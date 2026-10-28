package com.gatar.recipedealer.processing;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.node.ObjectNode;

/**
 * Extraction usage read from the per-run rows (the only source of truth: no counters). A recipe's runs are all runs of
 * the captures linked to it (revisions' {@code capture_id}, applied resolutions), including rejected cascade steps,
 * forced runs and comparison runs.
 */
@Service
@Transactional(readOnly = true)
public class ExtractionStatsService {

    private final JdbcClient jdbc;
    private final ProcessingRepository processing;

    public ExtractionStatsService(JdbcClient jdbc, ProcessingRepository processing) {
        this.jdbc = jdbc;
        this.processing = processing;
    }

    /**
     * @param applied the run's content became a revision of the recipe (stats) or of any recipe (comparison report)
     */
    public record RunStats(UUID runId, UUID captureId, Instant startedAt, Instant finishedAt, String extractor,
                           String extractorVersion, String step, String purpose, String model, String status,
                           boolean applied, List<String> validationErrors, String errorType, String stopReason,
                           long inputTokens, long cacheCreationInputTokens, long cacheReadInputTokens, long outputTokens,
                           Long latencyMs, BigDecimal costUsd) {
    }

    public record Totals(int runs, int llmRuns, long inputTokens, long cacheCreationInputTokens,
                         long cacheReadInputTokens, long outputTokens, BigDecimal costUsd) {
    }

    public record ModelStats(String model, int runs, long inputTokens, long cacheCreationInputTokens,
                             long cacheReadInputTokens, long outputTokens, BigDecimal costUsd) {
    }

    public record RecipeStats(UUID recipeId, Totals totals, List<ModelStats> models, List<RunStats> runs) {
    }

    /** {@code GET /v1/recipes/{recipeId}/extraction-stats}. */
    public RecipeStats forRecipe(UUID recipeId) {
        boolean live = jdbc.sql("SELECT deleted_at IS NULL AND merged_into IS NULL FROM recipe WHERE id = :id")
                .param("id", recipeId).query(Boolean.class).optional().orElse(false);
        if (!live) {
            throw new NotFoundException("recipe " + recipeId + " not found");
        }
        List<RunStats> runs = jdbc.sql("""
                        WITH captures AS (
                            SELECT capture_id FROM recipe_revision WHERE recipe_id = :recipeId AND capture_id IS NOT NULL
                            UNION
                            SELECT capture_id FROM capture_resolution WHERE applied_recipe_id = :recipeId)
                        SELECT r.*, EXISTS (SELECT 1 FROM recipe_revision v WHERE v.run_id = r.id AND v.recipe_id = :recipeId)
                                    AS applied
                        FROM processing_run r
                        WHERE r.capture_id IN (SELECT capture_id FROM captures)
                        ORDER BY r.started_at, r.id""")
                .param("recipeId", recipeId)
                .query((rs, n) -> run(rs))
                .list();

        Map<String, ModelStats> models = new LinkedHashMap<>();
        for (RunStats run : runs) {
            if (run.model() != null) {
                models.merge(run.model(), new ModelStats(run.model(), 1, run.inputTokens(), run.cacheCreationInputTokens(),
                        run.cacheReadInputTokens(), run.outputTokens(), cost(run.costUsd())), ExtractionStatsService::add);
            }
        }
        var totals = new Totals(runs.size(), (int) runs.stream().filter(r -> r.model() != null).count(),
                runs.stream().mapToLong(RunStats::inputTokens).sum(),
                runs.stream().mapToLong(RunStats::cacheCreationInputTokens).sum(),
                runs.stream().mapToLong(RunStats::cacheReadInputTokens).sum(),
                runs.stream().mapToLong(RunStats::outputTokens).sum(),
                runs.stream().map(r -> cost(r.costUsd())).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new RecipeStats(recipeId, totals, List.copyOf(models.values()), runs);
    }

    /** @param cacheHit the prompt prefix was read from the cache */
    public record ComparisonRun(RunStats run, boolean cacheHit, List<ObjectNode> candidates) {
    }

    public record CaptureComparison(UUID captureId, String sourceType, String status, String reason,
                                    List<ComparisonRun> runs) {
    }

    /** {@code GET /v1/admin/llm-comparisons}: every run of each capture side by side, with what it extracted. */
    public List<CaptureComparison> comparison(List<UUID> captureIds) {
        List<CaptureComparison> result = new ArrayList<>();
        for (UUID captureId : captureIds) {
            var capture = jdbc.sql("""
                            SELECT c.source_type, s.status, s.reason FROM capture c JOIN capture_state s ON s.capture_id = c.id
                            WHERE c.id = :id""")
                    .param("id", captureId)
                    .query((rs, n) -> new String[] {rs.getString("source_type"), rs.getString("status"), rs.getString("reason")})
                    .optional()
                    .orElseThrow(() -> new NotFoundException("capture " + captureId + " not found"));
            List<ComparisonRun> runs = jdbc.sql("""
                            SELECT r.*, EXISTS (SELECT 1 FROM recipe_revision v WHERE v.run_id = r.id) AS applied
                            FROM processing_run r WHERE r.capture_id = :id ORDER BY r.started_at, r.id""")
                    .param("id", captureId)
                    .query((rs, n) -> run(rs))
                    .list().stream()
                    .map(run -> new ComparisonRun(run, run.cacheReadInputTokens() > 0,
                            processing.candidates(run.runId()).stream().map(StoredCandidate::content).toList()))
                    .toList();
            result.add(new CaptureComparison(captureId, capture[0], capture[1], capture[2], runs));
        }
        return result;
    }

    private static RunStats run(ResultSet rs) throws SQLException {
        return new RunStats(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "capture_id"), Jdbc.instant(rs, "started_at"),
                Jdbc.instant(rs, "finished_at"), rs.getString("extractor_name"), rs.getString("extractor_version"),
                rs.getString("step"), rs.getString("purpose"), rs.getString("model"), rs.getString("status"),
                rs.getBoolean("applied"), strings(rs.getArray("validation_errors")), rs.getString("error_type"),
                rs.getString("stop_reason"), rs.getLong("input_tokens"), rs.getLong("cache_creation_input_tokens"),
                rs.getLong("cache_read_input_tokens"), rs.getLong("output_tokens"),
                rs.getObject("latency_ms", Long.class), rs.getBigDecimal("cost_usd"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static BigDecimal cost(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static ModelStats add(ModelStats a, ModelStats b) {
        return new ModelStats(a.model(), a.runs() + b.runs(), a.inputTokens() + b.inputTokens(),
                a.cacheCreationInputTokens() + b.cacheCreationInputTokens(),
                a.cacheReadInputTokens() + b.cacheReadInputTokens(), a.outputTokens() + b.outputTokens(),
                a.costUsd().add(b.costUsd()));
    }
}
