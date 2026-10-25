package com.gatar.recipedealer.recipe;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.gatar.recipedealer.common.Jdbc;

@Repository
public class ResolutionRepository {

    private final JdbcClient jdbc;

    public ResolutionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Action { NEW, UPDATE }

    /** {@code tags}: tag ids chosen with a NEW decision. */
    public record Resolution(UUID captureId, int index, Action action, UUID targetRecipeId, List<String> tags, UUID userId,
                             UUID deviceId, boolean applied, UUID appliedRecipeId, String message) {
    }

    public Optional<Resolution> find(UUID captureId, int index) {
        return jdbc.sql("SELECT * FROM capture_resolution WHERE capture_id = :captureId AND idx = :idx")
                .param("captureId", captureId)
                .param("idx", index)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public List<Resolution> forCapture(UUID captureId) {
        return jdbc.sql("SELECT * FROM capture_resolution WHERE capture_id = :captureId ORDER BY idx")
                .param("captureId", captureId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public void upsertPending(UUID captureId, int index, Action action, UUID targetRecipeId, List<String> tags,
                              UUID userId, UUID deviceId) {
        jdbc.sql("""
                        INSERT INTO capture_resolution (capture_id, idx, action, target_recipe_id, tags, user_id, device_id)
                        VALUES (:captureId, :idx, :action, :target, string_to_array(:tags, ','), :userId, :deviceId)
                        ON CONFLICT (capture_id, idx) DO UPDATE
                            SET action = EXCLUDED.action, target_recipe_id = EXCLUDED.target_recipe_id, tags = EXCLUDED.tags,
                                user_id = EXCLUDED.user_id, device_id = EXCLUDED.device_id, requested_at = now(),
                                message = NULL
                            WHERE capture_resolution.applied_at IS NULL""")
                .param("captureId", captureId)
                .param("idx", index)
                .param("action", action.name())
                .param("target", targetRecipeId)
                .param("tags", String.join(",", tags))
                .param("userId", userId)
                .param("deviceId", deviceId)
                .update();
    }

    public void markApplied(UUID captureId, int index, UUID runId, UUID recipeId, UUID revisionId, String message) {
        jdbc.sql("""
                        UPDATE capture_resolution SET applied_at = now(), applied_run_id = :runId, applied_recipe_id = :recipeId,
                               applied_revision_id = :revisionId, message = :message
                        WHERE capture_id = :captureId AND idx = :idx""")
                .param("captureId", captureId)
                .param("idx", index)
                .param("runId", runId)
                .param("recipeId", recipeId)
                .param("revisionId", revisionId)
                .param("message", message)
                .update();
    }

    public void delete(UUID captureId, int index) {
        jdbc.sql("DELETE FROM capture_resolution WHERE capture_id = :captureId AND idx = :idx AND applied_at IS NULL")
                .param("captureId", captureId)
                .param("idx", index)
                .update();
    }

    private static Resolution map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Resolution(Jdbc.uuid(rs, "capture_id"), rs.getInt("idx"), Action.valueOf(rs.getString("action")),
                Jdbc.uuid(rs, "target_recipe_id"), List.of((String[]) rs.getArray("tags").getArray()),
                Jdbc.uuid(rs, "user_id"), Jdbc.uuid(rs, "device_id"),
                rs.getObject("applied_at") != null, Jdbc.uuid(rs, "applied_recipe_id"), rs.getString("message"));
    }
}
