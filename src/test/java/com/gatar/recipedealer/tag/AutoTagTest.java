package com.gatar.recipedealer.tag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.llm.ClaudeClient;
import com.gatar.recipedealer.llm.LlmException;
import com.gatar.recipedealer.llm.LlmTestConfiguration;
import com.gatar.recipedealer.llm.StubClaudeClient;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

/** Tagging of new recipes by Claude Haiku (stubbed), turned on only in this test's context. */
@IntegrationTest
@TestPropertySource(properties = {"recipedealer.llm.api-key=test-key", "recipedealer.tagging.enabled=true"})
@Import(LlmTestConfiguration.class)
class AutoTagTest {

    static final String HAIKU = "claude-haiku-5-5";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    StubClaudeClient claude;

    String token;
    /** The catalog is global: every test uses a category of its own. */
    String meal;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "AutoTag", "phone");
        worker.drain();
        claude.reset();
        meal = "meal-" + UUID.randomUUID().toString().substring(0, 8);
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"breakfast\",\"title\":\"Śniadanie\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);
    }

    private MvcTestResult post(String uri, String body, Object... vars) {
        return mvc.post().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** Uploads a capture of its own page and runs the heuristic step: a candidate waiting for a decision. */
    private UUID processed() {
        String sfx = UUID.randomUUID().toString();
        UUID captureId = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(),
                "https://www.autotag-" + sfx + ".pl/przepis/", "Szarlotka " + sfx));
        worker.drain();
        return captureId;
    }

    private String resolve(UUID captureId, String body) {
        MvcTestResult result = TestCaptures.resolve(mvc, token, captureId, body);
        assertThat(result).hasStatusOk();
        return TestAuth.body(result);
    }

    private String status(UUID captureId) {
        return JsonPath.read(TestAuth.body(TestCaptures.status(mvc, token, captureId)), "$.status");
    }

    private List<String> tagsOf(UUID recipeId) {
        return jdbc.sql("SELECT category || ':' || tag FROM recipe_tag WHERE recipe_id = :id ORDER BY tag")
                .param("id", recipeId).query(String.class).list();
    }

    private Map<String, Object> tagging(UUID recipeId) {
        return jdbc.sql("SELECT status, array_to_string(tags, ',') AS tags, error_type, cost_usd FROM auto_tagging WHERE recipe_id = :id")
                .param("id", recipeId).query().singleRow();
    }

    private static UUID recipeId(String captureView) {
        return UUID.fromString(JsonPath.read(captureView, "$.recipes[0].recipeId"));
    }

    private static ClaudeClient.Response tags(String... ids) {
        return StubClaudeClient.response(HAIKU, "end_turn",
                "{\"tags\": [" + String.join(", ", List.of(ids).stream().map(id -> "\"" + id + "\"").toList()) + "]}",
                1000, 0, 0, 20);
    }

    @Test
    void newRecipeWithoutTagsIsTaggedBeforeTheCaptureIsDone() {
        UUID captureId = processed();
        claude.willAnswer(HAIKU, tags(meal + ":dinner", "made-up:tag"));

        String view = resolve(captureId, "{\"action\":\"NEW\"}");
        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("processing");
        UUID recipeId = recipeId(view);
        assertThat(tagsOf(recipeId)).isEmpty();

        worker.drain();

        assertThat(status(captureId)).isEqualTo("done");
        assertThat(tagsOf(recipeId)).containsExactly(meal + ":dinner");
        assertThat(tagging(recipeId)).containsEntry("status", "succeeded").containsEntry("tags", meal + ":dinner");

        assertThat(claude.requests).hasSize(1);
        ClaudeClient.Request request = claude.requests.getFirst();
        assertThat(request.model()).isEqualTo(HAIKU);
        assertThat(request.effort()).isEqualTo("low");
        String input = ((ClaudeClient.Text) request.parts().getFirst()).text();
        assertThat(input).contains(meal + ":dinner: Posiłek / Obiad").contains("Title: Szarlotka ");
        @SuppressWarnings("unchecked")
        var items = (Map<String, Object>) ((Map<String, Map<String, Object>>) request.schema().get("properties"))
                .get("tags").get("items");
        assertThat((List<String>) items.get("enum")).contains(meal + ":breakfast", meal + ":dinner");
    }

    @Test
    void taggingShowsInTheExtractionStats() {
        UUID captureId = processed();
        claude.willAnswer(HAIKU, tags(meal + ":breakfast"));
        UUID recipeId = recipeId(resolve(captureId, "{\"action\":\"NEW\"}"));
        worker.drain();

        var response = mvc.get().uri("/v1/recipes/{id}/extraction-stats", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(response).hasStatusOk();
        String body = TestAuth.body(response);
        // 1000 × 0.10 + 20 × 0.50 = 110 µUSD
        assertThat(JsonPath.<List<String>>read(body, "$.runs[*].step")).containsExactly("heuristic", "tagging");
        assertThat(JsonPath.<String>read(body, "$.runs[1].extractorVersion")).isEqualTo(HAIKU + "/t1");
        assertThat(JsonPath.<Boolean>read(body, "$.runs[1].applied")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.totals.llmRuns")).isEqualTo(1);
        assertThat(JsonPath.<Double>read(body, "$.totals.costUsd")).isEqualTo(0.00011);
    }

    @Test
    void tagsChosenByTheUserAreNotExtended() {
        UUID captureId = processed();

        String view = resolve(captureId, "{\"action\":\"NEW\",\"tags\":[\"" + meal + ":breakfast\"]}");

        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("done");
        worker.drain();
        assertThat(tagsOf(recipeId(view))).containsExactly(meal + ":breakfast");
        assertThat(claude.requests).isEmpty();
    }

    @Test
    void anUpdateIsNotTagged() {
        UUID first = processed();
        UUID recipeId = recipeId(resolve(first, "{\"action\":\"NEW\",\"tags\":[\"" + meal + ":breakfast\"]}"));
        UUID second = processed();

        String view = resolve(second, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}");

        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("done");
        worker.drain();
        assertThat(claude.requests).isEmpty();
    }

    @Test
    void aFailedCallLeavesTheRecipeWithoutTags() {
        UUID captureId = processed();
        claude.willAnswer(HAIKU, StubClaudeClient.error(LlmException.Kind.TRANSIENT, "overloaded_error", 529));
        UUID recipeId = recipeId(resolve(captureId, "{\"action\":\"NEW\"}"));

        worker.drain();

        assertThat(status(captureId)).isEqualTo("done");
        assertThat(tagsOf(recipeId)).isEmpty();
        assertThat(tagging(recipeId)).containsEntry("status", "failed").containsEntry("error_type", "overloaded_error");
    }

    @Test
    void tagsSetByTheUserMeanwhileWin() {
        UUID captureId = processed();
        claude.willAnswer(HAIKU, tags(meal + ":dinner"));
        UUID recipeId = recipeId(resolve(captureId, "{\"action\":\"NEW\"}"));
        assertThat(mvc.put().uri("/v1/recipes/{id}/tags", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"" + meal + ":breakfast\"]}"))
                .hasStatusOk();

        worker.drain();

        assertThat(status(captureId)).isEqualTo("done");
        assertThat(tagsOf(recipeId)).containsExactly(meal + ":breakfast");
        assertThat(tagging(recipeId)).containsEntry("status", "skipped").containsEntry("error_type", "recipe_tagged_or_gone");
    }
}
