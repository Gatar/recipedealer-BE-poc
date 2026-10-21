package com.gatar.recipedealer.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class LockAndEditTest {

    private static final String EDITED = """
            {"title": "Szarlotka babci Zosi", "language": "pl",
             "ingredientGroups": [{"name": null, "items": ["1 kg jabłek", "300 g mąki"]}],
             "stepGroups": [{"name": null, "items": ["Upiec."]}]}""";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    String ania;
    String tablet;
    String suffix;
    UUID recipeId;

    @BeforeEach
    void setUp() {
        ania = TestAuth.enroll(mvc, "Ania", "Pixel");
        tablet = TestAuth.enroll(mvc, "Tablet", "kitchen");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
        recipeId = TestCaptures.newRecipe(mvc, ania, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://edit-" + suffix + ".pl/szarlotka", "Szarlotka " + suffix));
    }

    private MvcTestResult acquire(String token) {
        return mvc.post().uri("/v1/recipes/{id}/lock", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private String lockId(MvcTestResult result) {
        assertThat(result).hasStatus(201);
        return JsonPath.read(TestAuth.body(result), "$.lockId");
    }

    private MvcTestResult heartbeat(String token, String lockId) {
        return mvc.put().uri("/v1/recipes/{id}/lock/{lockId}", recipeId, lockId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private String head() {
        return jdbc.sql("SELECT head_revision_id::text FROM recipe WHERE id = :id").param("id", recipeId).query(String.class).single();
    }

    private MvcTestResult save(String token, String lockId, String ifMatch, String body) {
        var request = mvc.post().uri("/v1/recipes/{id}/revisions", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (lockId != null) {
            request = request.header("Lock-Id", lockId);
        }
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private void expireLock() {
        jdbc.sql("UPDATE recipe_lock SET expires_at = now() - interval '1 second' WHERE recipe_id = :id").param("id", recipeId).update();
    }

    @Test
    void lockIsExclusiveAndShowsWhoHoldsIt() {
        var first = acquire(ania);
        lockId(first);
        Instant expiresAt = Instant.parse(JsonPath.read(TestAuth.body(first), "$.expiresAt"));
        Instant dbNow = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(Duration.between(dbNow, expiresAt)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5).plusSeconds(5));

        var conflict = acquire(tablet);
        assertThat(conflict).hasStatus(409);
        assertThat(JsonPath.<String>read(TestAuth.body(conflict), "$.lockedBy")).isEqualTo("Ania (Pixel)");
        assertThat(JsonPath.<String>read(TestAuth.body(conflict), "$.expiresAt")).isNotBlank();
        // The lock belongs to the editing session, not to the user.
        assertThat(acquire(ania)).hasStatus(409);

        var recipe = mvc.get().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tablet)).exchange();
        assertThat(JsonPath.<String>read(TestAuth.body(recipe), "$.lock.lockedBy")).isEqualTo("Ania (Pixel)");
    }

    @Test
    void expiredLockCanBeTakenOverAndOldSessionLosesIt() {
        String old = lockId(acquire(ania));
        expireLock();

        String taken = lockId(acquire(tablet));

        assertThat(taken).isNotEqualTo(old);
        assertThat(heartbeat(ania, old)).hasStatus(409).bodyJson().extractingPath("$.lockedBy").isEqualTo("Tablet (kitchen)");
    }

    @Test
    void heartbeatExtendsTheLease() {
        String lockId = lockId(acquire(ania));
        jdbc.sql("UPDATE recipe_lock SET expires_at = now() + interval '30 seconds' WHERE recipe_id = :id").param("id", recipeId).update();

        assertThat(heartbeat(ania, lockId)).hasStatusOk();

        assertThat(jdbc.sql("SELECT expires_at > now() + interval '4 minutes' FROM recipe_lock WHERE recipe_id = :id")
                .param("id", recipeId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void heartbeatAfterExpiryFails() {
        String lockId = lockId(acquire(ania));
        expireLock();

        assertThat(heartbeat(ania, lockId)).hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("lock_expired");
    }

    @Test
    void releaseLetsOthersEdit() {
        String lockId = lockId(acquire(ania));

        assertThat(mvc.delete().uri("/v1/recipes/{id}/lock/{lockId}", recipeId, lockId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania))).hasStatus(204);

        lockId(acquire(tablet));
    }

    @Test
    void saveCreatesUserHeadAndReleasesTheLock() {
        String oldHead = head();
        String lockId = lockId(acquire(ania));

        var result = save(ania, lockId, "\"" + oldHead + "\"", EDITED);

        assertThat(result).hasStatus(201);
        String newHead = head();
        assertThat(newHead).isNotEqualTo(oldHead);
        assertThat(result).hasHeader(HttpHeaders.ETAG, "\"" + newHead + "\"");
        String body = TestAuth.body(result);
        assertThat(JsonPath.<String>read(body, "$.origin")).isEqualTo("USER");
        assertThat(JsonPath.<Boolean>read(body, "$.needsReview")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.recipe.title")).isEqualTo("Szarlotka babci Zosi");
        assertThat(jdbc.sql("SELECT parent_id::text FROM recipe_revision WHERE id = CAST(:id AS uuid)").param("id", newHead)
                .query(String.class).single()).isEqualTo(oldHead);
        assertThat(jdbc.sql("SELECT count(*) FROM recipe_lock WHERE recipe_id = :id").param("id", recipeId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT title FROM recipe WHERE id = :id").param("id", recipeId).query(String.class).single())
                .isEqualTo("Szarlotka babci Zosi");
    }

    @Test
    void saveWithStaleLockIsRejected() {
        String lockId = lockId(acquire(ania));
        expireLock();

        assertThat(save(ania, lockId, "\"" + head() + "\"", EDITED)).hasStatus(409);
        assertThat(save(ania, null, "\"" + head() + "\"", EDITED)).hasStatus(409)
                .bodyJson().extractingPath("$.error").isEqualTo("lock_required");
    }

    @Test
    void saveWithAnotherSessionsLockIsRejected() {
        lockId(acquire(tablet));

        assertThat(save(ania, UUID.randomUUID().toString(), "\"" + head() + "\"", EDITED)).hasStatus(409)
                .bodyJson().extractingPath("$.lockedBy").isEqualTo("Tablet (kitchen)");
    }

    @Test
    void saveWithStaleHeadIsRejectedAndKeepsTheLock() {
        String staleHead = head();
        String lockId = lockId(acquire(ania));
        UUID captureId = TestCaptures.upload(mvc, tablet,
                TestCaptures.webCapture(UUID.randomUUID(), "https://edit-" + suffix + ".pl/szarlotka", "Szarlotka nowa " + suffix));
        worker.drain();
        TestCaptures.resolve(mvc, tablet, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}");
        assertThat(head()).isNotEqualTo(staleHead);

        assertThat(save(ania, lockId, "\"" + staleHead + "\"", EDITED)).hasStatus(412)
                .bodyJson().extractingPath("$.error").isEqualTo("head_changed");
        assertThat(heartbeat(ania, lockId)).hasStatusOk();
        assertThat(save(ania, lockId, null, EDITED)).hasStatus(428);
        assertThat(save(ania, lockId, "*", EDITED)).hasStatus(412);
    }

    @Test
    void saveWithUnchangedContentCreatesNoRevision() {
        String head = head();
        String content = jdbc.sql("SELECT content::text FROM recipe_revision WHERE id = CAST(:id AS uuid)").param("id", head)
                .query(String.class).single();
        String lockId = lockId(acquire(ania));

        assertThat(save(ania, lockId, "\"" + head + "\"", content)).hasStatus(200);
        assertThat(head()).isEqualTo(head);
        assertThat(jdbc.sql("SELECT count(*) FROM recipe_revision WHERE recipe_id = :id").param("id", recipeId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invalidRecipeIsRejected() {
        String lockId = lockId(acquire(ania));

        assertThat(save(ania, lockId, "\"" + head() + "\"", "{\"title\": 42, \"ingredientGroups\": \"none\"}"))
                .hasStatus(400).bodyJson().extractingPath("$.error").isEqualTo("invalid_recipe");
        assertThat(save(ania, lockId, "\"" + head() + "\"", "[]")).hasStatus(400);
    }

    @Test
    void deleteNeedsLockAndProducesTombstone() {
        assertThat(mvc.delete().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania)))
                .hasStatus(409);
        String lockId = lockId(acquire(ania));

        assertThat(mvc.delete().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania))
                .header("Lock-Id", lockId)).hasStatus(204);

        assertThat(mvc.get().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania)))
                .hasStatus(410);
        var candidates = mvc.get().uri("/v1/recipes/candidates").param("url", "https://edit-" + suffix + ".pl/szarlotka")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania)).exchange();
        assertThat(JsonPath.<java.util.List<?>>read(TestAuth.body(candidates), "$")).isEmpty();
        assertThat(acquire(ania)).hasStatus(410);
        assertThat(jdbc.sql("SELECT count(*) FROM recipe_key WHERE recipe_id = :id").param("id", recipeId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void lockOnUnknownRecipeIs404() {
        assertThat(mvc.post().uri("/v1/recipes/{id}/lock", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(ania))).hasStatus(404);
    }
}
