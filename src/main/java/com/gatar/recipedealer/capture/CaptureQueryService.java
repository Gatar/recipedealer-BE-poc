package com.gatar.recipedealer.capture;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.capture.CaptureView.CandidateSummary;
import com.gatar.recipedealer.capture.CaptureView.RecipeRef;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.processing.ProcessingRepository;
import com.gatar.recipedealer.recipe.RecipeContent;
import com.gatar.recipedealer.recipe.RecipeKey;
import com.gatar.recipedealer.recipe.ResolutionRepository;
import com.gatar.recipedealer.recipe.ResolutionRepository.Resolution;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

@Service
@Transactional(readOnly = true)
public class CaptureQueryService {

    public static final int INBOX_LIMIT = 200;

    private final JdbcClient jdbc;
    private final ProcessingRepository processing;
    private final ResolutionRepository resolutions;

    public CaptureQueryService(JdbcClient jdbc, ProcessingRepository processing, ResolutionRepository resolutions) {
        this.jdbc = jdbc;
        this.processing = processing;
        this.resolutions = resolutions;
    }

    private record Row(UUID captureId, CaptureStatus status, String reason, String message, java.time.Instant receivedAt,
                       java.time.Instant capturedAt, String sourceType, String sourceUrl, String title) {
    }

    public CaptureView view(UUID captureId) {
        return rows("c.id = :id", Map.of("id", captureId)).stream().findFirst()
                .map(this::toView)
                .orElseThrow(() -> new NotFoundException("capture " + captureId + " not found"));
    }

    /** Captures in the given states, newest first. */
    public List<CaptureView> inbox(Set<CaptureStatus> statuses) {
        if (statuses.isEmpty()) {
            throw ApiException.badRequest("status must not be empty");
        }
        var values = statuses.stream().map(CaptureStatus::value).toList();
        return rows("s.status IN (:statuses)", Map.of("statuses", values)).stream().map(this::toView).toList();
    }

    private List<Row> rows(String condition, Map<String, ?> params) {
        return jdbc.sql("""
                        SELECT c.id, s.status, s.reason, s.message, c.received_at, c.captured_at, c.source_type, c.source_url,
                               nullif(btrim(c.body->'recipe'->>'title'), '') AS title
                        FROM capture c JOIN capture_state s ON s.capture_id = c.id
                        WHERE %s
                        ORDER BY c.received_at DESC
                        LIMIT %d""".formatted(condition, INBOX_LIMIT))
                .params(params)
                .query((rs, n) -> new Row(Jdbc.uuid(rs, "id"), CaptureStatus.of(rs.getString("status")),
                        rs.getString("reason"), rs.getString("message"), Jdbc.instant(rs, "received_at"),
                        Jdbc.instant(rs, "captured_at"), rs.getString("source_type"), rs.getString("source_url"),
                        rs.getString("title")))
                .list();
    }

    private CaptureView toView(Row row) {
        List<Resolution> decisions = resolutions.forCapture(row.captureId());
        List<RecipeRef> recipes = jdbc.sql("""
                        SELECT r.id, coalesce(r.title, '') AS title
                        FROM capture_resolution cr JOIN recipe r ON r.id = cr.applied_recipe_id
                        WHERE cr.capture_id = :id ORDER BY cr.idx""")
                .param("id", row.captureId())
                .query((rs, n) -> new RecipeRef(Jdbc.uuid(rs, "id"), rs.getString("title")))
                .list();
        List<CandidateSummary> candidates = row.status() == CaptureStatus.NEEDS_DECISION
                ? candidates(row.captureId(), decisions) : null;
        return new CaptureView(row.captureId(), row.status().value(), row.reason(), row.message(), recipes, candidates,
                row.receivedAt(), row.capturedAt(), row.sourceType(), row.sourceUrl(), row.title());
    }

    private List<CandidateSummary> candidates(UUID captureId, List<Resolution> decisions) {
        Map<Integer, Resolution> byIndex = decisions.stream().collect(Collectors.toMap(Resolution::index, Function.identity()));
        return processing.latestCompletedRun(captureId)
                .map(run -> processing.candidates(run.id()).stream().map(c -> {
                    Resolution decision = byIndex.get(c.index());
                    List<String> urls = c.keys().stream().filter(k -> k.kind() == RecipeKey.Kind.CANONICAL_URL)
                            .map(RecipeKey::value).toList();
                    return new CandidateSummary(c.index(), RecipeContent.title(c.content()),
                            RecipeContent.ingredientLines(c.content()).size(), RecipeContent.stepLines(c.content()).size(),
                            c.needsReview(), urls, decision == null ? null : decision.action().name(),
                            decision == null ? null : decision.targetRecipeId());
                }).toList())
                .orElse(List.of());
    }
}
