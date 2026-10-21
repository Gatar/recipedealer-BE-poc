package com.gatar.recipedealer.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobQueue;
import com.gatar.recipedealer.processing.JobWorker;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class ProcessingAndResolutionTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    JobQueue jobs;

    @Autowired
    RevisionService revisions;

    @Autowired
    TransactionTemplate tx;

    String token;
    String suffix;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Processing", "phone");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
    }

    private String url(String slug) {
        return "https://www.example-" + suffix + ".pl/przepis/" + slug + "/";
    }

    private UUID upload(String slug, String title) {
        return TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(), url(slug), title));
    }

    private String status(UUID captureId) {
        return TestAuth.body(TestCaptures.status(mvc, token, captureId));
    }

    private UUID resolveNew(UUID captureId) {
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();
        return UUID.fromString(JsonPath.read(status(captureId), "$.recipes[0].recipeId"));
    }

    private Map<String, Object> recipe(UUID recipeId) {
        return jdbc.sql("""
                        SELECT r.title, r.head_revision_id, h.origin, h.needs_review, r.source_summary,
                               (SELECT count(*) FROM recipe_revision WHERE recipe_id = r.id) AS revisions
                        FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id WHERE r.id = :id""")
                .param("id", recipeId).query().singleRow();
    }

    @Test
    void processingLeadsToNeedsDecisionWithCandidateSummary() {
        UUID captureId = upload("szarlotka", "Szarlotka " + suffix);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("accepted");

        worker.drain();

        String status = status(captureId);
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("needs_decision");
        assertThat(JsonPath.<String>read(status, "$.candidates[0].title")).isEqualTo("Szarlotka " + suffix);
        assertThat(JsonPath.<Integer>read(status, "$.candidates[0].ingredientCount")).isEqualTo(6);
        assertThat(JsonPath.<Boolean>read(status, "$.candidates[0].needsReview")).isTrue();
        assertThat(JsonPath.<List<String>>read(status, "$.candidates[0].urls"))
                .containsExactly("https://example-" + suffix + ".pl/przepis/szarlotka");
        assertThat(JsonPath.<List<?>>read(status, "$.recipes")).isEmpty();

        var run = jdbc.sql("SELECT extractor_name, extractor_version, status FROM processing_run WHERE capture_id = :id")
                .param("id", captureId).query().singleRow();
        assertThat(run).containsEntry("extractor_name", "passthrough").containsEntry("extractor_version", "1")
                .containsEntry("status", "succeeded");
    }

    @Test
    void newResolutionAfterProcessingCreatesRecipeKeysAndRevision() {
        UUID captureId = upload("sernik", "Sernik " + suffix);
        worker.drain();

        var result = TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("done");
        UUID recipeId = UUID.fromString(JsonPath.read(TestAuth.body(result), "$.recipes[0].recipeId"));
        assertThat(JsonPath.<String>read(TestAuth.body(result), "$.recipes[0].title")).isEqualTo("Sernik " + suffix);

        assertThat(recipe(recipeId)).containsEntry("title", "Sernik " + suffix).containsEntry("origin", "MACHINE")
                .containsEntry("needs_review", true).containsEntry("revisions", 1L)
                .containsEntry("source_summary", "Kwestia Smaku");
        assertThat(jdbc.sql("SELECT kind, value, raw_value, normalizer_version FROM recipe_key WHERE recipe_id = :id")
                .param("id", recipeId).query().singleRow())
                .containsEntry("kind", "CANONICAL_URL")
                .containsEntry("value", "https://example-" + suffix + ".pl/przepis/sernik")
                .containsEntry("raw_value", url("sernik"))
                .containsEntry("normalizer_version", 1);
    }

    @Test
    void resolutionArrivingBeforeProcessingIsAppliedLater() {
        UUID captureId = upload("piernik", "Piernik " + suffix);

        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT count(*) FROM recipe WHERE title = :t").param("t", "Piernik " + suffix)
                .query(Long.class).single()).isZero();

        worker.drain();

        String status = status(captureId);
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("done");
        assertThat(JsonPath.<String>read(status, "$.recipes[0].title")).isEqualTo("Piernik " + suffix);
    }

    @Test
    void updateResolutionAddsRevisionAndMovesHead() {
        UUID first = upload("bigos", "Bigos " + suffix);
        worker.drain();
        UUID recipeId = resolveNew(first);
        UUID firstHead = (UUID) recipe(recipeId).get("head_revision_id");

        UUID second = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(), url("bigos"),
                "Bigos staropolski " + suffix));
        worker.drain();
        var result = TestCaptures.resolve(mvc, token, second, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}");

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("done");
        assertThat(JsonPath.<String>read(TestAuth.body(result), "$.message")).doesNotContain("already belongs");
        var row = recipe(recipeId);
        assertThat(row).containsEntry("title", "Bigos staropolski " + suffix).containsEntry("revisions", 2L);
        assertThat(jdbc.sql("SELECT parent_id FROM recipe_revision WHERE id = :id").param("id", row.get("head_revision_id"))
                .query(UUID.class).single()).isEqualTo(firstHead);
    }

    @Test
    void updateWithSameContentCreatesNoRevision() {
        UUID first = upload("rosol", "Rosół " + suffix);
        worker.drain();
        UUID recipeId = resolveNew(first);

        UUID second = upload("rosol", "Rosół " + suffix);
        worker.drain();
        assertThat(TestCaptures.resolve(mvc, token, second, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("done");

        assertThat(recipe(recipeId)).containsEntry("revisions", 1L);
    }

    @Test
    void keyOwnedByAnotherRecipeIsReportedNotMoved() {
        UUID a = upload("zurek", "Żurek " + suffix);
        UUID b = upload("barszcz", "Barszcz " + suffix);
        worker.drain();
        UUID zurek = resolveNew(a);
        UUID barszcz = resolveNew(b);

        UUID c = upload("zurek", "Żurek po śląsku " + suffix);
        worker.drain();
        var result = TestCaptures.resolve(mvc, token, c, "{\"action\":\"UPDATE\",\"recipeId\":\"" + barszcz + "\"}");

        assertThat(JsonPath.<String>read(TestAuth.body(result), "$.message"))
                .contains("already belongs to recipe \"Żurek " + suffix + "\"").contains(zurek.toString());
        assertThat(jdbc.sql("SELECT recipe_id FROM recipe_key WHERE value = :v")
                .param("v", "https://example-" + suffix + ".pl/przepis/zurek").query(UUID.class).single()).isEqualTo(zurek);
    }

    @Test
    void resolutionIsIdempotentAndCannotBeChangedOnceApplied() {
        UUID captureId = upload("makowiec", "Makowiec " + suffix);
        worker.drain();
        UUID recipeId = resolveNew(captureId);

        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        assertThat(jdbc.sql("SELECT count(*) FROM recipe WHERE title = :t").param("t", "Makowiec " + suffix)
                .query(Long.class).single()).isEqualTo(1);

        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}"))
                .hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("already_resolved");
    }

    @Test
    void pendingResolutionCanBeChangedBeforeProcessing() {
        UUID target = resolveNew(processed("kutia", "Kutia " + suffix));
        UUID captureId = upload("kutia-2", "Kutia wigilijna " + suffix);

        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + target + "\"}"))
                .hasStatusOk();
        worker.drain();

        assertThat(JsonPath.<String>read(status(captureId), "$.recipes[0].recipeId")).isEqualTo(target.toString());
    }

    @Test
    void invalidResolutionsAreRejected() {
        UUID captureId = processed("pierogi", "Pierogi " + suffix);

        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\"}")).hasStatus(400);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\",\"recipeId\":\"" + UUID.randomUUID() + "\"}"))
                .hasStatus(400);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + UUID.randomUUID() + "\"}"))
                .hasStatus(409);
        assertThat(mvc.put().uri("/v1/captures/{id}/extracted/1/resolution", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType("application/json").content("{\"action\":\"NEW\"}")).hasStatus(404);
        assertThat(TestCaptures.resolve(mvc, token, UUID.randomUUID(), "{\"action\":\"NEW\"}")).hasStatus(404);
    }

    @Test
    void captureWithoutIngredientsAndStepsAwaitsExtraction() {
        ObjectNode capture = TestCaptures.webCapture(UUID.randomUUID(), url("kartka"), "Kartka od babci " + suffix);
        ObjectNode recipe = capture.withObject("recipe");
        recipe.putArray("ingredientGroups");
        recipe.putArray("stepGroups");
        UUID captureId = TestCaptures.upload(mvc, token, capture);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();

        worker.drain();

        String status = status(captureId);
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("needs_attention");
        assertThat(JsonPath.<String>read(status, "$.reason")).isEqualTo("AWAITING_EXTRACTION");
        assertThat(JsonPath.<String>read(status, "$.message")).contains("decision is saved");
        assertThat(jdbc.sql("SELECT count(*) FROM recipe WHERE title = :t").param("t", "Kartka od babci " + suffix)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM processing_run WHERE capture_id = :id").param("id", captureId)
                .query(String.class).single()).isEqualTo("awaiting_extraction");
    }

    @Test
    void reprocessingNeverMovesAUserHeadAndCreatesOneProposal() {
        UUID captureId = processed("golabki", "Gołąbki " + suffix);
        UUID recipeId = resolveNew(captureId);
        ObjectNode edited = (ObjectNode) JsonMapper.builder().build().readTree(
                "{\"title\": \"Gołąbki mamy " + suffix + "\", \"ingredientGroups\": [{\"items\": [\"kapusta\"]}]}");
        UUID userRevision = tx.execute(s -> revisions.addRevision(recipeId, new RevisionService.Content(edited, Origin.USER,
                null, null, jdbc.sql("SELECT user_id FROM capture WHERE id = :id").param("id", captureId).query(UUID.class).single(),
                null, false), true).revisionId());

        jobs.enqueue(captureId);
        worker.drain();
        jobs.enqueue(captureId);
        worker.drain();

        var row = recipe(recipeId);
        assertThat(row).containsEntry("head_revision_id", userRevision).containsEntry("origin", "USER")
                .containsEntry("title", "Gołąbki mamy " + suffix).containsEntry("revisions", 3L);
        var proposal = jdbc.sql("SELECT origin, parent_id FROM recipe_revision WHERE recipe_id = :id AND parent_id = :head")
                .param("id", recipeId).param("head", userRevision).query().singleRow();
        assertThat(proposal).containsEntry("origin", "MACHINE");
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
    }

    @Test
    void reprocessingWithUnchangedContentCreatesNoRevision() {
        UUID captureId = processed("leniwe", "Leniwe " + suffix);
        UUID recipeId = resolveNew(captureId);

        jobs.enqueue(captureId);
        worker.drain();

        assertThat(recipe(recipeId)).containsEntry("revisions", 1L);
        assertThat(jdbc.sql("SELECT count(*) FROM processing_run WHERE capture_id = :id").param("id", captureId)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void inboxListsCapturesWaitingForTheUser() {
        UUID waiting = processed("faworki", "Faworki " + suffix);
        UUID done = processed("paczki", "Pączki " + suffix);
        resolveNew(done);

        var inbox = mvc.get().uri("/v1/captures?status=needs_decision")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(inbox).hasStatusOk();
        List<String> ids = JsonPath.read(TestAuth.body(inbox), "$[*].captureId");
        assertThat(ids).contains(waiting.toString()).doesNotContain(done.toString());

        assertThat(mvc.get().uri("/v1/captures?status=bogus").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(400);
    }

    @Test
    void candidatesPutExactUrlFirstThenSimilarTitles() {
        UUID byUrl = resolveNew(processed("nalesniki", "Naleśniki z serem " + suffix));
        UUID byTitle = resolveNew(processed("nalesniki-2", "Naleśniki z serem i rodzynkami " + suffix));

        var result = mvc.get().uri("/v1/recipes/candidates")
                .param("url", "http://example-" + suffix + ".pl/przepis/nalesniki?utm_source=facebook")
                .param("title", "nalesniki z serem " + suffix)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(result).hasStatusOk();
        String body = TestAuth.body(result);
        assertThat(JsonPath.<String>read(body, "$[0].recipeId")).isEqualTo(byUrl.toString());
        assertThat(JsonPath.<String>read(body, "$[0].matchReason")).isEqualTo("URL");
        assertThat(JsonPath.<Double>read(body, "$[0].score")).isEqualTo(1.0);
        List<String> titleMatches = JsonPath.read(body, "$[?(@.matchReason == 'TITLE')].recipeId");
        assertThat(titleMatches).contains(byTitle.toString()).doesNotContain(byUrl.toString());
        assertThat(JsonPath.<String>read(body, "$[0].sourceSummary")).isEqualTo("Kwestia Smaku");
    }

    @Test
    void candidatesTolerateMissingDiacriticsAndTypos() {
        UUID recipeId = resolveNew(processed("losos", "Łosoś pieczony " + suffix));

        var result = mvc.get().uri("/v1/recipes/candidates").param("title", "losos piecozny " + suffix)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        List<String> ids = JsonPath.read(TestAuth.body(result), "$[*].recipeId");
        assertThat(ids).contains(recipeId.toString());
    }

    private UUID processed(String slug, String title) {
        UUID captureId = upload(slug, title);
        worker.drain();
        return captureId;
    }
}
