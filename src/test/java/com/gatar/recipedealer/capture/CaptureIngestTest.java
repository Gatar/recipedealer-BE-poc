package com.gatar.recipedealer.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Hashing;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class CaptureIngestTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BlobStore blobs;

    String token;

    @BeforeEach
    void enroll() {
        token = TestAuth.enroll(mvc, "Ingest", "phone");
    }

    private String storedBodySha(UUID id) {
        return jdbc.sql("SELECT body_sha256 FROM capture WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void acceptsWebCaptureAndStoresOriginalBytes() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body)))
                .hasStatus(201)
                .bodyJson().extractingPath("$.status").isEqualTo("accepted");

        assertThat(storedBodySha(id)).isEqualTo(Hashing.sha256Hex(body));
        assertThat(blobs.exists(Hashing.sha256Hex(body))).isTrue();
        var row = jdbc.sql("SELECT source_type, source_url, body->'recipe'->>'title' AS title FROM capture WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("source_type", "WEB")
                .containsEntry("source_url", "https://www.kwestiasmaku.com/przepis/szarlotka")
                .containsEntry("title", "Szarlotka");
    }

    @Test
    void storesAttachmentsAsBlobsWithMetadata() {
        UUID id = UUID.randomUUID();
        byte[] image = "fake jpeg bytes".getBytes(StandardCharsets.UTF_8);
        ObjectNode capture = TestCaptures.webCapture(id, c -> c.withArray("attachments")
                .add(TestCaptures.attachment("image-1.jpg", "image", "image/jpeg").put("width", 1600).put("height", 1200)));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(TestCaptures.bytes(capture)),
                TestCaptures.filePart("image-1.jpg", "image/jpeg", image))).hasStatus(201);

        var row = jdbc.sql("SELECT role, media_type, original_file_name, blob_sha256, size_bytes, width FROM capture_attachment WHERE capture_id = :id")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("role", "image").containsEntry("media_type", "image/jpeg")
                .containsEntry("original_file_name", "image-1.jpg").containsEntry("size_bytes", (long) image.length)
                .containsEntry("width", 1600);
        assertThat(blobs.exists((String) row.get("blob_sha256"))).isTrue();
    }

    @Test
    void sameCaptureTwiceIsIdempotent() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body))).hasStatus(201);
        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body))).hasStatus(200)
                .bodyJson().extractingPath("$.status").isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT count(*) FROM capture WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void differentBodyForExistingCaptureKeepsTheStoredOne() {
        UUID id = UUID.randomUUID();
        byte[] first = TestCaptures.bytes(TestCaptures.webCapture(id));
        byte[] second = TestCaptures.bytes(TestCaptures.webCapture(id, c -> c.withObject("recipe").put("title", "Inna szarlotka")));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(first))).hasStatus(201);
        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(second))).hasStatus(200);

        assertThat(storedBodySha(id)).isEqualTo(Hashing.sha256Hex(first));
    }

    @Test
    void schemaViolationIsRejectedWithDetails() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id, c -> {
            c.remove("raw");
            c.withObject("source").put("type", "FAX");
        }));

        var result = TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body));
        assertThat(result).hasStatus(400).bodyJson().extractingPath("$.error").isEqualTo("invalid_capture");
        List<String> details = JsonPath.read(TestAuth.body(result), "$.details");
        assertThat(details).anyMatch(d -> d.contains("raw")).anyMatch(d -> d.contains("source.type") || d.contains("/source/type"));
        assertThat(jdbc.sql("SELECT count(*) FROM capture WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void formatsAreAsserted() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id, c -> c.put("capturedAt", "yesterday")));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body))).hasStatus(400);
    }

    @Test
    void malformedJsonIsRejected() {
        UUID id = UUID.randomUUID();
        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart("{not json".getBytes(StandardCharsets.UTF_8))))
                .hasStatus(400).bodyJson().extractingPath("$.error").isEqualTo("invalid_capture");
    }

    @Test
    void captureIdMustMatchUrl() {
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(UUID.randomUUID()));
        assertThat(TestCaptures.put(mvc, token, UUID.randomUUID(), TestCaptures.capturePart(body))).hasStatus(400);
    }

    @Test
    void declaredAttachmentWithoutPartIsRejected() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id, c -> c.withArray("attachments")
                .add(TestCaptures.attachment("document-1.pdf", "pdf", "application/pdf"))));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body))).hasStatus(400);
    }

    @Test
    void undeclaredPartIsRejected() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id));

        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body),
                TestCaptures.filePart("extra.jpg", "image/jpeg", new byte[] {1, 2, 3}))).hasStatus(400);
    }

    @Test
    void missingCapturePartIsRejected() {
        assertThat(TestCaptures.put(mvc, token, UUID.randomUUID(),
                TestCaptures.filePart("image-1.jpg", "image/jpeg", new byte[] {1}))).hasStatus(400);
    }

    @Test
    void requiresBearerToken() {
        UUID id = UUID.randomUUID();
        assertThat(mvc.put().uri("/v1/captures/{id}", id).multipart()
                .part(TestCaptures.capturePart(TestCaptures.bytes(TestCaptures.webCapture(id)))))
                .hasStatus(401);
    }

    @Test
    void statusOfUnknownCaptureIs404() {
        assertThat(mvc.get().uri("/v1/captures/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(404);
    }

    @Test
    void capturesAreImmutableInTheDatabase() {
        UUID id = UUID.randomUUID();
        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(TestCaptures.bytes(TestCaptures.webCapture(id)))))
                .hasStatus(201);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("UPDATE capture SET source_url = 'x' WHERE id = :id").param("id", id).update())
                .hasMessageContaining("immutable");
    }
}
