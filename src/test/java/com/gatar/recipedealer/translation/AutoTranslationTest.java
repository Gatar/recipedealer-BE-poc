package com.gatar.recipedealer.translation;

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
import com.gatar.recipedealer.llm.LlmTestConfiguration;
import com.gatar.recipedealer.llm.StubClaudeClient;
import com.gatar.recipedealer.llm.TranslatePrompt;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Translation (then tagging) of new recipes by Claude Haiku (stubbed), turned on only in this test's context. */
@IntegrationTest
@TestPropertySource(properties = {"recipedealer.llm.api-key=test-key", "recipedealer.translation.enabled=true",
        "recipedealer.tagging.enabled=true"})
@Import(LlmTestConfiguration.class)
class AutoTranslationTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final JsonMapper JSON = JsonMapper.builder().build();

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
    String sfx;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "AutoTranslate", "phone");
        worker.drain();
        claude.reset();
        sfx = UUID.randomUUID().toString();
        meal = "meal-" + sfx.substring(0, 8);
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dessert\",\"title\":\"Deser\"}", meal);
    }

    private void post(String uri, String body, Object... vars) {
        mvc.post().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** An English capture of its own page. */
    private ObjectNode englishCapture() {
        ObjectNode capture = TestCaptures.webCapture(UUID.randomUUID(), "https://www.translate-" + sfx + ".com/pie/",
                "Apple pie " + sfx);
        ObjectNode recipe = capture.withObject("recipe");
        recipe.put("language", "en");
        recipe.set("servings", JSON.readTree("{\"text\": \"8 servings\", \"count\": 8}"));
        recipe.set("ingredientGroups", JSON.readTree("""
                [{"name": "Crust", "items": ["2 cups flour", "8 oz butter"]}, {"name": null, "items": ["4 apples"]}]"""));
        recipe.set("stepGroups", JSON.readTree("[{\"name\": null, \"items\": [\"Mix.\", \"Bake at 350°F for 45 minutes.\"]}]"));
        recipe.set("notes", JSON.readTree("[\"Serve warm.\"]"));
        return capture;
    }

    private MvcTestResult upload(ObjectNode capture, String translateTo) {
        var request = mvc.put().uri("/v1/captures/{id}", capture.get("captureId").asString())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token));
        if (translateTo != null) {
            request = request.header("Translate-To", translateTo);
        }
        return request.multipart().part(TestCaptures.capturePart(TestCaptures.bytes(capture))).exchange();
    }

    /** Uploads and runs the heuristic step: a candidate waiting for a decision. */
    private UUID processed(ObjectNode capture, String translateTo) {
        assertThat(upload(capture, translateTo)).hasStatus(201);
        worker.drain();
        return UUID.fromString(capture.get("captureId").asString());
    }

    private String resolve(UUID captureId, String body) {
        MvcTestResult result = TestCaptures.resolve(mvc, token, captureId, body);
        assertThat(result).hasStatusOk();
        return TestAuth.body(result);
    }

    private String status(UUID captureId) {
        return JsonPath.read(TestAuth.body(TestCaptures.status(mvc, token, captureId)), "$.status");
    }

    private static UUID recipeId(String captureView) {
        return UUID.fromString(JsonPath.read(captureView, "$.recipes[0].recipeId"));
    }

    private String head(UUID recipeId) {
        return jdbc.sql("SELECT h.content::text FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id WHERE r.id = :id")
                .param("id", recipeId).query(String.class).single();
    }

    private Map<String, Object> translation(UUID recipeId) {
        return jdbc.sql("SELECT status, source_language, error_type, revision_id IS NOT NULL AS revision FROM auto_translation WHERE recipe_id = :id")
                .param("id", recipeId).query().singleRow();
    }

    private List<String> tagsOf(UUID recipeId) {
        return jdbc.sql("SELECT category || ':' || tag FROM recipe_tag WHERE recipe_id = :id")
                .param("id", recipeId).query(String.class).list();
    }

    private static ClaudeClient.Response polish(String ingredients) {
        return StubClaudeClient.response(HAIKU, "end_turn", """
                {"sourceLanguage": "en", "alreadyInTarget": false, "title": "Szarlotka amerykańska",
                 "servingsText": "8 porcji",
                 "ingredientGroups": %s,
                 "stepGroups": [{"name": null, "items": ["Wymieszać.", "Piec w 177°C (350°F) przez 45 minut."]}],
                 "notes": ["Podawać na ciepło."]}""".formatted(ingredients), 1500, 0, 0, 400);
    }

    private static final String POLISH_INGREDIENTS = """
            [{"name": "Spód", "items": ["2 szklanki (cups) mąki", "227 g (8 oz) masła"]}, {"name": null, "items": ["4 jabłka"]}]""";

    @Test
    void anEnglishRecipeIsTranslatedThenTaggedBeforeDone() {
        UUID captureId = processed(englishCapture(), "pl-PL");
        claude.willAnswer(HAIKU, polish(POLISH_INGREDIENTS),
                StubClaudeClient.response(HAIKU, "end_turn", "{\"tags\": [\"" + meal + ":dessert\"]}", 1000, 0, 0, 20));

        String view = resolve(captureId, "{\"action\":\"NEW\"}");
        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("processing");
        UUID recipeId = recipeId(view);

        worker.drain();

        assertThat(status(captureId)).isEqualTo("done");
        String head = head(recipeId);
        assertThat(JsonPath.<String>read(head, "$.title")).isEqualTo("Szarlotka amerykańska");
        assertThat(JsonPath.<String>read(head, "$.language")).isEqualTo("pl");
        assertThat(JsonPath.<String>read(head, "$.servings.text")).isEqualTo("8 porcji");
        assertThat(JsonPath.<Integer>read(head, "$.servings.count")).isEqualTo(8);
        assertThat(JsonPath.<String>read(head, "$.ingredientGroups[0].items[1]")).isEqualTo("227 g (8 oz) masła");
        assertThat(JsonPath.<List<String>>read(head, "$.images")).isNotEmpty();
        assertThat(jdbc.sql("SELECT title FROM recipe WHERE id = :id").param("id", recipeId).query(String.class).single())
                .isEqualTo("Szarlotka amerykańska");
        // the original stays in the history
        assertThat(jdbc.sql("SELECT count(*) FROM recipe_revision WHERE recipe_id = :id").param("id", recipeId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(translation(recipeId)).containsEntry("status", "succeeded").containsEntry("source_language", "en")
                .containsEntry("revision", true);
        assertThat(tagsOf(recipeId)).containsExactly(meal + ":dessert");

        assertThat(claude.requests).hasSize(2);
        ClaudeClient.Request request = claude.requests.getFirst();
        assertThat(request.effort()).isEqualTo("high");
        assertThat(((ClaudeClient.Text) request.parts().getFirst()).text())
                .startsWith("Target language: Polish (pl)").contains("8 oz butter");
        // tags are chosen from the translated text
        assertThat(((ClaudeClient.Text) claude.requests.get(1).parts().getFirst()).text())
                .contains("Title: Szarlotka amerykańska");

        var stats = TestAuth.body(mvc.get().uri("/v1/recipes/{id}/extraction-stats", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        assertThat(JsonPath.<List<String>>read(stats, "$.runs[*].step")).containsExactly("heuristic", "translation", "tagging");
        assertThat(JsonPath.<String>read(stats, "$.runs[1].extractorVersion")).isEqualTo(HAIKU + "/" + TranslatePrompt.VERSION);
        assertThat(JsonPath.<Boolean>read(stats, "$.runs[1].applied")).isTrue();
    }

    @Test
    void aRecipeInTheTargetLanguageIsNotTranslated() {
        ObjectNode capture = englishCapture();
        capture.withObject("recipe").put("language", "pl");
        UUID captureId = processed(capture, "pl");

        String view = resolve(captureId, "{\"action\":\"NEW\",\"tags\":[\"" + meal + ":dessert\"]}");

        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("done");
        worker.drain();
        assertThat(claude.requests).isEmpty();
    }

    @Test
    void withoutTheHeaderNothingIsTranslated() {
        UUID captureId = processed(englishCapture(), null);

        String view = resolve(captureId, "{\"action\":\"NEW\",\"tags\":[\"" + meal + ":dessert\"]}");

        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("done");
        worker.drain();
        assertThat(claude.requests).isEmpty();
    }

    @Test
    void anAnswerThatChangesTheStructureIsRejectedAndTheRecipeStillTagged() {
        UUID captureId = processed(englishCapture(), "pl");
        claude.willAnswer(HAIKU, polish("[{\"name\": \"Spód\", \"items\": [\"mąka i masło\"]}, {\"name\": null, \"items\": [\"4 jabłka\"]}]"),
                StubClaudeClient.response(HAIKU, "end_turn", "{\"tags\": []}", 1000, 0, 0, 20));
        UUID recipeId = recipeId(resolve(captureId, "{\"action\":\"NEW\"}"));

        worker.drain();

        assertThat(status(captureId)).isEqualTo("done");
        assertThat(JsonPath.<String>read(head(recipeId), "$.title")).startsWith("Apple pie");
        assertThat(translation(recipeId)).containsEntry("status", "failed").containsEntry("error_type", "invalid_output")
                .containsEntry("revision", false);
        assertThat(claude.requests).hasSize(2);
    }

    @Test
    void aBadHeaderIsRejected() {
        assertThat(upload(englishCapture(), "polish!")).hasStatus(400);
    }
}
