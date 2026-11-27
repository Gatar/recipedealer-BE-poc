package com.gatar.recipedealer.capture;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.blob.BlobJanitor;
import com.gatar.recipedealer.processing.JobQueue;
import com.gatar.recipedealer.processing.ProcessingRepository;
import com.gatar.recipedealer.web.ApiException;

/**
 * {@code DELETE /v1/captures/{captureId}}: a capture that never became a recipe (e.g. an invoice shared by mistake)
 * goes away completely: its rows, its processing runs and decisions, and the blobs nothing else uses. The purge of
 * deleted recipes and households uses {@link #deleteRows} for the captures that go with them.
 */
@Service
public class CaptureDeletionService {

    private static final Logger log = LoggerFactory.getLogger(CaptureDeletionService.class);

    private final JdbcClient jdbc;
    private final ProcessingRepository processing;
    private final JobQueue jobs;
    private final BlobJanitor janitor;
    private final TransactionTemplate tx;

    public CaptureDeletionService(JdbcClient jdbc, ProcessingRepository processing, JobQueue jobs, BlobJanitor janitor,
                                  TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.processing = processing;
        this.jobs = jobs;
        this.janitor = janitor;
        this.tx = tx;
    }

    public void delete(UUID captureId) {
        List<String> unused = tx.execute(status -> {
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
            allowDeletes(jdbc, true);
            List<String> blobs = deleteRows(captureId);
            allowDeletes(jdbc, false);
            return janitor.deleteRows(blobs);
        });
        janitor.deleteFiles(unused);
        log.info("Deleted capture {} and {} unused blob(s)", captureId, unused.size());
    }

    /** Lets this transaction delete immutable rows (captures, attachments, revisions; V15, V24). */
    public static void allowDeletes(JdbcClient jdbc, boolean on) {
        jdbc.sql("SELECT set_config('rd.delete_capture', :value, true)").param("value", on ? "on" : "off").query().singleRow();
    }

    /**
     * Deletes the capture's rows (nothing may refer to its runs any more, deletes must be allowed); returns the blobs
     * it used, for {@link BlobJanitor#deleteRows}.
     */
    public List<String> deleteRows(UUID captureId) {
        List<String> blobs = jdbc.sql("""
                        SELECT body_sha256 FROM capture WHERE id = :id
                        UNION SELECT blob_sha256 FROM capture_attachment WHERE capture_id = :id
                        UNION SELECT blob_sha256 FROM pdf_image WHERE capture_id = :id AND blob_sha256 IS NOT NULL
                        UNION SELECT blob_sha256 FROM dish_photo WHERE capture_id = :id AND blob_sha256 IS NOT NULL""")
                .param("id", captureId).query(String.class).list();
        for (String sql : List.of(
                "DELETE FROM dish_photo WHERE capture_id = :id",
                "DELETE FROM recipe_candidate WHERE run_id IN (SELECT id FROM processing_run WHERE capture_id = :id)",
                "DELETE FROM capture_resolution WHERE capture_id = :id",
                "DELETE FROM auto_tagging WHERE capture_id = :id",
                "DELETE FROM auto_translation WHERE capture_id = :id",
                "DELETE FROM job WHERE capture_id = :id",
                "DELETE FROM processing_run WHERE capture_id = :id",
                "DELETE FROM pdf_image WHERE capture_id = :id",
                "DELETE FROM capture_state WHERE capture_id = :id",
                "DELETE FROM capture_attachment WHERE capture_id = :id",
                "DELETE FROM capture WHERE id = :id")) {
            jdbc.sql(sql).param("id", captureId).update();
        }
        return blobs;
    }
}
