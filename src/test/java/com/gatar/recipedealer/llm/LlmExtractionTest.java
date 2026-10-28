package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobWorker;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.gatar.recipedealer.recipe.RevisionService;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The extraction cascade with LLM steps enabled and a stubbed Claude client. */
@IntegrationTest
@TestPropertySource(properties = "recipedealer.llm.api-key=test-key")
@Import(LlmTestConfiguration.class)
class LlmExtractionTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final String SONNET = "claude-sonnet-5-5";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    StubClaudeClient claude;

    @Autowired
    RevisionService revisions;

    @Autowired
    TransactionTemplate tx;

    String token;
    String suffix;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Llm", "phone");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
        claude.reset();
    }

    private ObjectNode capture(String slug, Consumer<ObjectNode> customizer) {
        ObjectNode capture = TestCaptures.webCapture(UUID.randomUUID(),
                "https://www.llm-" + suffix + ".pl/przepis/" + slug + "/", "Szarlotka " + slug + " " + suffix);
        customizer.accept(capture);
        return capture;
    }

    private static void withoutSteps(ObjectNode capture) {
        capture.withObject("recipe").putArray("stepGroups");
    }

    private static void empty(ObjectNode capture) {
        capture.withObject("recipe").putArray("stepGroups");
        capture.withObject("recipe").putArray("ingredientGroups");
    }

    /** Uploads, stores a NEW decision and runs all jobs. */
    private UUID processNew(ObjectNode capture) {
        UUID captureId = TestCaptures.upload(mvc, token, capture);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();
        return captureId;
    }

    private String status(UUID captureId) {
        return TestAuth.body(TestCaptures.status(mvc, token, captureId));
    }

    private List<Map<String, Object>> runs(UUID captureId) {
        return jdbc.sql("""
                        SELECT step, status, purpose, array_to_string(validation_errors, ',') AS errors, model, error_type,
                               input_tokens, cost_usd
                        FROM processing_run WHERE capture_id = :id ORDER BY started_at, id""")
                .param("id", captureId).query().listOfRows();
    }

    private Map<String, Object> recipeOf(UUID captureId) {
        return jdbc.sql("""
                        SELECT r.id, r.title, h.origin, h.needs_review, h.content::text AS content,
                               (SELECT count(*) FROM recipe_revision WHERE recipe_id = r.id) AS revisions
                        FROM capture_resolution cr JOIN recipe r ON r.id = cr.applied_recipe_id
                        JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE cr.capture_id = :id""")
                .param("id", captureId).query().singleRow();
    }

    private MvcTestResult force(UUID captureId) {
        return mvc.post().uri("/v1/captures/{id}/llm-extraction", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private MvcTestResult admin(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange();
    }

    @Test
    void validHeuristicResultNeedsNoLlm() {
        UUID captureId = processNew(capture("pelna", c -> { }));

        assertThat(claude.requests).isEmpty();
        assertThat(runs(captureId)).singleElement().satisfies(run -> assertThat(run)
                .containsEntry("step", "heuristic").containsEntry("status", "succeeded").containsEntry("errors", null));
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
    }

    @Test
    void partialHeuristicGoesToHaikuWhoseValidResultIsApplied() {
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka z Haiku " + suffix, 1000, 500, 0, 200));

        UUID captureId = processNew(capture("bezkrokow", LlmExtractionTest::withoutSteps));

        assertThat(runs(captureId)).extracting(run -> run.get("step") + ":" + run.get("status") + ":" + run.get("errors"))
                .containsExactly("heuristic:rejected:no_steps", "haiku:succeeded:null");
        assertThat(runs(captureId).get(1)).containsEntry("model", HAIKU).containsEntry("input_tokens", 1000L);
        assertThat((BigDecimal) runs(captureId).get(1).get("cost_usd")).isEqualByComparingTo("0.000263");
        var recipe = recipeOf(captureId);
        assertThat(recipe).containsEntry("title", "Szarlotka z Haiku " + suffix).containsEntry("origin", "MACHINE")
                .containsEntry("needs_review", true);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");

        var request = claude.requests.getFirst();
        assertThat(request.model()).isEqualTo(HAIKU);
        assertThat(request.effort()).isEqualTo("medium");
        assertThat(request.systemPrompt()).isEqualTo(RecipePrompt.SYSTEM_PROMPT);
        assertThat(((ClaudeClient.Text) request.parts().getLast()).text()).contains("Readable text of the web page:");
    }

    /** A 1000×800 photo: the dish (red) in the top half, the recipe text (white) below. */
    private UUID processNewWithPhoto(String slug) throws IOException {
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 800);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 1000, 400);
        g.dispose();
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", jpeg);
        ObjectNode capture = capture(slug, c -> {
            withoutSteps(c);
            c.withObject("recipe").remove("images");
            c.withArray("attachments").add(TestCaptures.attachment("photo.jpg", "image", "image/jpeg"));
        });
        UUID captureId = UUID.fromString(capture.get("captureId").asString());
        assertThat(TestCaptures.put(mvc, token, captureId, TestCaptures.capturePart(TestCaptures.bytes(capture)),
                TestCaptures.filePart("photo.jpg", "image/jpeg", jpeg.toByteArray()))).hasStatus(201);
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();
        return captureId;
    }

    private String thumbnail(UUID captureId) {
        return jdbc.sql("SELECT thumbnail_blob FROM recipe WHERE id = :id").param("id", recipeOf(captureId).get("id"))
                .query(String.class).optional().orElse(null);
    }

    private Map<String, Object> dishPhoto(UUID captureId) {
        return jdbc.sql("SELECT attachment_name, blob_sha256, x, y, width, height, rotation FROM dish_photo WHERE capture_id = :id")
                .param("id", captureId).query().singleRow();
    }

    @Test
    void dishPhotoIsRefinedCutOutTurnedUprightAndBecomesTheThumbnail() throws IOException {
        // The first box is too tall (600 px; the dish is the top 400 px); the second look gets it with a margin
        // (the whole 1000 × 800 photo) and answers its top half.
        claude.willAnswer(HAIKU, StubClaudeClient.recipeWithDishPhoto(HAIKU, "Szarlotka ze zdjęciem " + suffix,
                        "{\"found\": true, \"image\": 1, \"left\": 0, \"top\": 0, \"right\": 1000, \"bottom\": 750, \"rotation\": 90}"),
                StubClaudeClient.response(HAIKU, "end_turn",
                        "{\"found\": true, \"left\": 0, \"top\": 0, \"right\": 1000, \"bottom\": 500}", 800, 0, 0, 30));

        UUID captureId = processNewWithPhoto("zdjecie");

        assertThat(claude.requests).hasSize(2);
        assertThat(claude.requests.getFirst().parts().getFirst()).isEqualTo(new ClaudeClient.Text("Image 1, photo.jpg:"));
        var secondLook = claude.requests.get(1);
        assertThat(secondLook.systemPrompt()).isEqualTo(RecipePrompt.DISH_PHOTO_PROMPT);
        BufferedImage searched = ImageIO.read(new ByteArrayInputStream(((ClaudeClient.Image) secondLook.parts().getFirst()).data()));
        assertThat(searched.getWidth()).isEqualTo(1000);
        assertThat(searched.getHeight()).isEqualTo(800);
        assertThat(runs(captureId).get(1)).containsEntry("input_tokens", 1800L);

        var photo = dishPhoto(captureId);
        assertThat(photo).containsEntry("attachment_name", "photo.jpg").containsEntry("x", 0).containsEntry("y", 0)
                .containsEntry("width", 1000).containsEntry("height", 400).containsEntry("rotation", 90);
        assertThat(thumbnail(captureId)).isEqualTo(photo.get("blob_sha256"));
        var blob = mvc.get().uri("/v1/blobs/{sha256}", photo.get("blob_sha256"))
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(blob).hasStatusOk().hasContentType("image/jpeg");
        BufferedImage cutOut = ImageIO.read(new ByteArrayInputStream(blob.getResponse().getContentAsByteArray()));
        assertThat(cutOut.getWidth()).isEqualTo(400);
        assertThat(cutOut.getHeight()).isEqualTo(1000);
        assertThat(new Color(cutOut.getRGB(200, 500)).getRed()).isGreaterThan(200);
    }

    @Test
    void failedSecondLookKeepsTheFirstBox() throws IOException {
        claude.willAnswer(HAIKU, StubClaudeClient.recipeWithDishPhoto(HAIKU, "Szarlotka z pierwszym wycinkiem " + suffix,
                        "{\"found\": true, \"image\": 1, \"left\": 0, \"top\": 0, \"right\": 1000, \"bottom\": 500, \"rotation\": 0}"),
                StubClaudeClient.error(LlmException.Kind.TRANSIENT, "overloaded_error", 529));

        UUID captureId = processNewWithPhoto("pierwszy");

        assertThat(runs(captureId).get(1)).containsEntry("status", "succeeded");
        assertThat(dishPhoto(captureId)).containsEntry("width", 1000).containsEntry("height", 400)
                .containsEntry("rotation", 0);
        assertThat(thumbnail(captureId)).isNotNull();
    }

    @Test
    void photoWithoutTheDishGivesNoThumbnail() throws IOException {
        claude.willAnswer(HAIKU, StubClaudeClient.recipeWithDishPhoto(HAIKU, "Szarlotka bez zdjęcia " + suffix,
                "{\"found\": false, \"image\": 0, \"left\": 0, \"top\": 0, \"right\": 0, \"bottom\": 0, \"rotation\": 0}"));

        UUID captureId = processNewWithPhoto("tekst");

        assertThat(jdbc.sql("SELECT blob_sha256 FROM dish_photo WHERE capture_id = :id").param("id", captureId)
                .query().singleRow()).containsEntry("blob_sha256", null);
        assertThat(thumbnail(captureId)).isNull();
    }

    @Test
    void invalidHaikuResultEscalatesToSonnet() {
        claude.willAnswer(HAIKU, StubClaudeClient.withoutSteps(HAIKU, "Szarlotka niepełna"));
        claude.willAnswer(SONNET, StubClaudeClient.recipe(SONNET, "Szarlotka z Sonneta " + suffix, 2000, 0, 1000, 300));

        UUID captureId = processNew(capture("eskalacja", LlmExtractionTest::empty));

        assertThat(runs(captureId)).extracting(run -> run.get("step") + ":" + run.get("status"))
                .containsExactly("heuristic:rejected", "haiku:rejected", "sonnet:succeeded");
        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka z Sonneta " + suffix);
        assertThat(claude.requests).extracting(ClaudeClient.Request::model).containsExactly(HAIKU, SONNET);
    }

    @Test
    void whenEveryStepFailsTheBestNonEmptyResultIsAppliedForReview() {
        claude.willAnswer(HAIKU, StubClaudeClient.response(HAIKU, "max_tokens", "{\"title\": \"Ucię", 100, 0, 0, 16000));
        claude.willAnswer(SONNET, StubClaudeClient.refusal(SONNET, "general_harms"));

        UUID captureId = processNew(capture("fallback", LlmExtractionTest::withoutSteps));

        assertThat(runs(captureId)).extracting(run -> run.get("step") + ":" + run.get("status") + ":" + run.get("errors"))
                .containsExactly("heuristic:succeeded:no_steps", "haiku:rejected:max_tokens,no_title,no_ingredients,no_steps",
                        "sonnet:rejected:refusal:general_harms,no_title,no_ingredients,no_steps");
        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka fallback " + suffix)
                .containsEntry("needs_review", true);
    }

    @Test
    void whenEveryResultIsEmptyTheCaptureWaitsForExtraction() {
        claude.willAnswer(HAIKU, StubClaudeClient.response(HAIKU, "end_turn",
                "{\"recipeFound\": false, \"title\": null, \"ingredientGroups\": [], \"stepGroups\": []}", 100, 0, 0, 20));
        claude.willAnswer(SONNET, StubClaudeClient.response(SONNET, "end_turn", "not json", 100, 0, 0, 5));

        UUID captureId = processNew(capture("pusta", LlmExtractionTest::empty));

        assertThat(runs(captureId)).extracting(run -> run.get("step") + ":" + run.get("status") + ":" + run.get("errors"))
                .containsExactly("heuristic:rejected:no_ingredients,no_steps",
                        "haiku:rejected:no_recipe_found,no_title,no_ingredients,no_steps",
                        "sonnet:awaiting_extraction:invalid_output,no_title,no_ingredients,no_steps");
        String status = status(captureId);
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("needs_attention");
        assertThat(JsonPath.<String>read(status, "$.reason")).isEqualTo("AWAITING_EXTRACTION");
    }

    @Test
    void transientErrorRetriesTheSameStepInsteadOfEscalating() {
        claude.willAnswer(HAIKU, StubClaudeClient.error(LlmException.Kind.TRANSIENT, "overloaded_error", 529),
                StubClaudeClient.recipe(HAIKU, "Szarlotka po przeciążeniu " + suffix, 100, 0, 0, 100));

        UUID captureId = processNew(capture("przeciazenie", LlmExtractionTest::withoutSteps));
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("processing");
        jdbc.sql("UPDATE job SET next_attempt_at = now() WHERE capture_id = :id AND status = 'queued'")
                .param("id", captureId).update();
        worker.drain();

        assertThat(runs(captureId)).extracting(run -> run.get("step") + ":" + run.get("status") + ":" + run.get("error_type"))
                .containsExactly("heuristic:rejected:null", "haiku:failed:overloaded_error", "haiku:succeeded:null");
        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka po przeciążeniu " + suffix);
    }

    @Test
    void authOrCreditErrorStopsWithoutRetry() {
        claude.willAnswer(HAIKU, StubClaudeClient.error(LlmException.Kind.UNAVAILABLE, "billing_error", 402));

        UUID captureId = processNew(capture("kredyt", LlmExtractionTest::withoutSteps));

        String status = status(captureId);
        assertThat(JsonPath.<String>read(status, "$.status")).isEqualTo("needs_attention");
        assertThat(JsonPath.<String>read(status, "$.reason")).isEqualTo("LLM_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT status || ':' || attempts FROM job WHERE capture_id = :id AND step = 'haiku'")
                .param("id", captureId).query(String.class).single()).isEqualTo("needs_attention:1");
        assertThat(claude.requests).hasSize(1);
    }

    @Test
    void fieldsTheUserEditedOnThePhoneAreNeverOverwritten() {
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Tytuł od modelu", 100, 0, 0, 100));

        UUID captureId = processNew(capture("tytul", c -> {
            withoutSteps(c);
            c.withObject("recipe").put("title", "Szarlotka mojej mamy " + suffix);
            c.withObject("provenance").put("title", "user");
        }));

        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka mojej mamy " + suffix);
    }

    @Test
    void forcedExtractionUpdatesTheRecipeButNeverMovesAUserHead() {
        UUID captureId = processNew(capture("wymuszona", c -> { }));
        UUID recipeId = (UUID) recipeOf(captureId).get("id");
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka wymuszona " + suffix, 100, 0, 0, 100),
                StubClaudeClient.recipe(HAIKU, "Szarlotka wymuszona drugi raz " + suffix, 100, 0, 0, 100));

        var forced = force(captureId);
        assertThat(forced).hasStatus(202);
        assertThat(JsonPath.<String>read(TestAuth.body(forced), "$.status")).isEqualTo("processing");
        assertThat(force(captureId)).hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("extraction_in_progress");
        worker.drain();

        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka wymuszona " + suffix)
                .containsEntry("origin", "MACHINE").containsEntry("revisions", 2L);
        assertThat(runs(captureId).getLast()).containsEntry("purpose", "forced").containsEntry("step", "haiku");
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");

        ObjectNode edited = (ObjectNode) JsonMapper.builder().build().readTree(
                "{\"title\": \"Szarlotka poprawiona " + suffix + "\", \"ingredientGroups\": [{\"items\": [\"jabłka\"]}]}");
        UUID userId = jdbc.sql("SELECT user_id FROM capture WHERE id = :id").param("id", captureId).query(UUID.class).single();
        tx.executeWithoutResult(s -> revisions.addRevision(recipeId,
                new RevisionService.Content(edited, 1, Origin.USER, null, null, userId, null, false), true));
        assertThat(force(captureId)).hasStatus(202);
        worker.drain();

        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka poprawiona " + suffix)
                .containsEntry("origin", "USER").containsEntry("revisions", 4L);
    }

    @Test
    void forcingNeedsAnExistingCaptureWithInput() {
        assertThat(force(UUID.randomUUID())).hasStatus(404);

        UUID captureId = processNew(capture("bezwejscia", c -> c.withObject("raw").withObject("web").remove("readableText")));
        assertThat(force(captureId)).hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("no_llm_input");
    }

    @Test
    void comparisonRunsAreReportedButNeverApplied() {
        UUID captureId = processNew(capture("porownanie", c -> { }));
        var before = recipeOf(captureId);
        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Haiku " + suffix, 1000, 0, 800, 100));
        claude.willAnswer(SONNET, StubClaudeClient.withoutSteps(SONNET, "Sonnet " + suffix));

        var queued = admin(mvc.post().uri("/v1/admin/llm-comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"captureIds\": [\"" + captureId + "\"], \"steps\": [\"haiku\", \"sonnet\"]}"));
        assertThat(queued).hasStatus(202);
        assertThat(JsonPath.<List<String>>read(TestAuth.body(queued), "$.queued[*].step")).containsExactly("haiku", "sonnet");
        worker.drain();

        assertThat(recipeOf(captureId)).isEqualTo(before);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
        assertThat(runs(captureId)).extracting(run -> run.get("purpose") + ":" + run.get("step") + ":" + run.get("status"))
                .containsExactly("pipeline:heuristic:succeeded", "comparison:haiku:succeeded", "comparison:sonnet:rejected");

        String report = TestAuth.body(admin(mvc.get().uri("/v1/admin/llm-comparisons?captureIds={id}", captureId)));
        assertThat(JsonPath.<List<String>>read(report, "$[0].runs[*].run.purpose"))
                .containsExactly("pipeline", "comparison", "comparison");
        assertThat(JsonPath.<List<Boolean>>read(report, "$[0].runs[*].cacheHit")).containsExactly(false, true, false);
        assertThat(JsonPath.<String>read(report, "$[0].runs[1].candidates[0].title")).isEqualTo("Haiku " + suffix);
        assertThat(JsonPath.<List<String>>read(report, "$[0].runs[2].run.validationErrors")).containsExactly("no_steps");

        // A later decision or reprocessing still works on the pipeline result, not on a comparison run.
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        assertThat(recipeOf(captureId)).isEqualTo(before);
    }

    @Test
    void adminReprocessesCapturesWaitingForExtraction() {
        claude.willAnswer(HAIKU, StubClaudeClient.response(HAIKU, "end_turn", "{\"recipeFound\": false}", 10, 0, 0, 5));
        claude.willAnswer(SONNET, StubClaudeClient.response(SONNET, "end_turn", "{\"recipeFound\": false}", 10, 0, 0, 5));
        UUID captureId = processNew(capture("czeka", LlmExtractionTest::empty));
        assertThat(JsonPath.<String>read(status(captureId), "$.reason")).isEqualTo("AWAITING_EXTRACTION");

        claude.willAnswer(HAIKU, StubClaudeClient.recipe(HAIKU, "Szarlotka odczytana " + suffix, 100, 0, 0, 100));
        var result = admin(mvc.post().uri("/v1/admin/llm-extractions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"captureIds\": [\"" + captureId + "\", \"" + UUID.randomUUID() + "\"]}"));
        assertThat(result).hasStatus(202);
        assertThat(JsonPath.<List<String>>read(TestAuth.body(result), "$.queued")).containsExactly(captureId.toString());
        assertThat(JsonPath.<List<String>>read(TestAuth.body(result), "$.skipped[*].error")).containsExactly("not_found");
        worker.drain();

        // The NEW decision stored before extraction creates the recipe now.
        assertThat(recipeOf(captureId)).containsEntry("title", "Szarlotka odczytana " + suffix);
        assertThat(JsonPath.<String>read(status(captureId), "$.status")).isEqualTo("done");
    }

    @Test
    void extractionStatsAggregateTheRunsOfTheRecipesCaptures() {
        claude.willAnswer(HAIKU, StubClaudeClient.withoutSteps(HAIKU, "Szarlotka bez kroków"));
        claude.willAnswer(SONNET, StubClaudeClient.recipe(SONNET, "Szarlotka statystyczna " + suffix, 2000, 0, 1000, 300));
        UUID captureId = processNew(capture("statystyki", LlmExtractionTest::withoutSteps));
        UUID recipeId = (UUID) recipeOf(captureId).get("id");

        var response = mvc.get().uri("/v1/recipes/{id}/extraction-stats", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(response).hasStatusOk();
        String body = TestAuth.body(response);

        // haiku: 100 × 0.10 + 50 × 0.50 = 35 µUSD; sonnet: 2000 × 2.00 + 1000 × 0.10 + 300 × 10.00 = 7100 µUSD
        assertThat(JsonPath.<Integer>read(body, "$.totals.runs")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.totals.llmRuns")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.totals.inputTokens")).isEqualTo(2100);
        assertThat(JsonPath.<Integer>read(body, "$.totals.cacheReadInputTokens")).isEqualTo(1000);
        assertThat(JsonPath.<Integer>read(body, "$.totals.outputTokens")).isEqualTo(350);
        assertThat(JsonPath.<Double>read(body, "$.totals.costUsd")).isEqualTo(0.007135);
        assertThat(JsonPath.<List<String>>read(body, "$.models[*].model")).containsExactly(HAIKU, SONNET);
        assertThat(JsonPath.<List<Integer>>read(body, "$.models[*].runs")).containsExactly(1, 1);
        assertThat(JsonPath.<List<String>>read(body, "$.runs[*].step")).containsExactly("heuristic", "haiku", "sonnet");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.runs[*].applied")).containsExactly(false, false, true);
        assertThat(JsonPath.<Integer>read(body, "$.runs[0].inputTokens")).isZero();

        assertThat(mvc.get().uri("/v1/recipes/{id}/extraction-stats", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(404);
    }
}
