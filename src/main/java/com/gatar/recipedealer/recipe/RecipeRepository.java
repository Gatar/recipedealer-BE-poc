package com.gatar.recipedealer.recipe;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.gatar.recipedealer.common.Jdbc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RecipeRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public RecipeRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public enum Origin { MACHINE, USER }

    /** Current state of a recipe and its head, read with a row lock on the recipe. */
    public record Head(UUID recipeId, UUID headRevisionId, Origin headOrigin, String headContentHash,
                       boolean headNeedsReview, boolean deleted, UUID mergedInto) {
    }

    public record NewRevision(UUID id, UUID recipeId, UUID parentId, Origin origin, UUID runId, UUID captureId,
                              UUID userId, UUID deviceId, int schemaVersion, JsonNode content, String contentHash,
                              boolean needsReview) {
    }

    public void insertRecipe(UUID id) {
        jdbc.sql("INSERT INTO recipe (id) VALUES (:id)").param("id", id).update();
    }

    public Optional<Head> lockHead(UUID recipeId) {
        return jdbc.sql("""
                        SELECT r.id, r.head_revision_id, h.origin, h.content_hash, h.needs_review,
                               r.deleted_at IS NOT NULL AS deleted, r.merged_into
                        FROM recipe r LEFT JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE r.id = :id
                        FOR UPDATE OF r""")
                .param("id", recipeId)
                .query((rs, n) -> new Head(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "head_revision_id"),
                        rs.getString("origin") == null ? null : Origin.valueOf(rs.getString("origin")),
                        rs.getString("content_hash"), rs.getBoolean("needs_review"), rs.getBoolean("deleted"),
                        Jdbc.uuid(rs, "merged_into")))
                .optional();
    }

    public void insertRevision(NewRevision revision) {
        jdbc.sql("""
                        INSERT INTO recipe_revision (id, recipe_id, parent_id, origin, run_id, capture_id, user_id, device_id,
                                                     schema_version, content, content_hash, needs_review)
                        VALUES (:id, :recipeId, :parentId, :origin, :runId, :captureId, :userId, :deviceId,
                                :schemaVersion, CAST(:content AS jsonb), :hash, :needsReview)""")
                .param("id", revision.id())
                .param("recipeId", revision.recipeId())
                .param("parentId", revision.parentId())
                .param("origin", revision.origin().name())
                .param("runId", revision.runId())
                .param("captureId", revision.captureId())
                .param("userId", revision.userId())
                .param("deviceId", revision.deviceId())
                .param("schemaVersion", revision.schemaVersion())
                .param("content", jsonMapper.writeValueAsString(revision.content()))
                .param("hash", revision.contentHash())
                .param("needsReview", revision.needsReview())
                .update();
    }

    /** An existing proposal with the same content on top of the given parent, to avoid duplicate proposals. */
    public Optional<UUID> findChild(UUID parentId, String contentHash) {
        return jdbc.sql("SELECT id FROM recipe_revision WHERE parent_id = :parentId AND content_hash = :hash LIMIT 1")
                .param("parentId", parentId)
                .param("hash", contentHash)
                .query(UUID.class)
                .optional();
    }

    /**
     * The newest capture photo of recipe {@code r}: the dish photo the newest succeeded (non-comparison) LLM run of a
     * capture cut out of its photos or screenshots ({@code dish_photo}; none when it found no dish); for a capture no
     * LLM run judged, the photo or screenshot attachment itself; or the photo found in a PDF attachment
     * ({@code pdf_image}).
     */
    private static final String CAPTURE_PHOTO = """
            (SELECT p.blob_sha256
             FROM recipe_revision v
             LEFT JOIN LATERAL (SELECT d.blob_sha256, d.attachment_name, true AS judged
                                FROM dish_photo d JOIN processing_run pr ON pr.id = d.run_id
                                WHERE d.capture_id = v.capture_id AND pr.purpose <> 'comparison'
                                  AND pr.status = 'succeeded'
                                ORDER BY pr.finished_at DESC, pr.id DESC
                                LIMIT 1) j ON true
             JOIN LATERAL (SELECT a.blob_sha256, a.name FROM capture_attachment a
                           WHERE a.capture_id = v.capture_id AND a.role IN ('image', 'screenshot') AND j.judged IS NULL
                           UNION ALL
                           SELECT j.blob_sha256, j.attachment_name WHERE j.blob_sha256 IS NOT NULL
                           UNION ALL
                           SELECT i.blob_sha256, i.attachment_name FROM pdf_image i
                           WHERE i.capture_id = v.capture_id AND i.blob_sha256 IS NOT NULL) p ON true
             WHERE v.recipe_id = r.id
             ORDER BY v.created_at DESC, p.name
             LIMIT 1)""";

    /**
     * Points the head to a revision and refreshes the copied search/display columns. The source summary changes only
     * when the revision comes from a capture (a user edit keeps it). The thumbnail: the server copy of the head's
     * main photo ({@code web_image}), else the newest capture photo; a photo downloaded later is set by
     * {@link #setWebImageThumbnail}, one found in a PDF later by {@link #refreshThumbnails}.
     */
    public void moveHead(UUID recipeId, UUID revisionId, JsonNode content, UUID captureId) {
        jdbc.sql("""
                        UPDATE recipe r SET head_revision_id = :revisionId, title = :title, ingredients_text = :ingredients,
                               source_summary = CASE WHEN CAST(:captureId AS uuid) IS NULL THEN source_summary ELSE (
                                   SELECT coalesce(nullif(c.body->'source'->>'siteName', ''),
                                                   substring(c.source_url from '^[a-z]+://(?:www\\.)?([^/:?#]+)'),
                                                   lower(c.source_type))
                                   FROM capture c WHERE c.id = :captureId) END,
                               thumbnail_blob = coalesce(
                                   (SELECT w.blob_sha256 FROM web_image w WHERE w.url = CAST(:imageUrl AS text)),
                                   %s),
                               updated_at = now()
                        WHERE r.id = :recipeId""".formatted(CAPTURE_PHOTO))
                .param("recipeId", recipeId)
                .param("revisionId", revisionId)
                .param("title", RecipeContent.title(content))
                .param("ingredients", RecipeContent.ingredientsText(content))
                .param("captureId", captureId)
                .param("imageUrl", RecipeContent.mainImage(content))
                .update();
    }

    /** Sets a downloaded photo as the thumbnail of every live recipe whose head shows it; returns how many changed. */
    public int setWebImageThumbnail(String url, String blobSha256) {
        return jdbc.sql("""
                        UPDATE recipe r SET thumbnail_blob = :sha256, updated_at = now()
                        FROM recipe_revision h
                        WHERE h.id = r.head_revision_id AND r.deleted_at IS NULL AND r.merged_into IS NULL
                          AND trim(h.content->'images'->>0) = :url AND r.thumbnail_blob IS DISTINCT FROM :sha256""")
                .param("url", url)
                .param("sha256", blobSha256)
                .update();
    }

    /**
     * Recomputes the thumbnail of the live recipes behind a capture, after a photo of it was found later (in a PDF);
     * returns how many changed.
     */
    public int refreshThumbnails(UUID captureId) {
        return jdbc.sql("""
                        WITH target AS (
                            SELECT r.id, coalesce(
                                       (SELECT w.blob_sha256 FROM recipe_revision h JOIN web_image w
                                            ON w.url = trim(h.content->'images'->>0)
                                        WHERE h.id = r.head_revision_id),
                                       %s) AS thumbnail
                            FROM recipe r
                            WHERE r.deleted_at IS NULL AND r.merged_into IS NULL
                              AND EXISTS (SELECT 1 FROM recipe_revision v WHERE v.recipe_id = r.id AND v.capture_id = :captureId))
                        UPDATE recipe r SET thumbnail_blob = t.thumbnail, updated_at = now()
                        FROM target t
                        WHERE r.id = t.id AND r.thumbnail_blob IS DISTINCT FROM t.thumbnail""".formatted(CAPTURE_PHOTO))
                .param("captureId", captureId)
                .update();
    }

    /** Attaches a key unless it already exists; returns the recipe that owns the key afterwards. */
    public UUID addKey(RecipeKey key, UUID recipeId, UUID captureId) {
        jdbc.sql("""
                        INSERT INTO recipe_key (kind, value, raw_value, normalizer_version, recipe_id, capture_id)
                        VALUES (:kind, :value, :raw, :version, :recipeId, :captureId)
                        ON CONFLICT (kind, value) DO NOTHING""")
                .param("kind", key.kind().name())
                .param("value", key.value())
                .param("raw", key.rawValue())
                .param("version", key.normalizerVersion())
                .param("recipeId", recipeId)
                .param("captureId", captureId)
                .update();
        return jdbc.sql("SELECT recipe_id FROM recipe_key WHERE kind = :kind AND value = :value")
                .param("kind", key.kind().name())
                .param("value", key.value())
                .query(UUID.class)
                .single();
    }

    /**
     * The recipe that should receive changes aimed at {@code recipeId}: follows {@code mergedInto}; empty if the
     * recipe does not exist or was deleted.
     */
    public Optional<UUID> liveTarget(UUID recipeId) {
        UUID current = recipeId;
        for (int hop = 0; hop < 16 && current != null; hop++) {
            var row = jdbc.sql("SELECT merged_into, deleted_at IS NOT NULL AS deleted FROM recipe WHERE id = :id")
                    .param("id", current)
                    .query((rs, n) -> new Object[] {Jdbc.uuid(rs, "merged_into"), rs.getBoolean("deleted")})
                    .optional();
            if (row.isEmpty() || (boolean) row.get()[1]) {
                return Optional.empty();
            }
            if (row.get()[0] == null) {
                return Optional.of(current);
            }
            current = (UUID) row.get()[0];
        }
        return Optional.empty();
    }

    public Optional<String> title(UUID recipeId) {
        return jdbc.sql("SELECT coalesce(title, '') FROM recipe WHERE id = :id").param("id", recipeId)
                .query(String.class).optional();
    }
}
