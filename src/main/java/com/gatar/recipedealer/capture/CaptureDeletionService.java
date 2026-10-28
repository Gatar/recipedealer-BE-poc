package com.gatar.recipedealer.capture;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.processing.JobQueue;
import com.gatar.recipedealer.processing.ProcessingRepository;
import com.gatar.recipedealer.web.ApiException;

/**
 * {@code DELETE /v1/captures/{captureId}}: a capture that never became a recipe (e.g. an invoice shared by mistake)
 * goes away completely: its rows, its processing runs and decisions, and the blobs nothing else uses.
 */
@Service
public class CaptureDeletionService {

    private static final Logger log = LoggerFactory.getLogger(CaptureDeletionService.class);

    private final JdbcClient jdbc;
    private final ProcessingRepository processing;
    private final JobQueue jobs;
    private final BlobStore blobs;
    private final TransactionTemplate tx;

    public CaptureDeletionService(JdbcClient jdbc, ProcessingRepository processing, JobQueue jobs, BlobStore blobs,
                                  TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.processing = processing;
        this.jobs = jobs;
        this.blobs = blobs;
        this.tx = tx;
    }

    public void delete(UUID captureId) {
        List<String> unused = tx.execute(status -> deleteRows(captureId));
        // Files only after the commit: a rollback must not leave rows pointing to missing blobs. A blob row inserted
        // again since (the same file uploaded at this moment) keeps its file.
        for (String sha256 : unused) {
            boolean storedAgain = jdbc.sql("SELECT EXISTS (SELECT 1 FROM blob WHERE sha256 = :sha256)")
                    .param("sha256", sha256).query(Boolean.class).single();
            if (!storedAgain) {
                try {
                    blobs.delete(sha256);
                } catch (IOException e) {
                    log.warn("Could not delete blob {} of deleted capture {}", sha256, captureId, e);
                }
            }
        }
        log.info("Deleted capture {} and {} unused blob(s)", captureId, unused.size());
    }

    /** Deletes the rows; returns the blobs no row refers to any more (their rows are deleted too). */
    private List<String> deleteRows(UUID captureId) {
        processing.lockState(captureId);
        if (jobs.hasActive(captureId)) {
            throw ApiException.conflict("extraction_in_progress", "this capture is being processed; try again later");
        }
        boolean applied = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM capture_resolution WHERE capture_id = :id AND applied_at IS NOT NULL)
                            OR EXISTS (SELECT 1 FROM recipe_revision WHERE capture_id = :id
                                           OR run_id IN (SELECT id FROM processing_run WHERE capture_id = :id))
                            OR EXISTS (SELECT 1 FROM recipe_key WHERE capture_id = :id)""")
                .param("id", captureId).query(Boolean.class).single();
        if (applied) {
            throw ApiException.conflict("capture_applied",
                    "this capture became a recipe; delete the recipe instead");
        }
        List<String> candidates = jdbc.sql("""
                        SELECT body_sha256 FROM capture WHERE id = :id
                        UNION SELECT blob_sha256 FROM capture_attachment WHERE capture_id = :id
                        UNION SELECT blob_sha256 FROM pdf_image WHERE capture_id = :id AND blob_sha256 IS NOT NULL
                        UNION SELECT blob_sha256 FROM dish_photo WHERE capture_id = :id AND blob_sha256 IS NOT NULL""")
                .param("id", captureId).query(String.class).list();

        jdbc.sql("SELECT set_config('rd.delete_capture', 'on', true)").query().singleRow();
        for (String sql : List.of(
                "DELETE FROM dish_photo WHERE capture_id = :id",
                "DELETE FROM recipe_candidate WHERE run_id IN (SELECT id FROM processing_run WHERE capture_id = :id)",
                "DELETE FROM capture_resolution WHERE capture_id = :id",
                "DELETE FROM job WHERE capture_id = :id",
                "DELETE FROM processing_run WHERE capture_id = :id",
                "DELETE FROM pdf_image WHERE capture_id = :id",
                "DELETE FROM capture_state WHERE capture_id = :id",
                "DELETE FROM capture_attachment WHERE capture_id = :id",
                "DELETE FROM capture WHERE id = :id")) {
            jdbc.sql(sql).param("id", captureId).update();
        }
        jdbc.sql("SELECT set_config('rd.delete_capture', 'off', true)").query().singleRow();

        return candidates.stream().filter(sha256 -> jdbc.sql("""
                        DELETE FROM blob b WHERE b.sha256 = :sha256
                            AND NOT EXISTS (SELECT 1 FROM capture WHERE body_sha256 = b.sha256)
                            AND NOT EXISTS (SELECT 1 FROM capture_attachment WHERE blob_sha256 = b.sha256)
                            AND NOT EXISTS (SELECT 1 FROM pdf_image WHERE blob_sha256 = b.sha256)
                            AND NOT EXISTS (SELECT 1 FROM dish_photo WHERE blob_sha256 = b.sha256)
                            AND NOT EXISTS (SELECT 1 FROM web_image WHERE blob_sha256 = b.sha256)
                            AND NOT EXISTS (SELECT 1 FROM recipe WHERE thumbnail_blob = b.sha256)""")
                .param("sha256", sha256).update() == 1).toList();
    }
}
