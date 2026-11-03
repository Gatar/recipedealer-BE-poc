package com.gatar.recipedealer.tag;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.llm.LlmProperties;
import com.gatar.recipedealer.llm.LlmUsage;
import com.gatar.recipedealer.processing.JobQueue;
import com.gatar.recipedealer.processing.ProcessingTask;
import com.gatar.recipedealer.processing.Purpose;

/**
 * Taggings of new recipes ({@code auto_tagging}): requested when a NEW decision without tags creates a recipe, done by
 * {@link AutoTagger} in a job of the capture. A capture with a pending tagging stays {@code processing}.
 */
@Component
public class AutoTagRepository {

    public enum Status { PENDING, SUCCEEDED, SKIPPED, FAILED }

    public record Pending(UUID id, UUID recipeId) {
    }

    /**
     * @param tags  the model's choice (null when no answer)
     * @param usage null when no call was made
     */
    public record Outcome(Status status, List<String> tags, String promptVersion, String errorType, String error,
                          LlmUsage usage, BigDecimal cost) {
    }

    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final AutoTagProperties properties;
    private final LlmProperties llm;

    public AutoTagRepository(JdbcClient jdbc, JobQueue jobs, AutoTagProperties properties, LlmProperties llm) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.properties = properties;
        this.llm = llm;
    }

    /**
     * Requests tagging of a recipe the capture just created, and queues the capture's tagging job. Nothing happens
     * when tagging or the LLM is disabled, or the catalog has no tags. Runs in the transaction that holds the
     * capture's state lock.
     */
    public void request(UUID recipeId, UUID captureId) {
        if (!properties.enabled() || !llm.enabled()
                || !jdbc.sql("SELECT EXISTS (SELECT 1 FROM tag)").query(Boolean.class).single()) {
            return;
        }
        int inserted = jdbc.sql("""
                        INSERT INTO auto_tagging (id, recipe_id, capture_id) VALUES (:id, :recipeId, :captureId)
                        ON CONFLICT (recipe_id) DO NOTHING""")
                .param("id", Ids.newId()).param("recipeId", recipeId).param("captureId", captureId)
                .update();
        if (inserted > 0) {
            jobs.enqueueOnce(new ProcessingTask(captureId, AutoTagger.STEP, null, Purpose.PIPELINE));
        }
    }

    public boolean hasPending(UUID captureId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auto_tagging WHERE capture_id = :id AND status = 'pending')")
                .param("id", captureId).query(Boolean.class).single();
    }

    public List<Pending> pending(UUID captureId) {
        return jdbc.sql("SELECT id, recipe_id FROM auto_tagging WHERE capture_id = :id AND status = 'pending' ORDER BY id")
                .param("id", captureId)
                .query((rs, n) -> new Pending(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "recipe_id")))
                .list();
    }

    public void finish(UUID id, Outcome outcome) {
        LlmUsage usage = outcome.usage();
        String error = outcome.error() == null || outcome.error().length() <= 500
                ? outcome.error() : outcome.error().substring(0, 500);
        jdbc.sql("""
                        UPDATE auto_tagging
                        SET status = :status, tags = string_to_array(:tags, ','), prompt_version = :promptVersion, error_type = :errorType,
                            error = :error, model = :model, stop_reason = :stopReason, input_tokens = :input,
                            cache_creation_input_tokens = :cacheWrite, cache_read_input_tokens = :cacheRead,
                            output_tokens = :output, latency_ms = :latency, cost_usd = :cost, finished_at = now()
                        WHERE id = :id AND status = 'pending'""")
                .param("id", id)
                .param("status", outcome.status().name().toLowerCase(Locale.ROOT))
                .param("tags", outcome.tags() == null ? null : String.join(",", outcome.tags()))
                .param("promptVersion", outcome.promptVersion())
                .param("errorType", outcome.errorType())
                .param("error", error)
                .param("model", usage == null ? null : usage.model())
                .param("stopReason", usage == null ? null : usage.stopReason())
                .param("input", usage == null ? null : usage.inputTokens())
                .param("cacheWrite", usage == null ? null : usage.cacheCreationInputTokens())
                .param("cacheRead", usage == null ? null : usage.cacheReadInputTokens())
                .param("output", usage == null ? null : usage.outputTokens())
                .param("latency", usage == null ? null : usage.latencyMs())
                .param("cost", outcome.cost())
                .update();
    }
}
