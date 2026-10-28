package com.gatar.recipedealer.pdfimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobWorker;

import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class PdfImageTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    PdfImageService images;

    String token;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Pdf", "phone");
        worker.drain();
        images.drain();
    }

    @Test
    void photoFoundInAPdfBecomesTheThumbnail() throws IOException {
        UUID captureId = UUID.randomUUID();
        UUID recipeId = newRecipe(captureId, pdfWithPhoto());
        assertThat(thumbnail(recipeId)).isNull();
        String updatedAt = updatedAt(recipeId);

        assertThat(images.drain()).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("SELECT blob_sha256, page, width, height, error FROM pdf_image WHERE capture_id = :id")
                .param("id", captureId).query().singleRow();
        assertThat(row).containsEntry("page", 1).containsEntry("width", 640).containsEntry("height", 480)
                .containsEntry("error", null);
        assertThat(thumbnail(recipeId)).isEqualTo(row.get("blob_sha256"));
        assertThat(updatedAt(recipeId)).isGreaterThan(updatedAt);
        assertThat(mvc.get().uri("/v1/blobs/{sha256}", row.get("blob_sha256"))
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange())
                .hasStatusOk().hasContentType("image/jpeg");
        assertThat(images.drain()).isZero();
    }

    @Test
    void photoFoundBeforeTheRecipeExistsIsItsThumbnail() throws IOException {
        UUID captureId = UUID.randomUUID();
        TestCaptures.put(mvc, token, captureId, TestCaptures.capturePart(TestCaptures.bytes(capture(captureId))),
                TestCaptures.filePart("document-1.pdf", "application/pdf", pdfWithPhoto()));
        assertThat(images.drain()).isEqualTo(1);

        worker.drain();
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();

        String photo = jdbc.sql("SELECT blob_sha256 FROM pdf_image WHERE capture_id = :id").param("id", captureId)
                .query(String.class).single();
        assertThat(thumbnail(recipeOf(captureId))).isEqualTo(photo);
    }

    @Test
    void pdfWithoutPhotoLeavesNoThumbnail() throws IOException {
        UUID captureId = UUID.randomUUID();
        UUID recipeId = newRecipe(captureId, pdf(document -> { }));

        assertThat(images.drain()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT blob_sha256, page, error FROM pdf_image WHERE capture_id = :id")
                .param("id", captureId).query().singleRow())
                .containsEntry("blob_sha256", null).containsEntry("page", null).containsEntry("error", null);
        assertThat(thumbnail(recipeId)).isNull();
    }

    @Test
    void damagedPdfIsRecordedAndNotSearchedAgain() {
        UUID captureId = UUID.randomUUID();
        UUID recipeId = newRecipe(captureId, "not a pdf".getBytes());

        assertThat(images.drain()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT error FROM pdf_image WHERE capture_id = :id").param("id", captureId)
                .query(String.class).single()).isNotBlank();
        assertThat(thumbnail(recipeId)).isNull();
        assertThat(images.drain()).isZero();
    }

    private ObjectNode capture(UUID captureId) {
        String url = "https://www.pdf-" + captureId + ".pl/przepis/";
        return TestCaptures.webCapture(captureId, c -> {
            c.withObject("source").put("url", url);
            c.withObject("raw").withObject("web").put("canonicalUrl", url);
            c.withObject("recipe").put("title", "PDF " + captureId);
            c.withObject("recipe").remove("images");
            c.withArray("attachments").add(TestCaptures.attachment("document-1.pdf", "pdf", "application/pdf"));
        });
    }

    private UUID newRecipe(UUID captureId, byte[] pdf) {
        assertThat(TestCaptures.put(mvc, token, captureId, TestCaptures.capturePart(TestCaptures.bytes(capture(captureId))),
                TestCaptures.filePart("document-1.pdf", "application/pdf", pdf))).hasStatus(201);
        worker.drain();
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\"}")).hasStatusOk();
        return recipeOf(captureId);
    }

    private UUID recipeOf(UUID captureId) {
        return jdbc.sql("SELECT recipe_id FROM recipe_revision WHERE capture_id = :id LIMIT 1").param("id", captureId)
                .query(UUID.class).single();
    }

    private String thumbnail(UUID recipeId) {
        return jdbc.sql("SELECT thumbnail_blob FROM recipe WHERE id = :id").param("id", recipeId)
                .query(String.class).optional().orElse(null);
    }

    private String updatedAt(UUID recipeId) {
        return jdbc.sql("SELECT to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US') FROM recipe WHERE id = :id")
                .param("id", recipeId).query(String.class).single();
    }

    private static byte[] pdfWithPhoto() throws IOException {
        return pdf(document -> {
            try (var content = new PDPageContentStream(document, document.getPage(0))) {
                content.drawImage(LosslessFactory.createFromImage(document, PdfImageExtractorTest.image(640, 480, Color.RED)),
                        50, 400, 320, 240);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static byte[] pdf(java.util.function.Consumer<PDDocument> content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            content.accept(document);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
