package com.gatar.recipedealer.pdfimage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.recipe.RecipeRepository;

/**
 * Photos of PDF attachments ({@link PdfImageExtractor}), searched once per attachment in the background. A photo
 * becomes the thumbnail of the recipes behind its capture like a photo attachment would; a PDF without one leaves the
 * thumbnail as it is.
 */
@Service
public class PdfImageService {

    private static final Logger log = LoggerFactory.getLogger(PdfImageService.class);

    public record Pdf(UUID captureId, String name, String blobSha256) {
    }

    private final JdbcClient jdbc;
    private final BlobStore blobs;
    private final PdfImageExtractor extractor;
    private final RecipeRepository recipes;
    private final PdfImageProperties properties;
    private final TransactionTemplate tx;

    public PdfImageService(JdbcClient jdbc, BlobStore blobs, PdfImageExtractor extractor, RecipeRepository recipes,
                           PdfImageProperties properties, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.extractor = extractor;
        this.recipes = recipes;
        this.properties = properties;
        this.tx = tx;
    }

    /** PDF attachments not searched yet. */
    public List<Pdf> due() {
        return jdbc.sql("""
                        SELECT a.capture_id, a.name, a.blob_sha256
                        FROM capture_attachment a
                        LEFT JOIN pdf_image p ON p.capture_id = a.capture_id AND p.attachment_name = a.name
                        WHERE (a.role = 'pdf' OR lower(a.media_type) = 'application/pdf') AND p.capture_id IS NULL
                        ORDER BY a.capture_id, a.name
                        LIMIT :limit""")
                .param("limit", properties.batchSize())
                .query((rs, n) -> new Pdf(Jdbc.uuid(rs, "capture_id"), rs.getString("name"), rs.getString("blob_sha256")))
                .list();
    }

    /** Searches one batch of PDFs; returns how many were searched. */
    public int drain() {
        List<Pdf> pdfs = due();
        pdfs.forEach(this::search);
        return pdfs.size();
    }

    /** Searches one PDF and records the result; a photo found becomes the thumbnail of the recipes behind it. */
    public void search(Pdf pdf) {
        Optional<PdfImageExtractor.Photo> photo;
        try (InputStream in = blobs.open(pdf.blobSha256())) {
            photo = extractor.extract(in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            record(pdf, null, null, error.length() > 500 ? error.substring(0, 500) : error);
            log.warn("Capture {}: cannot search PDF {} for a photo: {}", pdf.captureId(), pdf.name(), e.toString());
            return;
        }
        if (photo.isEmpty()) {
            record(pdf, null, null, null);
            log.info("Capture {}: no photo in PDF {}", pdf.captureId(), pdf.name());
            return;
        }
        BlobRef blob;
        try {
            blob = blobs.put(new ByteArrayInputStream(photo.get().jpeg()));
        } catch (IOException e) {
            log.warn("Capture {}: cannot store the photo of PDF {}, retried later: {}", pdf.captureId(), pdf.name(), e.toString());
            return;
        }
        Integer updated = tx.execute(status -> {
            jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                    .param("sha256", blob.sha256())
                    .param("size", blob.sizeBytes())
                    .update();
            record(pdf, blob, photo.get(), null);
            return recipes.refreshThumbnails(pdf.captureId());
        });
        log.info("Capture {}: photo from page {} of PDF {} ({}x{}) for {} recipe(s)", pdf.captureId(), photo.get().page(),
                pdf.name(), photo.get().width(), photo.get().height(), updated);
    }

    private void record(Pdf pdf, BlobRef blob, PdfImageExtractor.Photo photo, String error) {
        jdbc.sql("""
                        INSERT INTO pdf_image (capture_id, attachment_name, blob_sha256, page, width, height, error)
                        VALUES (:captureId, :name, :sha256, :page, :width, :height, :error)
                        ON CONFLICT (capture_id, attachment_name) DO NOTHING""")
                .param("captureId", pdf.captureId())
                .param("name", pdf.name())
                .param("sha256", blob == null ? null : blob.sha256())
                .param("page", photo == null ? null : photo.page())
                .param("width", photo == null ? null : photo.width())
                .param("height", photo == null ? null : photo.height())
                .param("error", error)
                .update();
    }
}
