package com.gatar.recipedealer.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.processing.ProcessingRepository.RunStatus;
import com.gatar.recipedealer.recipe.RecipeContent;
import com.gatar.recipedealer.recipe.RecipeKey;
import com.gatar.recipedealer.recipe.ResolutionRepository;
import com.gatar.recipedealer.recipe.ResolutionService;

/** One processing run of one capture: extract (outside any transaction), then store candidates and apply decisions. */
@Service
public class ProcessingService {

    public static final String AWAITING_EXTRACTION = "AWAITING_EXTRACTION";

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

    private final CaptureLoader loader;
    private final RecipeExtractor extractor;
    private final ProcessingRepository processing;
    private final ResolutionRepository resolutionRepository;
    private final ResolutionService resolutions;
    private final TransactionTemplate tx;
    private final ProcessingProperties properties;

    public ProcessingService(CaptureLoader loader, RecipeExtractor extractor, ProcessingRepository processing,
                             ResolutionRepository resolutionRepository, ResolutionService resolutions,
                             TransactionTemplate tx, ProcessingProperties properties) {
        this.loader = loader;
        this.extractor = extractor;
        this.processing = processing;
        this.resolutionRepository = resolutionRepository;
        this.resolutions = resolutions;
        this.tx = tx;
        this.properties = properties;
    }

    public void process(UUID captureId) throws Exception {
        UUID runId = tx.execute(status -> {
            processing.lockState(captureId);
            processing.setState(captureId, CaptureStatus.PROCESSING, null, null);
            return processing.startRun(captureId, extractor);
        });

        Capture capture;
        List<StoredCandidate> candidates;
        try {
            capture = loader.load(captureId);
            candidates = extract(capture);
        } catch (Exception e) {
            tx.executeWithoutResult(status -> processing.finishRun(runId, RunStatus.FAILED, String.valueOf(e)));
            throw e;
        }

        tx.executeWithoutResult(status -> {
            processing.lockState(captureId);
            boolean empty = candidates.stream().allMatch(c -> ReviewPolicy.isEmpty(c.content()));
            processing.finishRun(runId, empty ? RunStatus.AWAITING_EXTRACTION : RunStatus.SUCCEEDED, null);
            candidates.forEach(candidate -> processing.insertCandidate(runId, candidate));
            if (empty) {
                boolean decided = !resolutionRepository.forCapture(captureId).isEmpty();
                processing.setState(captureId, CaptureStatus.NEEDS_ATTENTION, AWAITING_EXTRACTION,
                        "No ingredients or steps could be read; waiting for automatic extraction."
                                + (decided ? " Your decision is saved." : ""));
            } else {
                if (properties.autoNew()) {
                    // Identity keys and the source URL describe the whole capture: only for a single candidate.
                    var sourceUrl = capture.body().path("source").path("url");
                    String url = candidates.size() == 1 && sourceUrl.isString() ? sourceUrl.asString() : null;
                    resolutions.decideUnmatchedAsNew(captureId, candidates, url, capture.userId(), capture.deviceId());
                }
                resolutions.apply(captureId, runId, candidates, true);
            }
        });
        log.info("Processed capture {} with {} {}: {} candidate(s)", captureId, extractor.name(), extractor.version(),
                candidates.size());
    }

    private List<StoredCandidate> extract(Capture capture) throws Exception {
        List<RecipeCandidate> extracted = extractor.extract(capture).candidates();
        // Identity keys describe the whole capture, so they only identify a recipe if there is exactly one.
        List<RecipeKey> keys = extracted.size() == 1 ? RecipeKey.fromCapture(capture.body()) : List.of();
        List<StoredCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) {
            RecipeCandidate guarded = ProvenanceGuard.apply(capture, extracted.get(i));
            candidates.add(new StoredCandidate(i, capture.schemaVersion(), guarded.recipe(),
                    RecipeContent.contentHash(guarded.recipe()), guarded.provenance(),
                    ReviewPolicy.needsReview(guarded.recipe(), guarded.provenance()), keys));
        }
        return candidates;
    }
}
