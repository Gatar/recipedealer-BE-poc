package com.gatar.recipedealer.processing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.llm.LlmUsage;
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

    /** {@code REJECTED}: finished but failed validation; its candidates are kept and not applied (unless promoted). */
    public enum RunStatus { RUNNING, SUCCEEDED, AWAITING_EXTRACTION, REJECTED, FAILED }

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

    public UUID startRun(ProcessingTask task, RecipeExtractor extractor) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO processing_run (id, capture_id, extractor_name, extractor_version, status, purpose, step,
                                                    cascade_id)
                        VALUES (:id, :captureId, :name, :version, 'running', :purpose, :step, :cascadeId)""")
                .param("id", id)
                .param("captureId", task.captureId())
                .param("name", extractor.name())
                .param("version", extractor.version())
                .param("purpose", task.purpose().value())
                .param("step", task.step())
                .param("cascadeId", task.cascadeId())
                .update();
        return id;
    }

    /** A run that could not finish (exception); {@code errorType}: e.g. the API error type. */
    public void failRun(UUID runId, String error, String errorType) {
        jdbc.sql("UPDATE processing_run SET status = 'failed', error = :error, error_type = :errorType, finished_at = now() WHERE id = :id")
                .param("id", runId)
                .param("error", error)
                .param("errorType", errorType)
                .update();
    }

    /** {@code usage} and {@code cost}: null for local extractors. */
    public void finishRun(UUID runId, RunStatus status, List<String> validationErrors, LlmUsage usage, BigDecimal cost) {
        jdbc.sql("""
                        UPDATE processing_run SET status = :status, finished_at = now(),
                               validation_errors = string_to_array(:errors, '|'), model = :model, stop_reason = :stopReason,
                               input_tokens = :input, cache_creation_input_tokens = :cacheWrite,
                               cache_read_input_tokens = :cacheRead, output_tokens = :output, latency_ms = :latency,
                               cost_usd = :cost
                        WHERE id = :id""")
                .param("id", runId)
                .param("status", status.name().toLowerCase(Locale.ROOT))
                .param("errors", validationErrors.isEmpty() ? null : String.join("|", validationErrors))
                .param("model", usage == null ? null : usage.model())
                .param("stopReason", usage == null ? null : usage.stopReason())
                .param("input", usage == null ? null : usage.inputTokens())
                .param("cacheWrite", usage == null ? null : usage.cacheCreationInputTokens())
                .param("cacheRead", usage == null ? null : usage.cacheReadInputTokens())
                .param("output", usage == null ? null : usage.outputTokens())
                .param("latency", usage == null ? null : usage.latencyMs())
                .param("cost", cost)
                .update();
    }

    /** Records what an LLM run found about the dish photo (the cut-out blob is already in the blob store). */
    public void insertDishPhoto(UUID runId, UUID captureId, DishPhotoFinding finding) {
        DishPhotoFinding.Photo photo = finding.photo();
        if (photo != null) {
            jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                    .param("sha256", photo.blobSha256())
                    .param("size", photo.sizeBytes())
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO dish_photo (run_id, capture_id, attachment_name, blob_sha256, x, y, width, height)
                        VALUES (:runId, :captureId, :name, :sha256, :x, :y, :width, :height)""")
                .param("runId", runId)
                .param("captureId", captureId)
                .param("name", photo == null ? null : photo.attachmentName())
                .param("sha256", photo == null ? null : photo.blobSha256())
                .param("x", photo == null ? null : photo.x())
                .param("y", photo == null ? null : photo.y())
                .param("width", photo == null ? null : photo.width())
                .param("height", photo == null ? null : photo.height())
                .update();
    }

    /** The fallback chose a rejected run as the cascade's result. */
    public void promote(UUID runId) {
        jdbc.sql("UPDATE processing_run SET status = 'succeeded' WHERE id = :id AND status = 'rejected'")
                .param("id", runId)
                .update();
    }

    /** Rejected runs of a cascade, oldest first. */
    public List<UUID> rejectedRuns(UUID cascadeId) {
        return jdbc.sql("""
                        SELECT id FROM processing_run WHERE cascade_id = :id AND status = 'rejected'
                        ORDER BY started_at, id""")
                .param("id", cascadeId)
                .query(UUID.class)
                .list();
    }

    public void insertCandidate(UUID runId, StoredCandidate candidate) {
        jdbc.sql("""
                        INSERT INTO recipe_candidate (run_id, idx, schema_version, content, content_hash, provenance,
                                                      needs_review, keys)
                        VALUES (:runId, :idx, :schemaVersion, CAST(:content AS jsonb), :hash, CAST(:provenance AS jsonb),
                                :needsReview, CAST(:keys AS jsonb))""")
                .param("runId", runId)
                .param("idx", candidate.index())
                .param("schemaVersion", candidate.schemaVersion())
                .param("content", jsonMapper.writeValueAsString(candidate.content()))
                .param("hash", candidate.contentHash())
                .param("provenance", jsonMapper.writeValueAsString(candidate.provenance()))
                .param("needsReview", candidate.needsReview())
                .param("keys", jsonMapper.writeValueAsString(candidate.keys()))
                .update();
    }

    /**
     * The newest run that produced the capture's current result (candidates), if any. Rejected cascade steps and
     * comparison runs are never the current result.
     */
    public Optional<Run> latestCompletedRun(UUID captureId) {
        return jdbc.sql("""
                        SELECT id, status FROM processing_run
                        WHERE capture_id = :id AND status IN ('succeeded', 'awaiting_extraction') AND purpose <> 'comparison'
                        ORDER BY finished_at DESC, started_at DESC, id DESC LIMIT 1""")
                .param("id", captureId)
                .query((rs, n) -> new Run(rs.getObject("id", UUID.class),
                        RunStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT))))
                .optional();
    }

    public List<StoredCandidate> candidates(UUID runId) {
        return jdbc.sql("""
                        SELECT idx, schema_version, content, content_hash, provenance, needs_review, keys
                        FROM recipe_candidate WHERE run_id = :id ORDER BY idx""")
                .param("id", runId)
                .query((rs, n) -> new StoredCandidate(rs.getInt("idx"), rs.getInt("schema_version"),
                        (ObjectNode) jsonMapper.readTree(rs.getString("content")), rs.getString("content_hash"),
                        jsonMapper.readValue(rs.getString("provenance"), PROVENANCE), rs.getBoolean("needs_review"),
                        jsonMapper.readValue(rs.getString("keys"), KEYS)))
                .list();
    }
}
