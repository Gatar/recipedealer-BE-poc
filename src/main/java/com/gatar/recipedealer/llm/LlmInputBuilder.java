package com.gatar.recipedealer.llm;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.processing.Capture;

import tools.jackson.databind.JsonNode;

/**
 * What the LLM sees of a capture: its attachments as native content blocks (images, PDFs) followed by the text the
 * phone already read and a little source metadata. Images larger than {@code maxImageEdge} are downscaled; tall
 * screenshots (up to 20,000 px) are cut into overlapping tiles, because the API would shrink them to an unreadable
 * width. Parts that would push the request over {@code maxRequestBytes} are left out.
 */
@Component
public class LlmInputBuilder {

    static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    /** Per image the API takes 10 MB of base64; stay well below. */
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_IMAGES = 100;
    static final int TILE_OVERLAP = 100;

    private static final Logger log = LoggerFactory.getLogger(LlmInputBuilder.class);

    private final BlobStore blobs;
    private final LlmProperties properties;

    public LlmInputBuilder(BlobStore blobs, LlmProperties properties) {
        this.blobs = blobs;
        this.properties = properties;
    }

    /** Cheap check without reading blobs: is there anything an LLM could read? */
    public boolean hasInput(Capture capture) {
        return capture.attachments().stream().anyMatch(this::usable) || !texts(capture).isEmpty();
    }

    private boolean usable(Capture.Attachment attachment) {
        return isPdf(attachment) ? base64Size(attachment.sizeBytes()) <= properties.maxRequestBytes() : isImage(attachment);
    }

    public List<ClaudeClient.Part> build(Capture capture) throws IOException {
        List<ClaudeClient.Part> parts = new ArrayList<>();
        long budget = properties.maxRequestBytes() - 200_000;
        int imageCount = 0;
        for (Capture.Attachment attachment : capture.attachments()) {
            byte[] bytes;
            if (isPdf(attachment)) {
                if (base64Size(attachment.sizeBytes()) > budget) {
                    log.info("Capture {}: PDF {} ({} bytes) does not fit into one request, left out", capture.id(),
                            attachment.name(), attachment.sizeBytes());
                    continue;
                }
                bytes = read(attachment);
                parts.add(new ClaudeClient.Text("Document " + attachment.name() + ":"));
                parts.add(new ClaudeClient.Pdf(bytes));
                budget -= base64Size(bytes.length);
            } else if (isImage(attachment)) {
                List<ClaudeClient.Image> images = images(capture, attachment, read(attachment));
                for (int i = 0; i < images.size(); i++) {
                    ClaudeClient.Image image = images.get(i);
                    long size = base64Size(image.data().length);
                    if (size > budget || imageCount >= MAX_IMAGES) {
                        log.info("Capture {}: image {} left out (request size or image count limit)", capture.id(),
                                attachment.name());
                        break;
                    }
                    String label = images.size() == 1 ? "Image " + attachment.name() + ":"
                            : "Image " + attachment.name() + ", part " + (i + 1) + " of " + images.size()
                              + " (top to bottom, parts overlap slightly):";
                    parts.add(new ClaudeClient.Text(label));
                    parts.add(image);
                    budget -= size;
                    imageCount++;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        for (String section : texts(capture)) {
            text.append(section).append("\n\n");
        }
        if (text.length() > properties.maxTextChars()) {
            text.setLength(properties.maxTextChars());
            text.append("\n[text truncated]\n\n");
        }
        text.append("Extract the recipe from the source above.");
        parts.add(new ClaudeClient.Text(metadata(capture) + text));
        return parts;
    }

    private static String metadata(Capture capture) {
        JsonNode source = capture.body().path("source");
        StringBuilder metadata = new StringBuilder("Source type: ").append(source.path("type").asString("unknown")).append('\n');
        appendIfPresent(metadata, "URL", source.path("url"));
        appendIfPresent(metadata, "Site", source.path("siteName"));
        appendIfPresent(metadata, "Page title", capture.body().path("raw").path("web").path("title"));
        return metadata.append('\n').toString();
    }

    private static void appendIfPresent(StringBuilder target, String label, JsonNode value) {
        if (value.isString() && !value.asString().isBlank()) {
            target.append(label).append(": ").append(value.asString().strip()).append('\n');
        }
    }

    /** Text the phone read from the source, each with what it is. */
    static List<String> texts(Capture capture) {
        JsonNode raw = capture.body().path("raw");
        List<String> sections = new ArrayList<>();
        addSection(sections, "Text shared to the app", raw.path("text"));
        addSection(sections, "Text layer of the PDF", raw.path("pdfText"));
        addSection(sections, "Text recognized by the phone (OCR, may contain errors)", raw.path("ocr").path("text"));
        addSection(sections, "Readable text of the web page", raw.path("web").path("readableText"));
        return sections;
    }

    private static void addSection(List<String> sections, String label, JsonNode value) {
        if (value.isString() && !value.asString().isBlank()) {
            sections.add(label + ":\n" + value.asString().strip());
        }
    }

    private byte[] read(Capture.Attachment attachment) throws IOException {
        try (InputStream in = blobs.open(attachment.blobSha256())) {
            return in.readAllBytes();
        }
    }

    /** The original when it is within the limits, else a downscaled JPEG or JPEG tiles; none if it cannot be used. */
    List<ClaudeClient.Image> images(Capture capture, Capture.Attachment attachment, byte[] bytes) throws IOException {
        String mediaType = attachment.mediaType().toLowerCase(java.util.Locale.ROOT);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            // ImageIO cannot decode it (e.g. WebP): send it as it is if the API takes it.
            if (IMAGE_TYPES.contains(mediaType) && bytes.length <= MAX_IMAGE_BYTES) {
                return List.of(new ClaudeClient.Image(mediaType, bytes));
            }
            log.info("Capture {}: image {} ({}) cannot be decoded, left out", capture.id(), attachment.name(), mediaType);
            return List.of();
        }
        int max = properties.maxImageEdge();
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= max && height <= max && bytes.length <= MAX_IMAGE_BYTES && IMAGE_TYPES.contains(mediaType)) {
            return List.of(new ClaudeClient.Image(mediaType, bytes));
        }
        if (height > 2L * width && height > max) {
            BufferedImage scaled = scale(image, Math.min(1.0, (double) max / width));
            List<ClaudeClient.Image> tiles = new ArrayList<>();
            int tileHeight = max;
            for (int top = 0; ; top += tileHeight - TILE_OVERLAP) {
                int bottom = Math.min(scaled.getHeight(), top + tileHeight);
                tiles.add(new ClaudeClient.Image("image/jpeg", jpeg(scaled.getSubimage(0, top, scaled.getWidth(), bottom - top))));
                if (bottom >= scaled.getHeight()) {
                    break;
                }
            }
            return tiles;
        }
        return List.of(new ClaudeClient.Image("image/jpeg", jpeg(scale(image, Math.min(1.0, (double) max / Math.max(width, height))))));
    }

    /** RGB copy (transparency on white) scaled by {@code factor}. */
    private static BufferedImage scale(BufferedImage source, double factor) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static boolean isPdf(Capture.Attachment attachment) {
        return "pdf".equals(attachment.role()) || "application/pdf".equalsIgnoreCase(attachment.mediaType());
    }

    private static boolean isImage(Capture.Attachment attachment) {
        return ("image".equals(attachment.role()) || "screenshot".equals(attachment.role()))
                && attachment.mediaType() != null && attachment.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }

    static long base64Size(long bytes) {
        return (bytes + 2) / 3 * 4;
    }
}
