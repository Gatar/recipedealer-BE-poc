package com.gatar.recipedealer.household;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

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
import com.gatar.recipedealer.auth.DeviceTokenService;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.llm.LlmTestConfiguration;
import com.gatar.recipedealer.llm.StubClaudeClient;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.node.ObjectNode;

/** The monthly Claude budget of a household, with LLM steps enabled and a stubbed Claude client. */
@IntegrationTest
@TestPropertySource(properties = "recipedealer.llm.api-key=test-key")
@Import(LlmTestConfiguration.class)
class LlmBudgetTest {

    static final String HAIKU = "claude-haiku-5-5";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    StubClaudeClient claude;

    @Autowired
    DeviceTokenService tokens;

    String sfx;
    UUID household;
    String token;

    @BeforeEach
    void setUp() {
        worker.drain();
        claude.reset();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        household = Ids.newId();
        jdbc.sql("INSERT INTO household (id, name, recipe_limit, llm_budget_usd) VALUES (:id, :name, 40, 1.00)")
                .param("id", household).param("name", "Budżet " + sfx).update();
        token = tokens.enroll(household, "Budget-" + sfx, "phone").token();
    }

    private ObjectNode capture(String slug, Consumer<ObjectNode> customizer) {
        ObjectNode capture = TestCaptures.webCapture(UUID.randomUUID(),
                "https://budget-" + sfx + ".pl/" + slug, "Szarlotka " + slug + " " + sfx);
        customizer.accept(capture);
        return capture;
    }

    private UUID processNew(ObjectNode capture) {
        UUID captureId = TestCaptures.upload(mvc, token, capture);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();
        return captureId;
    }

    private void budget(String value) {
        jdbc.sql("UPDATE household SET llm_budget_usd = CAST(:budget AS numeric) WHERE id = :id")
                .param("budget", value == null ? null : new BigDecimal(value)).param("id", household).update();
    }

    private String status(UUID captureId) {
        return TestAuth.body(TestCaptures.status(mvc, token, captureId));
    }

    @Test
    void withinTheBudgetClaudeIsCalledAndItsCostCounts() {
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka z Haiku " + sfx, 1000, 500, 0, 200));
        UUID captureId = processNew(capture("bezkrokow", c -> c.withObject("recipe").putArray("stepGroups")));

        assertThat(claude.requests).hasSize(1);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
        String me = TestAuth.body(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        assertThat(JsonPath.<Double>read(me, "$.household.llmBudgetUsd")).isEqualTo(1.0);
        assertThat(JsonPath.<Double>read(me, "$.household.llmSpentUsd")).isEqualTo(0.000263);
    }

    @Test
    void overTheBudgetThePartialHeuristicResultIsUsedWithoutClaude() {
        budget("0");
        UUID captureId = processNew(capture("bezkrokow", c -> c.withObject("recipe").putArray("stepGroups")));

        assertThat(claude.requests).isEmpty();
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
    }

    @Test
    void overTheBudgetACaptureWithoutResultSaysWhy() {
        budget("0");
        UUID captureId = processNew(capture("pusty", c -> {
            c.withObject("recipe").putArray("stepGroups");
            c.withObject("recipe").putArray("ingredientGroups");
        }));

        assertThat(claude.requests).isEmpty();
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("needs_attention");
        assertThat(JsonPath.<String>read(status(captureId), "$.reason")).isEqualTo("LLM_BUDGET_EXCEEDED");

        var forced = mvc.post().uri("/v1/captures/{id}/llm-extraction", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(forced).hasStatus(409);
        assertThat(JsonPath.<String>read(TestAuth.body(forced), "$.error")).isEqualTo("llm_budget_exceeded");

        // the admin raises the budget: the forced extraction runs
        assertThat(adminPatch("{\"llmBudgetUsd\": 2.5}")).hasStatusOk();
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka z Haiku " + sfx, 1000, 500, 0, 200));
        assertThat(mvc.post().uri("/v1/captures/{id}/llm-extraction", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(202);
        worker.drain();
        assertThat(claude.requests).hasSize(1);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
    }

    @Test
    void spendingCountsAgainstTheBudget() {
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka z Haiku " + sfx, 1000, 500, 0, 200));
        UUID captureId = processNew(capture("koszt", c -> c.withObject("recipe").putArray("stepGroups")));
        assertThat(claude.requests).hasSize(1);
        // the run cost more than the budget: the next forced extraction is refused
        budget("0.01");
        jdbc.sql("UPDATE processing_run SET cost_usd = 0.02 WHERE capture_id = :id AND step = 'haiku'")
                .param("id", captureId).update();
        var forced = mvc.post().uri("/v1/captures/{id}/llm-extraction", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(JsonPath.<String>read(TestAuth.body(forced), "$.error")).isEqualTo("llm_budget_exceeded");
        assertThat(claude.requests).hasSize(1);
    }

    @Test
    void theAdminSetsOrRemovesTheBudget() {
        String body = TestAuth.body(adminPatch("{\"llmBudgetUsd\": 0.555}"));
        assertThat(JsonPath.<Double>read(body, "$.llmBudgetUsd")).isEqualTo(0.56);
        assertThat(JsonPath.<Object>read(TestAuth.body(adminPatch("{\"llmBudgetUsd\": null}")), "$.llmBudgetUsd")).isNull();
        assertThat(adminPatch("{\"llmBudgetUsd\": -1}")).hasStatus(400);
        assertThat(adminPatch("{\"llmBudgetUsd\": \"dużo\"}")).hasStatus(400);
    }

    private MvcTestResult adminPatch(String body) {
        return mvc.patch().uri("/v1/admin/households/{id}", household)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
