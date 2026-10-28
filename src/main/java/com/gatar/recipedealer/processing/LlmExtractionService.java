package com.gatar.recipedealer.processing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

/**
 * Starting LLM extraction outside normal processing: a device forcing it for one capture, the admin reprocessing
 * captures, and admin model comparisons (never applied).
 */
@Service
public class LlmExtractionService {

    public static final int MAX_CAPTURES = 200;

    private final ExtractionCascade cascade;
    private final CaptureLoader loader;
    private final ProcessingRepository processing;
    private final JobQueue jobs;
    private final JdbcClient jdbc;

    public LlmExtractionService(ExtractionCascade cascade, CaptureLoader loader, ProcessingRepository processing,
                                JobQueue jobs, JdbcClient jdbc) {
        this.cascade = cascade;
        this.loader = loader;
        this.processing = processing;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    /**
     * {@code POST /v1/captures/{captureId}/llm-extraction}: the cascade from the first LLM step; its result takes the
     * normal path (an applied capture updates its recipe, never moving a USER head; a stored decision is applied).
     */
    @Transactional
    public void force(UUID captureId) {
        processing.lockState(captureId);
        requireEnabled();
        String problem = problem(captureId);
        if (problem != null) {
            throw ApiException.conflict(problem, message(problem));
        }
        enqueueForced(captureId);
    }

    public record Skipped(UUID captureId, String error, String message) {
    }

    public record Reprocessing(List<UUID> queued, List<Skipped> skipped) {
    }

    /** Admin: forced cascades for the given captures, or for every capture whose state has {@code reason}. */
    @Transactional
    public Reprocessing reprocess(List<UUID> captureIds, String reason) {
        requireEnabled();
        if ((captureIds == null || captureIds.isEmpty()) == (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("give either captureIds or reason");
        }
        List<UUID> ids = reason != null && !reason.isBlank()
                ? jdbc.sql("SELECT capture_id FROM capture_state WHERE reason = :reason ORDER BY updated_at")
                        .param("reason", reason).query(UUID.class).list()
                : List.copyOf(new LinkedHashSet<>(captureIds));
        if (ids.size() > MAX_CAPTURES) {
            throw ApiException.badRequest("at most " + MAX_CAPTURES + " captures at once");
        }
        List<UUID> queued = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (UUID id : ids) {
            try {
                processing.lockState(id);
            } catch (NotFoundException e) {
                skipped.add(new Skipped(id, "not_found", e.getMessage()));
                continue;
            }
            String problem = problem(id);
            if (problem != null) {
                skipped.add(new Skipped(id, problem, message(problem)));
            } else {
                enqueueForced(id);
                queued.add(id);
            }
        }
        return new Reprocessing(queued, skipped);
    }

    public record QueuedComparison(UUID captureId, String step, UUID jobId) {
    }

    /** Admin: comparison runs of the given LLM steps; stored for the report, never applied. */
    @Transactional
    public List<QueuedComparison> compare(List<UUID> captureIds, List<String> steps) {
        requireEnabled();
        if (captureIds == null || captureIds.isEmpty() || steps == null || steps.isEmpty()) {
            throw ApiException.badRequest("captureIds and steps must not be empty");
        }
        if (captureIds.size() > MAX_CAPTURES) {
            throw ApiException.badRequest("at most " + MAX_CAPTURES + " captures at once");
        }
        List<String> known = cascade.llmStepNames();
        for (String step : steps) {
            if (!known.contains(step)) {
                throw ApiException.badRequest("unknown step '" + step + "'; LLM steps are " + known);
            }
        }
        List<QueuedComparison> queued = new ArrayList<>();
        for (UUID captureId : new LinkedHashSet<>(captureIds)) {
            processing.lockState(captureId);
            for (String step : new LinkedHashSet<>(steps)) {
                UUID jobId = jobs.enqueue(new ProcessingTask(captureId, step, Ids.newId(), Purpose.COMPARISON));
                queued.add(new QueuedComparison(captureId, step, jobId));
            }
        }
        return queued;
    }

    private void requireEnabled() {
        if (!cascade.llmEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "llm_unavailable",
                    "automatic extraction is not configured on this server");
        }
    }

    /** Why a forced cascade cannot start, or null. The capture's state row must be locked. */
    private String problem(UUID captureId) {
        if (jobs.hasActive(captureId)) {
            return "extraction_in_progress";
        }
        try {
            return cascade.hasLlmInput(loader.load(captureId)) ? null : "no_llm_input";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String message(String problem) {
        return switch (problem) {
            case "extraction_in_progress" -> "this capture is already being processed";
            case "no_llm_input" -> "this capture has no attachments or text for automatic extraction";
            default -> problem;
        };
    }

    private void enqueueForced(UUID captureId) {
        jobs.enqueue(new ProcessingTask(captureId, cascade.firstLlmStep(), Ids.newId(), Purpose.FORCED));
        processing.setState(captureId, CaptureStatus.PROCESSING, null, null);
    }
}
