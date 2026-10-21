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
                       boolean deleted, UUID mergedInto) {
    }

    public record NewRevision(UUID id, UUID recipeId, UUID parentId, Origin origin, UUID runId, UUID captureId,
                              UUID userId, UUID deviceId, JsonNode content, String contentHash, boolean needsReview) {
    }

    public void insertRecipe(UUID id) {
        jdbc.sql("INSERT INTO recipe (id) VALUES (:id)").param("id", id).update();
    }

    public Optional<Head> lockHead(UUID recipeId) {
        return jdbc.sql("""
                        SELECT r.id, r.head_revision_id, h.origin, h.content_hash, r.deleted_at IS NOT NULL AS deleted, r.merged_into
                        FROM recipe r LEFT JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE r.id = :id
                        FOR UPDATE OF r""")
                .param("id", recipeId)
                .query((rs, n) -> new Head(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "head_revision_id"),
                        rs.getString("origin") == null ? null : Origin.valueOf(rs.getString("origin")),
                        rs.getString("content_hash"), rs.getBoolean("deleted"), Jdbc.uuid(rs, "merged_into")))
                .optional();
    }

    public void insertRevision(NewRevision revision) {
        jdbc.sql("""
                        INSERT INTO recipe_revision (id, recipe_id, parent_id, origin, run_id, capture_id, user_id, device_id,
                                                     content, content_hash, needs_review)
                        VALUES (:id, :recipeId, :parentId, :origin, :runId, :captureId, :userId, :deviceId,
                                CAST(:content AS jsonb), :hash, :needsReview)""")
                .param("id", revision.id())
                .param("recipeId", revision.recipeId())
                .param("parentId", revision.parentId())
                .param("origin", revision.origin().name())
                .param("runId", revision.runId())
                .param("captureId", revision.captureId())
                .param("userId", revision.userId())
                .param("deviceId", revision.deviceId())
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
     * Points the head to a revision and refreshes the copied search/display columns. Source summary and thumbnail
     * change only when the revision comes from a capture (a user edit keeps them).
     */
    public void moveHead(UUID recipeId, UUID revisionId, JsonNode content, UUID captureId) {
        jdbc.sql("""
                        UPDATE recipe SET head_revision_id = :revisionId, title = :title, ingredients_text = :ingredients,
                               source_summary = CASE WHEN CAST(:captureId AS uuid) IS NULL THEN source_summary ELSE (
                                   SELECT coalesce(nullif(c.body->'source'->>'siteName', ''),
                                                   substring(c.source_url from '^[a-z]+://(?:www\\.)?([^/:?#]+)'),
                                                   lower(c.source_type))
                                   FROM capture c WHERE c.id = :captureId) END,
                               thumbnail_blob = CASE WHEN CAST(:captureId AS uuid) IS NULL THEN thumbnail_blob ELSE (
                                   SELECT a.blob_sha256 FROM capture_attachment a
                                   WHERE a.capture_id = :captureId AND a.role IN ('image', 'screenshot')
                                   ORDER BY a.name LIMIT 1) END,
                               updated_at = now()
                        WHERE id = :recipeId""")
                .param("recipeId", recipeId)
                .param("revisionId", revisionId)
                .param("title", RecipeContent.title(content))
                .param("ingredients", RecipeContent.ingredientsText(content))
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
