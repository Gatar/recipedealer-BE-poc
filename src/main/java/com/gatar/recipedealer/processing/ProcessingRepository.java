package com.gatar.recipedealer.processing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.recipe.RecipeKey;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class ProcessingRepository {

    private static final TypeReference<Map<String, String>> PROVENANCE = new TypeReference<>() {
    };
    private static final TypeReference<List<RecipeKey>> KEYS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ProcessingRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public enum RunStatus { RUNNING, SUCCEEDED, AWAITING_EXTRACTION, FAILED }

    public record Run(UUID id, RunStatus status) {
    }

    /** Locks the capture's state row; serializes processing and resolutions of one capture. */
    public CaptureStatus lockState(UUID captureId) {
        return jdbc.sql("SELECT status FROM capture_state WHERE capture_id = :id FOR UPDATE")
                .param("id", captureId)
                .query(String.class)
                .optional()
                .map(CaptureStatus::of)
                .orElseThrow(() -> new NotFoundException("capture " + captureId + " not found"));
    }

    public void setState(UUID captureId, CaptureStatus status, String reason, String message) {
        jdbc.sql("UPDATE capture_state SET status = :status, reason = :reason, message = :message, updated_at = now() WHERE capture_id = :id")
                .param("id", captureId)
                .param("status", status.value())
                .param("reason", reason)
                .param("message", message)
                .update();
    }

    public UUID startRun(UUID captureId, RecipeExtractor extractor) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO processing_run (id, capture_id, extractor_name, extractor_version, status)
                        VALUES (:id, :captureId, :name, :version, 'running')""")
                .param("id", id)
                .param("captureId", captureId)
                .param("name", extractor.name())
                .param("version", extractor.version())
                .update();
        return id;
    }

    public void finishRun(UUID runId, RunStatus status, String error) {
        jdbc.sql("UPDATE processing_run SET status = :status, error = :error, finished_at = now() WHERE id = :id")
                .param("id", runId)
                .param("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .param("error", error)
                .update();
    }

    public void insertCandidate(UUID runId, StoredCandidate candidate) {
        jdbc.sql("""
                        INSERT INTO recipe_candidate (run_id, idx, content, content_hash, provenance, needs_review, keys)
                        VALUES (:runId, :idx, CAST(:content AS jsonb), :hash, CAST(:provenance AS jsonb), :needsReview,
                                CAST(:keys AS jsonb))""")
                .param("runId", runId)
                .param("idx", candidate.index())
                .param("content", jsonMapper.writeValueAsString(candidate.content()))
                .param("hash", candidate.contentHash())
                .param("provenance", jsonMapper.writeValueAsString(candidate.provenance()))
                .param("needsReview", candidate.needsReview())
                .param("keys", jsonMapper.writeValueAsString(candidate.keys()))
                .update();
    }

    /** The newest run that produced a result (candidates), if any. */
    public Optional<Run> latestCompletedRun(UUID captureId) {
        return jdbc.sql("""
                        SELECT id, status FROM processing_run
                        WHERE capture_id = :id AND status IN ('succeeded', 'awaiting_extraction')
                        ORDER BY started_at DESC, id DESC LIMIT 1""")
                .param("id", captureId)
                .query((rs, n) -> new Run(rs.getObject("id", UUID.class),
                        RunStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT))))
                .optional();
    }

    public List<StoredCandidate> candidates(UUID runId) {
        return jdbc.sql("SELECT idx, content, content_hash, provenance, needs_review, keys FROM recipe_candidate WHERE run_id = :id ORDER BY idx")
                .param("id", runId)
                .query((rs, n) -> new StoredCandidate(rs.getInt("idx"),
                        (ObjectNode) jsonMapper.readTree(rs.getString("content")), rs.getString("content_hash"),
                        jsonMapper.readValue(rs.getString("provenance"), PROVENANCE), rs.getBoolean("needs_review"),
                        jsonMapper.readValue(rs.getString("keys"), KEYS)))
                .list();
    }
}
