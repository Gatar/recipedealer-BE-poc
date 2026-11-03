package com.gatar.recipedealer.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Recipe photos uploaded from the phone: downscaled on the server, shown through {@code images[0]}. */
@IntegrationTest
class PhotoTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    String token;
    UUID recipeId;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Photo", "phone");
        worker.drain();
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        recipeId = TestCaptures.newRecipe(mvc, token, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://photo-" + sfx + ".pl/szarlotka", "Szarlotka " + sfx));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MvcTestResult upload(byte[] content) {
        return mvc.post().uri("/v1/blobs").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .multipart().part(new MockPart("image", "photo.png", content, MediaType.IMAGE_PNG)).exchange();
    }

    private MvcTestResult saveWithImage(String image) {
        String lock = JsonPath.read(TestAuth.body(mvc.post().uri("/v1/recipes/{id}/lock", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange()), "$.lockId");
        var view = TestAuth.body(mvc.get().uri("/v1/recipes/{id}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        ObjectNode recipe = (ObjectNode) JSON.readTree(view).get("recipe");
        recipe.putArray("images").add(image);
        return mvc.post().uri("/v1/recipes/{id}/revisions", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .header("Lock-Id", lock)
                .header(HttpHeaders.IF_MATCH, "\"" + JsonPath.read(view, "$.headRevisionId") + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(recipe.toString())
                .exchange();
    }

    @Test
    void aLargePhotoIsStoredAsASmallJpegAndBecomesTheThumbnail() throws IOException {
        byte[] original = png(4000, 3000);
        MvcTestResult result = upload(original);

        assertThat(result).hasStatus(201);
        String body = TestAuth.body(result);
        String blob = JsonPath.read(body, "$.blob");
        assertThat(JsonPath.<String>read(body, "$.ref")).isEqualTo("blob:" + blob);
        assertThat(JsonPath.<Integer>read(body, "$.width")).isEqualTo(1600);
        assertThat(JsonPath.<Integer>read(body, "$.height")).isEqualTo(1200);

        var stored = mvc.get().uri("/v1/blobs/{sha}", blob).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        assertThat(stored).hasStatusOk().hasContentType(MediaType.IMAGE_JPEG);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored.getResponse().getContentAsByteArray()));
        assertThat(image.getWidth()).isEqualTo(1600);

        assertThat(saveWithImage("blob:" + blob)).hasStatus(201);

        var recipe = TestAuth.body(mvc.get().uri("/v1/recipes/{id}", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange());
        assertThat(JsonPath.<String>read(recipe, "$.thumbnailBlob")).isEqualTo(blob);
        assertThat(JsonPath.<String>read(recipe, "$.recipe.images[0]")).isEqualTo("blob:" + blob);
    }

    @Test
    void aSmallPhotoKeepsItsSize() throws IOException {
        MvcTestResult result = upload(png(800, 600));

        assertThat(result).hasStatus(201);
        assertThat(JsonPath.<Integer>read(TestAuth.body(result), "$.width")).isEqualTo(800);
    }

    @Test
    void notAnImageIsRejected() {
        assertThat(upload("not an image".getBytes())).hasStatus(400).bodyJson()
                .extractingPath("$.error").isEqualTo("invalid_image");
    }

    @Test
    void aRecipeCannotReferenceAPhotoThatWasNotUploaded() {
        assertThat(saveWithImage("blob:" + "a".repeat(64))).hasStatus(400).bodyJson()
                .extractingPath("$.error").isEqualTo("unknown_photo");
    }
}
