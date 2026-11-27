package com.gatar.recipedealer.purge;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

/** Permanent deletion (user, 2026-11-27): recipes, accounts and households keep nothing. */
@IntegrationTest
class PurgeTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    PurgeService purge;

    @Autowired
    BlobStore blobs;

    String sfx;
    String token;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        token = TestAuth.enroll(mvc, "Purge-" + sfx, "phone");
        worker.drain();
    }

    private long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private UUID[] recipeWithCapture(String token) {
        UUID captureId = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(),
                "https://purge-" + sfx + "-" + UUID.randomUUID() + ".pl/sernik", "Sernik " + sfx));
        worker.drain();
        String body = TestAuth.body(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}"));
        return new UUID[] {UUID.fromString(JsonPath.read(body, "$.recipes[0].recipeId")), captureId};
    }

    private void deleteRecipe(String token, UUID recipeId) {
        String lock = JsonPath.read(TestAuth.body(mvc.post().uri("/v1/recipes/{id}/lock", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange()), "$.lockId");
        assertThat(mvc.delete().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .header("Lock-Id", lock)).hasStatus(204);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
        g.fillRect(0, 0, 300, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aDeletedRecipeIsPurgedDownToItsSyncTombstone() {
        UUID[] ids = recipeWithCapture(token);
        UUID recipeId = ids[0];
        UUID captureId = ids[1];
        String body = jdbc.sql("SELECT body_sha256 FROM capture WHERE id = :id").param("id", captureId)
                .query(String.class).single();
        deleteRecipe(token, recipeId);

        assertThat(purge.purgeRecipe(recipeId)).isTrue();

        Map<String, Object> row = jdbc.sql("""
                        SELECT head_revision_id, title, thumbnail_blob, deleted_at IS NOT NULL AS deleted,
                               purged_at IS NOT NULL AS purged
                        FROM recipe WHERE id = :id""").param("id", recipeId).query().singleRow();
        assertThat(row).containsEntry("head_revision_id", null).containsEntry("title", null)
                .containsEntry("deleted", true).containsEntry("purged", true);
        assertThat(count("SELECT count(*) FROM recipe_revision WHERE recipe_id = :id", recipeId)).isZero();
        assertThat(count("SELECT count(*) FROM capture WHERE id = :id", captureId)).isZero();
        assertThat(count("SELECT count(*) FROM processing_run WHERE capture_id = :id", captureId)).isZero();
        assertThat(count("SELECT count(*) FROM blob WHERE sha256 = :id", body)).isZero();
        assertThat(blobs.exists(body)).isFalse();

        // other devices still learn about the delete
        var sync = TestAuth.body(mvc.get().uri("/v1/recipes").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        assertThat(JsonPath.<List<Boolean>>read(sync, "$.items[?(@.recipeId == '" + recipeId + "')].deleted")).containsExactly(true);
        assertThat(mvc.get().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(410);
        assertThat(purge.purgeRecipe(recipeId)).isFalse();
    }

    @Test
    void aLiveRecipeIsNotPurged() {
        UUID recipeId = recipeWithCapture(token)[0];

        assertThat(purge.purgeRecipe(recipeId)).isFalse();
        assertThat(count("SELECT count(*) FROM recipe_revision WHERE recipe_id = :id", recipeId)).isPositive();
    }

    @Test
    void thePurgeWaitsForARunningJob() {
        UUID[] ids = recipeWithCapture(token);
        deleteRecipe(token, ids[0]);
        jdbc.sql("UPDATE job SET status = 'running' WHERE id = (SELECT id FROM job WHERE capture_id = :id LIMIT 1)")
                .param("id", ids[1]).update();

        assertThat(purge.purgeRecipe(ids[0])).isFalse();
        assertThat(count("SELECT count(*) FROM capture WHERE id = :id", ids[1])).isOne();

        jdbc.sql("UPDATE job SET status = 'done' WHERE capture_id = :id").param("id", ids[1]).update();
        assertThat(purge.purgeRecipe(ids[0])).isTrue();
    }

    @Test
    void aDeletedAccountKeepsNoPersonalData() {
        String me = TestAuth.body(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        UUID userId = UUID.fromString(JsonPath.read(me, "$.userId"));
        UUID recipeId = recipeWithCapture(token)[0];

        assertThat(mvc.delete().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(204);

        assertThat(jdbc.sql("SELECT name, email, password_hash FROM app_user WHERE id = :id").param("id", userId)
                .query().singleRow()).containsEntry("name", null).containsEntry("email", null)
                .containsEntry("password_hash", null);
        assertThat(jdbc.sql("SELECT device_name FROM device_token WHERE user_id = :id").param("id", userId)
                .query(String.class).list()).containsOnlyNulls();
        assertThat(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(401);
        // the recipe stays in the household, without an author name
        assertThat(count("SELECT count(*) FROM recipe WHERE id = :id AND deleted_at IS NULL", recipeId)).isOne();
        // the name is free again
        assertThat(TestAuth.enrollRequest(mvc, "Purge-" + sfx, "phone")).hasStatus(201);
    }

    @Test
    void aDeletedHouseholdIsPurgedCompletely() throws IOException {
        var created = mvc.post().uri("/v1/auth/households").contentType(MediaType.APPLICATION_JSON).content("""
                {"householdName": "Dom %s", "userName": "Gone-%s", "password": "hasło", "deviceName": "Pixel",
                 "email": "gone-%s@example.org"}""".formatted(sfx, sfx, sfx)).exchange();
        assertThat(created).hasStatus(201);
        String owner = JsonPath.read(TestAuth.body(created), "$.token");
        UUID householdId = UUID.fromString(JsonPath.read(TestAuth.body(created), "$.householdId"));
        UUID[] ids = recipeWithCapture(owner);
        MvcTestResult photo = mvc.post().uri("/v1/blobs").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(owner))
                .multipart().part(new MockPart("image", "photo.png", png(), MediaType.IMAGE_PNG)).exchange();
        String photoBlob = JsonPath.read(TestAuth.body(photo), "$.blob");

        assertThat(mvc.delete().uri("/v1/household").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(owner))).hasStatus(204);
        assertThat(purge.purgeHousehold(householdId)).isTrue();

        assertThat(count("SELECT count(*) FROM household WHERE id = :id", householdId)).isZero();
        assertThat(count("SELECT count(*) FROM app_user WHERE household_id = :id", householdId)).isZero();
        assertThat(count("SELECT count(*) FROM recipe WHERE id = :id", ids[0])).isZero();
        assertThat(count("SELECT count(*) FROM capture WHERE id = :id", ids[1])).isZero();
        assertThat(count("SELECT count(*) FROM user_photo WHERE household_id = :id", householdId)).isZero();
        assertThat(count("SELECT count(*) FROM tag_category WHERE household_id = :id", householdId)).isZero();
        assertThat(blobs.exists(photoBlob)).isFalse();
    }
}
