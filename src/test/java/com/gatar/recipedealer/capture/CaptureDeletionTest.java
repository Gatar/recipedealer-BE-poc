package com.gatar.recipedealer.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.processing.JobWorker;

import tools.jackson.databind.node.ObjectNode;

/** {@code DELETE /v1/captures/{captureId}}: a capture that never became a recipe goes away with its blobs. */
@IntegrationTest
class CaptureDeletionTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    BlobStore blobs;

    String token;
    String suffix;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Deleter", "phone");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
    }

    /** An invoice photo: no ingredients, no steps, so it waits for extraction (no LLM key in the shared context). */
    private UUID uploadInvoice(byte[] jpeg) {
        UUID captureId = UUID.randomUUID();
        ObjectNode capture = TestCaptures.webCapture(captureId, "https://www.faktura-" + suffix + ".pl/" + captureId,
                "Faktura " + suffix);
        capture.withObject("recipe").putArray("ingredientGroups");
        capture.withObject("recipe").putArray("stepGroups");
        capture.withArray("attachments").add(TestCaptures.attachment("photo.jpg", "image", "image/jpeg"));
        assertThat(TestCaptures.put(mvc, token, captureId, TestCaptures.capturePart(TestCaptures.bytes(capture)),
                TestCaptures.filePart("photo.jpg", "image/jpeg", jpeg))).hasStatus(201);
        return captureId;
    }

    private static byte[] jpeg(int width) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, 40, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private MvcTestResult delete(UUID captureId) {
        return mvc.delete().uri("/v1/captures/{id}", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private List<String> blobsOf(UUID captureId) {
        return jdbc.sql("""
                        SELECT body_sha256 FROM capture WHERE id = :id
                        UNION SELECT blob_sha256 FROM capture_attachment WHERE capture_id = :id""")
                .param("id", captureId).query(String.class).list();
    }

    private long rows(String table, UUID captureId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE capture_id = :id").param("id", captureId)
                .query(Long.class).single();
    }

    @Test
    void deletesAnUnappliedCaptureWithItsBlobs() throws IOException {
        UUID captureId = uploadInvoice(jpeg(41));
        // a decision stored before the extraction is deleted too
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();
        assertThat(TestAuth.body(TestCaptures.status(mvc, token, captureId))).contains("AWAITING_EXTRACTION");
        List<String> stored = blobsOf(captureId);
        assertThat(stored).hasSize(2).allSatisfy(sha256 -> assertThat(blobs.exists(sha256)).isTrue());

        assertThat(delete(captureId)).hasStatus(204);

        assertThat(TestCaptures.status(mvc, token, captureId)).hasStatus(404);
        assertThat(mvc.get().uri("/v1/captures").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk().bodyText().doesNotContain(captureId.toString());
        for (String table : List.of("capture_state", "capture_attachment", "capture_resolution", "processing_run", "job")) {
            assertThat(rows(table, captureId)).as(table).isZero();
        }
        assertThat(stored).allSatisfy(sha256 -> {
            assertThat(blobs.exists(sha256)).isFalse();
            assertThat(jdbc.sql("SELECT count(*) FROM blob WHERE sha256 = :s").param("s", sha256).query(Long.class)
                    .single()).isZero();
        });
        assertThat(delete(captureId)).hasStatus(404).bodyJson().extractingPath("$.error").isEqualTo("not_found");
    }

    @Test
    void keepsABlobAnotherCaptureUses() throws IOException {
        byte[] photo = jpeg(43);
        UUID first = uploadInvoice(photo);
        UUID second = uploadInvoice(photo);
        worker.drain();
        String shared = jdbc.sql("SELECT blob_sha256 FROM capture_attachment WHERE capture_id = :id")
                .param("id", first).query(String.class).single();

        assertThat(delete(first)).hasStatus(204);

        assertThat(blobs.exists(shared)).isTrue();
        assertThat(mvc.get().uri("/v1/blobs/{sha256}", shared).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk();
        assertThat(TestCaptures.status(mvc, token, second)).hasStatusOk();
    }

    @Test
    void refusesWhileProcessing() throws IOException {
        UUID captureId = uploadInvoice(jpeg(45));

        assertThat(delete(captureId)).hasStatus(409).bodyJson().extractingPath("$.error")
                .isEqualTo("extraction_in_progress");
        worker.drain();
        assertThat(delete(captureId)).hasStatus(204);
    }

    @Test
    void refusesACaptureThatBecameARecipe() {
        UUID captureId = UUID.randomUUID();
        TestCaptures.newRecipe(mvc, token, worker, TestCaptures.webCapture(captureId,
                "https://www.przepis-" + suffix + ".pl/", "Szarlotka " + suffix));

        assertThat(delete(captureId)).hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("capture_applied");
        assertThat(TestCaptures.status(mvc, token, captureId)).hasStatusOk();
    }

    @Test
    void capturesStayImmutableOutsideTheDeletion() throws IOException {
        UUID captureId = uploadInvoice(jpeg(47));
        worker.drain();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM capture_attachment WHERE capture_id = :id")
                .param("id", captureId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture SET source_url = 'x' WHERE id = :id")
                .param("id", captureId).update()).hasMessageContaining("immutable");
    }

    @Test
    void unknownCapture() {
        assertThat(delete(UUID.randomUUID())).hasStatus(404);
    }
}
