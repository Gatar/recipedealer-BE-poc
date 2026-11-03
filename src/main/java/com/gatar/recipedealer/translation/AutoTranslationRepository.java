package com.gatar.recipedealer.translation;

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

import tools.jackson.databind.JsonNode;

/**
 * Translations ({@code auto_translation}): requested when a capture uploaded with {@code Translate-To} makes a machine
 * revision in another language the head of a recipe (a new recipe, an UPDATE decision, a re-read), done by
 * {@link AutoTranslator} in a job of the capture. A capture with a pending translation stays {@code processing}.
 */
@Component
public class AutoTranslationRepository {

    public enum Status { PENDING, SUCCEEDED, SKIPPED, FAILED }

    /** @param tagAfter a new recipe: tag it once translated */
    public record Pending(UUID id, UUID recipeId, String targetLanguage, boolean tagAfter) {
    }

    /**
     * @param usage null when no call was made
     */
    public record Outcome(Status status, String sourceLanguage, UUID revisionId, String promptVersion, String errorType,
                          String error, LlmUsage usage, BigDecimal cost) {
    }

    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final AutoTranslationProperties properties;
    private final LlmProperties llm;

    public AutoTranslationRepository(JdbcClient jdbc, JobQueue jobs, AutoTranslationProperties properties,
                                     LlmProperties llm) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.properties = properties;
        this.llm = llm;
    }

    /**
     * Requests translation of the head the capture just made, when the capture asked for a language and the content
     * is known to be in another one, and queues the capture's translation job. Content already in the target language
     * (a translation included) or without a language is never translated: a model can only spoil it. Runs in the
     * transaction that holds the capture's state lock.
     *
     * @param recipe   the content that became the head
     * @param tagAfter a new recipe to tag once translated
     * @return whether a translation was requested
     */
    public boolean request(UUID recipeId, UUID captureId, JsonNode recipe, boolean tagAfter) {
        if (!properties.enabled() || !llm.enabled()) {
            return false;
        }
        String target = jdbc.sql("SELECT translate_to FROM capture WHERE id = :id").param("id", captureId)
                .query(String.class).optional().orElse(null);
        String language = recipe.path("language").asString("").strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        if (target == null || language.isEmpty() || target.equals(language)) {
            return false;
        }
        int inserted = jdbc.sql("""
                        INSERT INTO auto_translation (id, recipe_id, capture_id, target_language, tag_after)
                        VALUES (:id, :recipeId, :captureId, :target, :tagAfter)
                        ON CONFLICT (recipe_id) WHERE status = 'pending' DO NOTHING""")
                .param("id", Ids.newId()).param("recipeId", recipeId).param("captureId", captureId)
                .param("target", target).param("tagAfter", tagAfter)
                .update();
        if (inserted > 0) {
            jobs.enqueueOnce(new ProcessingTask(captureId, AutoTranslator.STEP, null, Purpose.PIPELINE));
        }
        return inserted > 0;
    }

    public boolean hasPending(UUID captureId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auto_translation WHERE capture_id = :id AND status = 'pending')")
                .param("id", captureId).query(Boolean.class).single();
    }

    public List<Pending> pending(UUID captureId) {
        return jdbc.sql("""
                        SELECT id, recipe_id, target_language, tag_after FROM auto_translation
                        WHERE capture_id = :id AND status = 'pending' ORDER BY id""")
                .param("id", captureId)
                .query((rs, n) -> new Pending(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "recipe_id"),
                        rs.getString("target_language"), rs.getBoolean("tag_after")))
                .list();
    }

    public void finish(UUID id, Outcome outcome) {
        LlmUsage usage = outcome.usage();
        String error = outcome.error() == null || outcome.error().length() <= 500
                ? outcome.error() : outcome.error().substring(0, 500);
        jdbc.sql("""
                        UPDATE auto_translation
                        SET status = :status, source_language = :sourceLanguage, revision_id = :revisionId,
                            prompt_version = :promptVersion, error_type = :errorType, error = :error, model = :model,
                            stop_reason = :stopReason, input_tokens = :input, cache_creation_input_tokens = :cacheWrite,
                            cache_read_input_tokens = :cacheRead, output_tokens = :output, latency_ms = :latency,
                            cost_usd = :cost, finished_at = now()
                        WHERE id = :id AND status = 'pending'""")
                .param("id", id)
                .param("status", outcome.status().name().toLowerCase(Locale.ROOT))
                .param("sourceLanguage", outcome.sourceLanguage())
                .param("revisionId", outcome.revisionId())
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
