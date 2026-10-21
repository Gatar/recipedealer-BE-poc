package com.gatar.recipedealer.blob;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.common.Hashing;

@IntegrationTest
class BlobControllerTest {

    @Autowired
    MockMvcTester mvc;

    String token;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Blobs", "phone");
    }

    @Test
    void servesAttachmentWithImmutableCacheHeaders() {
        UUID id = UUID.randomUUID();
        byte[] image = ("jpeg " + id).getBytes();
        var capture = TestCaptures.webCapture(id, c -> c.withArray("attachments")
                .add(TestCaptures.attachment("photo.jpg", "image", "image/jpeg")));
        assertThat(TestCaptures.put(mvc, token, id, TestCaptures.capturePart(TestCaptures.bytes(capture)),
                TestCaptures.filePart("photo.jpg", "image/jpeg", image))).hasStatus(201);
        String sha = Hashing.sha256Hex(image);

        var result = mvc.get().uri("/v1/blobs/{sha}", sha).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(result).hasStatusOk()
                .hasContentType("image/jpeg")
                .hasHeader(HttpHeaders.ETAG, "\"" + sha + "\"")
                .hasHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(image.length));
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=31536000").contains("immutable")
                .doesNotContain("no-store");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(image);

        assertThat(mvc.get().uri("/v1/blobs/{sha}", sha).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .header(HttpHeaders.IF_NONE_MATCH, "\"" + sha + "\"")).hasStatus(304);
    }

    @Test
    void servesOriginalCaptureJson() {
        UUID id = UUID.randomUUID();
        byte[] body = TestCaptures.bytes(TestCaptures.webCapture(id));
        TestCaptures.put(mvc, token, id, TestCaptures.capturePart(body));

        assertThat(mvc.get().uri("/v1/blobs/{sha}", Hashing.sha256Hex(body)).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk().hasContentType("application/json");
    }

    @Test
    void unknownOrInvalidBlobIs404AndAuthIsRequired() {
        assertThat(mvc.get().uri("/v1/blobs/{sha}", "a".repeat(64)).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(404);
        assertThat(mvc.get().uri("/v1/blobs/{sha}", "not-a-hash").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(404);
        assertThat(mvc.get().uri("/v1/blobs/{sha}", "a".repeat(64))).hasStatus(401);
    }
}
