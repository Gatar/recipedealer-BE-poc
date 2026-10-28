package com.gatar.recipedealer.processing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.llm.LlmException;
import com.gatar.recipedealer.llm.LlmPricing;
import com.gatar.recipedealer.llm.LlmUsage;
import com.gatar.recipedealer.processing.ProcessingRepository.RunStatus;
import com.gatar.recipedealer.recipe.RecipeContent;
import com.gatar.recipedealer.recipe.RecipeKey;
import com.gatar.recipedealer.recipe.RecipeRepository;
import com.gatar.recipedealer.recipe.ResolutionRepository;
import com.gatar.recipedealer.recipe.ResolutionService;

import tools.jackson.databind.node.ObjectNode;

/**
 * One cascade step of one capture: extract (outside any transaction), validate, then store the run and either apply
 * it (valid), enqueue the next step (invalid, a next step exists) or end the cascade with its best non-empty result
 * (with {@code needsReview}). Comparison runs are stored only: never applied, never a state change.
 */
@Service
public class ProcessingService {

    public static final String AWAITING_EXTRACTION = "AWAITING_EXTRACTION";
    /** The API key is missing, invalid, or the account cannot serve requests (e.g. no credit). */
    public static final String LLM_UNAVAILABLE = "LLM_UNAVAILABLE";
    /** The API refused this capture's request (a 4xx other than auth / credit). */
    public static final String LLM_REQUEST_REJECTED = "LLM_REQUEST_REJECTED";

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

    private final CaptureLoader loader;
    private final ExtractionCascade cascade;
    private final ProcessingRepository processing;
    private final ResolutionRepository resolutionRepository;
    private final ResolutionService resolutions;
    private final JobQueue jobs;
    private final LlmPricing pricing;
    private final RecipeRepository recipes;
    private final TransactionTemplate tx;
    private final ProcessingProperties properties;

    public ProcessingService(CaptureLoader loader, ExtractionCascade cascade, ProcessingRepository processing,
                             ResolutionRepository resolutionRepository, ResolutionService resolutions, JobQueue jobs,
                             LlmPricing pricing, RecipeRepository recipes, TransactionTemplate tx,
                             ProcessingProperties properties) {
        this.loader = loader;
        this.cascade = cascade;
        this.processing = processing;
        this.resolutionRepository = resolutionRepository;
        this.resolutions = resolutions;
        this.jobs = jobs;
        this.pricing = pricing;
        this.recipes = recipes;
        this.tx = tx;
        this.properties = properties;
    }

    /** Normal processing from the first step (the next steps are enqueued as jobs). */
    public void process(UUID captureId) throws Exception {
        process(ProcessingTask.pipeline(captureId));
    }

    public void process(ProcessingTask task) throws Exception {
        UUID captureId = task.captureId();
        boolean comparison = task.purpose() == Purpose.COMPARISON;
        RecipeExtractor extractor;
        try {
            extractor = cascade.extractor(task.step());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableProcessingException(LLM_UNAVAILABLE, e.getMessage(), e);
        }
        UUID runId = tx.execute(status -> {
            processing.lockState(captureId);
            if (!comparison) {
                processing.setState(captureId, CaptureStatus.PROCESSING, null, null);
            }
            return processing.startRun(task, extractor);
        });

        Capture capture;
        ExtractionResult result;
        List<StoredCandidate> candidates;
        try {
            capture = loader.load(captureId);
            result = extractor.extract(capture);
            candidates = candidates(capture, result);
        } catch (LlmException e) {
            tx.executeWithoutResult(status -> processing.failRun(runId, e.getMessage(), e.errorType()));
            if (e.kind() == LlmException.Kind.TRANSIENT) {
                throw e;
            }
            boolean unavailable = e.kind() == LlmException.Kind.UNAVAILABLE;
            throw new NonRetryableProcessingException(unavailable ? LLM_UNAVAILABLE : LLM_REQUEST_REJECTED,
                    (unavailable ? "Automatic extraction is unavailable: " : "Automatic extraction was refused: ")
                            + e.getMessage(), e);
        } catch (Exception e) {
            tx.executeWithoutResult(status -> processing.failRun(runId, String.valueOf(e), null));
            throw e;
        }

        List<String> errors = ExtractionValidator.validate(result, candidates);
        boolean valid = errors.isEmpty();
        List<StoredCandidate> stored = valid ? candidates : candidates.stream().map(StoredCandidate::needingReview).toList();
        var finished = new FinishedRun(runId, captureId, stored, errors, result.usage(), pricing.cost(result.usage()),
                result.dishPhoto());

        tx.executeWithoutResult(status -> {
            processing.lockState(captureId);
            if (comparison) {
                finished.store(processing, valid ? RunStatus.SUCCEEDED : RunStatus.REJECTED);
            } else if (valid) {
                finished.store(processing, RunStatus.SUCCEEDED);
                apply(capture, runId, stored);
            } else {
                Optional<String> next = cascade.next(task.step(), capture);
                if (next.isPresent()) {
                    finished.store(processing, RunStatus.REJECTED);
                    jobs.enqueue(task.next(next.get()));
                } else {
                    endCascade(task, capture, finished);
                }
            }
            if (!comparison && result.dishPhoto() != null) {
                // A new look at the photos changes the thumbnail also when the recipe content stays the same.
                recipes.refreshThumbnails(captureId);
            }
        });
        log.info("Processed capture {} ({} step {}) with {} {}: {} candidate(s){}", captureId, task.purpose().value(),
                task.step(), extractor.name(), extractor.version(), candidates.size(),
                valid ? "" : ", validation failed: " + String.join(", ", errors));
    }

    private record FinishedRun(UUID id, UUID captureId, List<StoredCandidate> candidates, List<String> errors,
                               LlmUsage usage, BigDecimal cost, DishPhotoFinding dishPhoto) {

        void store(ProcessingRepository processing, RunStatus status) {
            processing.finishRun(id, status, errors, usage, cost);
            candidates.forEach(candidate -> processing.insertCandidate(id, candidate));
            if (dishPhoto != null) {
                processing.insertDishPhoto(id, captureId, dishPhoto);
            }
        }
    }

    /**
     * No step passed validation: the best non-empty result of the cascade goes on (later steps win ties); if every
     * result is empty the capture waits for extraction. A forced cascade never replaces what a capture already applied.
     */
    private void endCascade(ProcessingTask task, Capture capture, FinishedRun current) {
        UUID captureId = task.captureId();
        boolean alreadyApplied = task.purpose() == Purpose.FORCED
                && resolutionRepository.forCapture(captureId).stream().anyMatch(ResolutionRepository.Resolution::applied);
        if (alreadyApplied) {
            current.store(processing, RunStatus.REJECTED);
            restoreState(captureId, "Automatic extraction found no complete recipe; the recipe was not changed.");
            return;
        }

        UUID bestRun = null;
        List<StoredCandidate> bestCandidates = null;
        int bestScore = 0;
        for (UUID earlier : processing.rejectedRuns(task.cascadeId())) {
            List<StoredCandidate> earlierCandidates = processing.candidates(earlier);
            int score = score(earlierCandidates);
            if (score > 0 && score >= bestScore) {
                bestRun = earlier;
                bestCandidates = earlierCandidates;
                bestScore = score;
            }
        }
        int ownScore = score(current.candidates());
        if (ownScore > 0 && ownScore >= bestScore) {
            current.store(processing, RunStatus.SUCCEEDED);
            apply(capture, current.id(), current.candidates());
        } else if (bestRun != null) {
            current.store(processing, RunStatus.REJECTED);
            processing.promote(bestRun);
            apply(capture, bestRun, bestCandidates);
        } else {
            current.store(processing, RunStatus.AWAITING_EXTRACTION);
            boolean decided = !resolutionRepository.forCapture(captureId).isEmpty();
            processing.setState(captureId, CaptureStatus.NEEDS_ATTENTION, AWAITING_EXTRACTION,
                    "No recipe could be read from this capture."
                            + (decided ? " Your decision is saved." : ""));
        }
    }

    /** Non-empty (ingredients or steps) results only; see {@link ExtractionValidator#score}. */
    private static int score(List<StoredCandidate> candidates) {
        return candidates.stream().allMatch(c -> ReviewPolicy.isEmpty(c.content())) ? 0 : ExtractionValidator.score(candidates);
    }

    /** The state as the capture's current result gives it, after a forced cascade that changed nothing. */
    private void restoreState(UUID captureId, String message) {
        processing.latestCompletedRun(captureId)
                .filter(run -> run.status() == RunStatus.SUCCEEDED)
                .ifPresentOrElse(
                        run -> resolutions.apply(captureId, run.id(), processing.candidates(run.id()), false),
                        () -> processing.setState(captureId, CaptureStatus.DONE, null, null));
        if (processing.lockState(captureId) == CaptureStatus.DONE) {
            processing.setState(captureId, CaptureStatus.DONE, null, message);
        }
    }

    private void apply(Capture capture, UUID runId, List<StoredCandidate> candidates) {
        if (properties.autoNew()) {
            // Identity keys and the source URL describe the whole capture: only for a single candidate.
            var sourceUrl = capture.body().path("source").path("url");
            String url = candidates.size() == 1 && sourceUrl.isString() ? sourceUrl.asString() : null;
            resolutions.decideUnmatchedAsNew(capture.id(), candidates, url, capture.userId(), capture.deviceId());
        }
        resolutions.apply(capture.id(), runId, candidates, true);
    }

    private static List<StoredCandidate> candidates(Capture capture, ExtractionResult result) {
        List<RecipeCandidate> extracted = result.candidates();
        // Identity keys describe the whole capture, so they only identify a recipe if there is exactly one.
        List<RecipeKey> keys = extracted.size() == 1 ? RecipeKey.fromCapture(capture.body()) : List.of();
        List<StoredCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) {
            RecipeCandidate guarded = ProvenanceGuard.apply(capture, extracted.get(i));
            // LLM answers have no photos: the page photo the phone found stays (a single recipe only)
            ObjectNode recipe = extracted.size() == 1
                    ? (ObjectNode) RecipeContent.keepingImagesOf(guarded.recipe(), capture.recipe()) : guarded.recipe();
            candidates.add(new StoredCandidate(i, capture.schemaVersion(), recipe,
                    RecipeContent.contentHash(recipe), guarded.provenance(),
                    ReviewPolicy.needsReview(recipe, guarded.provenance()), keys));
        }
        return candidates;
    }
}
