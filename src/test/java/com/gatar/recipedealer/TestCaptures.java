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

    /** A web capture of its own page (unique URL and title), so tests do not share identity keys. */
    public static ObjectNode webCapture(UUID captureId, String url, String title) {
        return webCapture(captureId, c -> {
            c.withObject("source").put("url", url);
            c.withObject("raw").withObject("web").put("canonicalUrl", url);
            c.withObject("recipe").put("title", title);
        });
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

    /** Uploads a capture (no attachments) and expects 201. */
    public static UUID upload(MockMvcTester mvc, String token, ObjectNode capture) {
        UUID id = UUID.fromString(capture.get("captureId").asString());
        var result = put(mvc, token, id, capturePart(bytes(capture)));
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("upload failed: " + result.getResponse().getStatus() + " " + TestAuth.body(result));
        }
        return id;
    }

    /** Uploads, processes and resolves a capture as a NEW recipe; returns the recipe id. */
    public static UUID newRecipe(MockMvcTester mvc, String token, com.gatar.recipedealer.processing.JobWorker worker,
                                 ObjectNode capture) {
        UUID captureId = upload(mvc, token, capture);
        worker.drain();
        var result = resolve(mvc, token, captureId, "{\"action\":\"NEW\"}");
        String body = TestAuth.body(result);
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("resolution failed: " + body);
        }
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.recipes[0].recipeId"));
    }

    public static MvcTestResult resolve(MockMvcTester mvc, String token, UUID captureId, String body) {
        return mvc.put().uri("/v1/captures/{id}/extracted/0/resolution", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    public static MvcTestResult status(MockMvcTester mvc, String token, UUID captureId) {
        return mvc.get().uri("/v1/captures/{id}", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .exchange();
    }

    public static MvcTestResult put(MockMvcTester mvc, String token, UUID captureId, MockPart... parts) {
        return mvc.put().uri("/v1/captures/{id}", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .multipart()
                .part(parts)
                .exchange();
    }
}
