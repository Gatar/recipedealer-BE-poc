package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gatar.recipedealer.blob.FileSystemBlobStore;
import com.gatar.recipedealer.processing.Capture;

import tools.jackson.databind.json.JsonMapper;

class LlmInputBuilderTest {

    @TempDir
    Path dir;

    FileSystemBlobStore blobs;
    LlmInputBuilder inputs;

    @BeforeEach
    void setUp() throws IOException {
        blobs = new FileSystemBlobStore(dir);
        inputs = builder(30_000_000);
    }

    private LlmInputBuilder builder(long maxRequestBytes) {
        return new LlmInputBuilder(blobs, new LlmProperties("key", Duration.ofSeconds(1), 0, 1, maxRequestBytes, 2000,
                400_000, List.of(), Map.of()));
    }

    private static byte[] image(int width, int height, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private Capture.Attachment attachment(String name, String role, String mediaType, byte[] content) throws IOException {
        var blob = blobs.put(new ByteArrayInputStream(content));
        return new Capture.Attachment(name, role, mediaType, blob.sha256(), content.length);
    }

    private static Capture capture(String body, Capture.Attachment... attachments) {
        return new Capture(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(), 1, "0".repeat(64),
                JsonMapper.builder().build().readTree(body), List.of(attachments));
    }

    private static final String IMAGE_BODY = """
            {"source": {"type": "IMAGE", "url": null}, "raw": {"ocr": {"text": "Sernik\\n1 kg twarogu"}}}""";

    private static List<BufferedImage> decoded(List<ClaudeClient.Part> parts) {
        return parts.stream().filter(p -> p instanceof ClaudeClient.Image).map(p -> {
            try {
                return ImageIO.read(new ByteArrayInputStream(((ClaudeClient.Image) p).data()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    @Test
    void imageWithinTheLimitsIsSentAsItIs() throws IOException {
        byte[] jpeg = image(1600, 1200, "jpg");
        var input = inputs.build(capture(IMAGE_BODY, attachment("photo.jpg", "image", "image/jpeg", jpeg)));
        var parts = input.parts();

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).isEqualTo(new ClaudeClient.Text("Image 1, photo.jpg:"));
        assertThat(input.images()).singleElement().satisfies(image -> {
            assertThat(image.number()).isEqualTo(1);
            assertThat(image.attachment().name()).isEqualTo("photo.jpg");
            assertThat(image.region()).isEqualTo(new LlmInputBuilder.Region(0, 0, 1600, 1200));
        });
        assertThat(((ClaudeClient.Image) parts.get(1)).data()).isEqualTo(jpeg);
        String text = ((ClaudeClient.Text) parts.get(2)).text();
        assertThat(text).startsWith("Source type: IMAGE\n")
                .contains("Text recognized by the phone (OCR, may contain errors):\nSernik\n1 kg twarogu")
                .endsWith("Extract the recipe from the source above.");
    }

    @Test
    void largeImageIsDownscaledToTheMaximumEdge() throws IOException {
        var input = inputs.build(capture(IMAGE_BODY, attachment("big.png", "image", "image/png", image(4000, 3000, "png"))));
        var parts = input.parts();

        assertThat(input.images()).singleElement()
                .satisfies(image -> assertThat(image.region()).isEqualTo(new LlmInputBuilder.Region(0, 0, 4000, 3000)));
        assertThat(decoded(parts)).singleElement().satisfies(image -> {
            assertThat(image.getWidth()).isEqualTo(2000);
            assertThat(image.getHeight()).isEqualTo(1500);
        });
        assertThat(parts).filteredOn(p -> p instanceof ClaudeClient.Image)
                .allSatisfy(p -> assertThat(((ClaudeClient.Image) p).mediaType()).isEqualTo("image/jpeg"));
    }

    @Test
    void tallScreenshotIsCutIntoOverlappingTiles() throws IOException {
        var input = inputs.build(capture(IMAGE_BODY,
                attachment("page.jpg", "screenshot", "image/jpeg", image(1000, 5000, "jpg"))));
        var parts = input.parts();

        assertThat(decoded(parts)).extracting(BufferedImage::getHeight).containsExactly(2000, 2000, 1200);
        assertThat(decoded(parts)).allSatisfy(tile -> assertThat(tile.getWidth()).isEqualTo(1000));
        assertThat(parts.get(0)).isEqualTo(
                new ClaudeClient.Text("Image 1, page.jpg, part 1 of 3 (top to bottom, parts overlap slightly):"));
        assertThat(parts.get(2)).isEqualTo(
                new ClaudeClient.Text("Image 2, page.jpg, part 2 of 3 (top to bottom, parts overlap slightly):"));
        assertThat(input.images()).extracting(LlmInputBuilder.SentImage::region).containsExactly(
                new LlmInputBuilder.Region(0, 0, 1000, 2000),
                new LlmInputBuilder.Region(0, 1900, 1000, 2000),
                new LlmInputBuilder.Region(0, 3800, 1000, 1200));
    }

    @Test
    void pdfTooLargeForOneRequestIsLeftOut() throws IOException {
        var pdf = attachment("book.pdf", "pdf", "application/pdf", new byte[3000]);
        var capture = capture("{\"source\": {\"type\": \"PDF\"}, \"raw\": {}}", pdf);

        assertThat(builder(2000).hasInput(capture)).isFalse();
        assertThat(inputs.hasInput(capture)).isTrue();
        var input = inputs.build(capture);
        assertThat(input.parts()).hasSize(3).element(1).isInstanceOf(ClaudeClient.Pdf.class);
        assertThat(input.images()).isEmpty();
    }

    @Test
    void captureWithoutAttachmentsOrTextHasNoInput() {
        var capture = capture("{\"source\": {\"type\": \"MANUAL\"}, \"raw\": {\"text\": \"  \"}}");
        assertThat(inputs.hasInput(capture)).isFalse();
    }
}
