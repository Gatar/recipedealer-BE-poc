package com.gatar.recipedealer.purge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.blob.BlobJanitor;
import com.gatar.recipedealer.capture.CaptureDeletionService;
import com.gatar.recipedealer.photo.PhotoService;

/**
 * Permanent deletion (user, 2026-11-27): a deleted recipe or household keeps nothing. Deleting marks it
 * ({@code deleted_at}); the purge follows in the background ({@link PurgePoller}), once no processing job of its
 * captures is running, so a job never finds its rows gone halfway.
 * <ul>
 * <li>A recipe loses its revisions, keys, tags, locks, taggings, translations and the captures only it used (with their
 * runs, decisions and files); the row stays as the sync tombstone (id, household, times, {@code purged_at}).</li>
 * <li>A household loses everything: recipes (no tombstones, no device is left to sync), captures, tags, photos,
 * invitations, login codes, users, devices and the household row itself.</li>
 * </ul>
 * Photos that only the purged data showed go with the following {@link PhotoService#sweep}.
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private final JdbcClient jdbc;
    private final CaptureDeletionService captures;
    private final BlobJanitor janitor;
    private final PhotoService photos;
    private final TransactionTemplate tx;

    public PurgeService(JdbcClient jdbc, CaptureDeletionService captures, BlobJanitor janitor, PhotoService photos,
                        TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.janitor = janitor;
        this.photos = photos;
        this.tx = tx;
    }

    /** Purges what was deleted (up to {@code limit} recipes and households); returns how many were purged. */
    public int purge(int limit) {
        int purged = 0;
        for (UUID household : jdbc.sql("SELECT id FROM household WHERE deleted_at IS NOT NULL ORDER BY deleted_at LIMIT :limit")
                .param("limit", limit).query(UUID.class).list()) {
            purged += purgeHousehold(household) ? 1 : 0;
        }
        for (UUID recipe : jdbc.sql("""
                        SELECT id FROM recipe WHERE deleted_at IS NOT NULL AND purged_at IS NULL ORDER BY deleted_at LIMIT :limit""")
                .param("limit", limit).query(UUID.class).list()) {
            purged += purgeRecipe(recipe) ? 1 : 0;
        }
        if (purged > 0) {
            photos.sweep();
        }
        return purged;
    }

    /** Purges one deleted recipe; false if it is not deleted, already purged, or a job of its captures is running. */
    public boolean purgeRecipe(UUID recipeId) {
        List<String> unused = tx.execute(status -> {
            boolean due = jdbc.sql("SELECT deleted_at IS NOT NULL AND purged_at IS NULL FROM recipe WHERE id = :id FOR UPDATE")
                    .param("id", recipeId).query(Boolean.class).optional().orElse(false);
            if (!due) {
                return null;
            }
            // captures this recipe came from that no other recipe uses
            List<UUID> own = jdbc.sql("""
                            WITH linked AS (
                                SELECT capture_id AS id FROM recipe_revision WHERE recipe_id = :id AND capture_id IS NOT NULL
                                UNION SELECT pr.capture_id FROM recipe_revision v JOIN processing_run pr ON pr.id = v.run_id
                                      WHERE v.recipe_id = :id
                                UNION SELECT capture_id FROM capture_resolution WHERE applied_recipe_id = :id
                                UNION SELECT capture_id FROM recipe_key WHERE recipe_id = :id AND capture_id IS NOT NULL)
                            SELECT l.id FROM linked l
                            WHERE NOT EXISTS (SELECT 1 FROM recipe_revision v
                                              WHERE v.recipe_id <> :id
                                                AND (v.capture_id = l.id
                                                     OR v.run_id IN (SELECT id FROM processing_run WHERE capture_id = l.id)))
                              AND NOT EXISTS (SELECT 1 FROM recipe_key k WHERE k.recipe_id <> :id AND k.capture_id = l.id)
                              AND NOT EXISTS (SELECT 1 FROM capture_resolution cr JOIN recipe r ON r.id = cr.applied_recipe_id
                                              WHERE cr.capture_id = l.id AND cr.applied_recipe_id <> :id
                                                AND r.purged_at IS NULL)""")
                    .param("id", recipeId).query(UUID.class).list();
            if (running(own)) {
                status.setRollbackOnly();
                return null;
            }
            List<String> blobs = new ArrayList<>(jdbc.sql("SELECT thumbnail_blob FROM recipe WHERE id = :id AND thumbnail_blob IS NOT NULL")
                    .param("id", recipeId).query(String.class).list());
            CaptureDeletionService.allowDeletes(jdbc, true);
            jdbc.sql("""
                            UPDATE recipe SET head_revision_id = NULL, title = NULL, ingredients_text = NULL,
                                              source_summary = NULL, thumbnail_blob = NULL, purged_at = now()
                            WHERE id = :id""").param("id", recipeId).update();
            for (String sql : List.of(
                    "DELETE FROM recipe_tag WHERE recipe_id = :id",
                    "DELETE FROM recipe_key WHERE recipe_id = :id",
                    "DELETE FROM recipe_lock WHERE recipe_id = :id",
                    "DELETE FROM auto_tagging WHERE recipe_id = :id",
                    "DELETE FROM auto_translation WHERE recipe_id = :id",
                    """
                    UPDATE capture_resolution SET applied_revision_id = NULL
                    WHERE applied_revision_id IN (SELECT id FROM recipe_revision WHERE recipe_id = :id)""",
                    "DELETE FROM recipe_revision WHERE recipe_id = :id")) {
                jdbc.sql(sql).param("id", recipeId).update();
            }
            for (UUID capture : own) {
                blobs.addAll(captures.deleteRows(capture));
            }
            CaptureDeletionService.allowDeletes(jdbc, false);
            return janitor.deleteRows(blobs);
        });
        if (unused == null) {
            return false;
        }
        janitor.deleteFiles(unused);
        log.info("Purged deleted recipe {} ({} blob(s))", recipeId, unused.size());
        return true;
    }

    /** Purges one deleted household; false if it is not deleted or a job of its captures is running. */
    public boolean purgeHousehold(UUID householdId) {
        List<String> unused = tx.execute(status -> {
            boolean due = jdbc.sql("SELECT deleted_at IS NOT NULL FROM household WHERE id = :id FOR UPDATE")
                    .param("id", householdId).query(Boolean.class).optional().orElse(false);
            if (!due) {
                return null;
            }
            List<UUID> all = jdbc.sql("SELECT id FROM capture WHERE household_id = :id").param("id", householdId)
                    .query(UUID.class).list();
            if (running(all)) {
                status.setRollbackOnly();
                return null;
            }
            List<String> blobs = new ArrayList<>(jdbc.sql("""
                            SELECT thumbnail_blob FROM recipe WHERE household_id = :id AND thumbnail_blob IS NOT NULL
                            UNION SELECT blob_sha256 FROM user_photo WHERE household_id = :id""")
                    .param("id", householdId).query(String.class).list());
            CaptureDeletionService.allowDeletes(jdbc, true);
            for (String sql : List.of(
                    "DELETE FROM recipe_tag WHERE household_id = :id",
                    "DELETE FROM tag WHERE household_id = :id",
                    "DELETE FROM tag_category WHERE household_id = :id",
                    "DELETE FROM recipe_key WHERE household_id = :id",
                    "DELETE FROM recipe_lock WHERE recipe_id IN (SELECT id FROM recipe WHERE household_id = :id)",
                    "DELETE FROM auto_tagging WHERE recipe_id IN (SELECT id FROM recipe WHERE household_id = :id)",
                    "DELETE FROM auto_translation WHERE recipe_id IN (SELECT id FROM recipe WHERE household_id = :id)",
                    "DELETE FROM capture_resolution WHERE capture_id IN (SELECT id FROM capture WHERE household_id = :id)",
                    "UPDATE recipe SET head_revision_id = NULL, thumbnail_blob = NULL WHERE household_id = :id",
                    "DELETE FROM recipe_revision WHERE recipe_id IN (SELECT id FROM recipe WHERE household_id = :id)",
                    "DELETE FROM recipe WHERE household_id = :id")) {
                jdbc.sql(sql).param("id", householdId).update();
            }
            for (UUID capture : all) {
                blobs.addAll(captures.deleteRows(capture));
            }
            CaptureDeletionService.allowDeletes(jdbc, false);
            for (String sql : List.of(
                    "DELETE FROM user_photo WHERE household_id = :id",
                    "DELETE FROM household_invitation WHERE household_id = :id",
                    """
                    DELETE FROM login_code WHERE user_id IN (SELECT id FROM app_user WHERE household_id = :id)
                                              OR created_by IN (SELECT id FROM app_user WHERE household_id = :id)""",
                    "DELETE FROM device_token WHERE user_id IN (SELECT id FROM app_user WHERE household_id = :id)",
                    "DELETE FROM app_user WHERE household_id = :id",
                    "DELETE FROM household WHERE id = :id")) {
                jdbc.sql(sql).param("id", householdId).update();
            }
            return janitor.deleteRows(blobs);
        });
        if (unused == null) {
            return false;
        }
        janitor.deleteFiles(unused);
        log.info("Purged deleted household {} ({} blob(s))", householdId, unused.size());
        return true;
    }

    /**
     * A processing job of one of the captures is running. The jobs stay locked until the commit, so the worker cannot
     * claim a queued one meanwhile (it skips locked jobs); queued ones are dropped with the capture.
     */
    private boolean running(List<UUID> captureIds) {
        return !captureIds.isEmpty() && jdbc.sql("SELECT status FROM job WHERE capture_id = ANY (:ids) FOR UPDATE")
                .param("ids", captureIds.toArray(UUID[]::new)).query(String.class).list().contains("running");
    }
}
