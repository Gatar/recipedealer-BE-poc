package com.gatar.recipedealer.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;

import tools.jackson.databind.node.ObjectNode;

/** Multipart limits are enforced by the servlet container, so these tests go through real HTTP. */
@IntegrationTest
class UploadLimitsTest {

    private static final int MB = 1024 * 1024;

    @LocalServerPort
    int port;

    @Autowired
    MockMvcTester mvc;

    String token;

    @BeforeEach
    void enroll() {
        token = TestAuth.enroll(mvc, "Limits", "phone");
    }

    private HttpResponse<String> upload(UUID id, Map<String, Integer> fileSizes) throws IOException, InterruptedException {
        ObjectNode capture = TestCaptures.webCapture(id, c -> fileSizes.keySet()
                .forEach(name -> c.withArray("attachments").add(TestCaptures.attachment(name, "image", "image/jpeg"))));
        String boundary = "----recipedealer-test";
        var body = new ByteArrayOutputStream();
        writePart(body, boundary, "capture", null, "application/json", TestCaptures.bytes(capture));
        for (var file : fileSizes.entrySet()) {
            writePart(body, boundary, file.getKey(), file.getKey(), "image/jpeg", new byte[file.getValue()]);
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/captures/" + id))
                .header("Authorization", TestAuth.bearer(token))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String fileName,
                                  String contentType, byte[] content) throws IOException {
        String disposition = "form-data; name=\"" + name + "\"" + (fileName == null ? "" : "; filename=\"" + fileName + "\"");
        out.write(("--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(content);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void acceptsFilesWithinLimits() throws Exception {
        var response = upload(UUID.randomUUID(), Map.of("image-1.jpg", 24 * MB));
        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsFileOver25Mb() throws Exception {
        var response = upload(UUID.randomUUID(), Map.of("image-1.jpg", 26 * MB));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("payload_too_large");
    }

    @Test
    void rejectsRequestOver50Mb() throws Exception {
        var files = new LinkedHashMap<String, Integer>();
        files.put("image-1.jpg", 20 * MB);
        files.put("image-2.jpg", 20 * MB);
        files.put("image-3.jpg", 20 * MB);
        var response = upload(UUID.randomUUID(), files);
        assertThat(response.statusCode()).isEqualTo(413);
    }
}
