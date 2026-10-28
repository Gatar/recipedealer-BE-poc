package com.gatar.recipedealer.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.llm.LlmPricing;
import com.gatar.recipedealer.processing.CaptureLoader;
import com.gatar.recipedealer.processing.ExtractionCascade;
import com.gatar.recipedealer.processing.JobQueue;
import com.gatar.recipedealer.processing.JobWorker;
import com.gatar.recipedealer.processing.ProcessingProperties;
import com.gatar.recipedealer.processing.ProcessingRepository;
import com.gatar.recipedealer.processing.ProcessingService;
import com.jayway.jsonpath.JsonPath;

/**
 * Auto-NEW is off in the shared test context (tests use unique titles); this test processes captures with its own
 * service that has it on.
 */
@IntegrationTest
class AutoNewTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    CaptureLoader loader;

    @Autowired
    ExtractionCascade cascade;

    @Autowired
    JobQueue jobs;

    @Autowired
    LlmPricing pricing;

    @Autowired
    ProcessingRepository processing;

    @Autowired
    ResolutionRepository resolutionRepository;

    @Autowired
    ResolutionService resolutions;

    @Autowired
    TransactionTemplate tx;

    ProcessingService autoNew;
    String token;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "AutoNew", "phone");
        worker.drain();
        autoNew = new ProcessingService(loader, cascade, processing, resolutionRepository, resolutions, jobs, pricing, tx,
                new ProcessingProperties(true));
    }

    /** A title no other test's recipe resembles. */
    private static String uniqueTitle() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String uniqueUrl() {
        return "https://www.auto-" + UUID.randomUUID() + ".pl/przepis/";
    }

    private String process(String url, String title) throws Exception {
        UUID captureId = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(), url, title));
        jdbc.sql("UPDATE job SET status = 'done' WHERE capture_id = :id").param("id", captureId).update();
        autoNew.process(captureId);
        return TestAuth.body(TestCaptures.status(mvc, token, captureId));
    }

    /** The stored decision (a done CaptureView has no candidates). */
    private String action(String status) {
        return jdbc.sql("SELECT action FROM capture_resolution WHERE capture_id = :id AND idx = 0")
                .param("id", UUID.fromString(JsonPath.read(status, "$.captureId"))).query(String.class).single();
    }

    @Test
    void captureWithoutAnyMatchBecomesANewRecipe() throws Exception {
        String title = uniqueTitle();
        String status = process(uniqueUrl(), title);

        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("done");
        assertThat(action(status)).isEqualTo("NEW");
        assertThat(JsonPath.<String>read(status, "$.recipes[0].title")).isEqualTo(title);
        UUID recipeId = UUID.fromString(JsonPath.read(status, "$.recipes[0].recipeId"));
        assertThat(jdbc.sql("SELECT count(*) FROM recipe_key WHERE recipe_id = :id").param("id", recipeId)
                .query(Long.class).single()).isPositive();
    }

    @Test
    void sameUrlAsksForADecision() throws Exception {
        String url = uniqueUrl();
        assertThat(JsonPath.<String>read(process(url, uniqueTitle()), "$.status")).isEqualTo("done");

        assertThat(JsonPath.<String>read(process(url, uniqueTitle()), "$.status")).isEqualTo("needs_decision");
    }

    @Test
    void similarTitleAsksForADecision() throws Exception {
        String title = uniqueTitle();
        assertThat(JsonPath.<String>read(process(uniqueUrl(), title), "$.status")).isEqualTo("done");

        assertThat(JsonPath.<String>read(process(uniqueUrl(), title + " x"), "$.status")).isEqualTo("needs_decision");
    }

    @Test
    void aDecisionSentBeforeProcessingWins() throws Exception {
        String title = uniqueTitle();
        String first = process(uniqueUrl(), title);
        UUID recipeId = UUID.fromString(JsonPath.read(first, "$.recipes[0].recipeId"));

        UUID captureId = TestCaptures.upload(mvc, token,
                TestCaptures.webCapture(UUID.randomUUID(), uniqueUrl(), uniqueTitle()));
        jdbc.sql("UPDATE job SET status = 'done' WHERE capture_id = :id").param("id", captureId).update();
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}"))
                .hasStatusOk();
        autoNew.process(captureId);

        String status = TestAuth.body(TestCaptures.status(mvc, token, captureId));
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("done");
        assertThat(action(status)).isEqualTo("UPDATE");
        assertThat(JsonPath.<String>read(status, "$.recipes[0].recipeId")).isEqualTo(recipeId.toString());
    }
}
