package com.gatar.recipedealer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Capture fixtures based on the Android contract example. */
public final class TestCaptures {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEMPLATE_ID = "3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11";

    private TestCaptures() {
    }

    /** The web capture example (kwestiasmaku szarlotka) with the given id. */
    public static ObjectNode webCapture(UUID captureId) {
        try (InputStream in = TestCaptures.class.getResourceAsStream("/captures/web-capture.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(TEMPLATE_ID, captureId.toString());
            return (ObjectNode) JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode webCapture(UUID captureId, Consumer<ObjectNode> customizer) {
        ObjectNode node = webCapture(captureId);
        customizer.accept(node);
        return node;
    }

    public static ObjectNode attachment(String name, String role, String mimeType) {
        return JSON.createObjectNode().put("name", name).put("role", role).put("mimeType", mimeType);
    }

    public static byte[] bytes(ObjectNode capture) {
        return JSON.writeValueAsBytes(capture);
    }

    public static MockPart capturePart(byte[] json) {
        var part = new MockPart("capture", null, json, MediaType.APPLICATION_JSON);
        return part;
    }

    public static MockPart filePart(String name, String mimeType, byte[] content) {
        return new MockPart(name, name, content, MediaType.parseMediaType(mimeType));
    }

    public static MvcTestResult put(MockMvcTester mvc, String token, UUID captureId, MockPart... parts) {
        return mvc.put().uri("/v1/captures/{id}", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .multipart()
                .part(parts)
                .exchange();
    }
}
