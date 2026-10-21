package com.gatar.recipedealer.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.capture.CaptureStatus;
import com.gatar.recipedealer.processing.ProcessingRepository;
import com.gatar.recipedealer.processing.ProcessingRepository.RunStatus;
import com.gatar.recipedealer.processing.StoredCandidate;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.gatar.recipedealer.recipe.ResolutionRepository.Action;
import com.gatar.recipedealer.recipe.ResolutionRepository.Resolution;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

/**
 * The user's NEW / UPDATE decision per candidate. A decision may arrive before processing has finished; it is
 * stored and applied as soon as the candidate exists. Processing and resolutions of one capture are serialized by
 * the lock on its state row.
 */
@Service
public class ResolutionService {

    public static final int MAX_CANDIDATES = 100;
    public static final String TARGET_GONE = "TARGET_GONE";

    private final ProcessingRepository processing;
    private final ResolutionRepository resolutions;
    private final RecipeRepository recipes;
    private final RevisionService revisions;

    public ResolutionService(ProcessingRepository processing, ResolutionRepository resolutions, RecipeRepository recipes,
                             RevisionService revisions) {
        this.processing = processing;
        this.resolutions = resolutions;
        this.recipes = recipes;
        this.revisions = revisions;
    }

    /** {@code PUT /v1/captures/{id}/extracted/{index}/resolution}. Idempotent. */
    @Transactional
    public void request(UUID captureId, int index, Action action, UUID targetRecipeId, DevicePrincipal device) {
        if (index < 0 || index >= MAX_CANDIDATES) {
            throw ApiException.badRequest("candidate index must be between 0 and " + (MAX_CANDIDATES - 1));
        }
        if (action == null) {
            throw ApiException.badRequest("action must be NEW or UPDATE");
        }
        if ((action == Action.UPDATE) != (targetRecipeId != null)) {
            throw ApiException.badRequest(action == Action.UPDATE ? "UPDATE requires recipeId" : "NEW must not have a recipeId");
        }
        processing.lockState(captureId);
        if (action == Action.UPDATE && recipes.liveTarget(targetRecipeId).isEmpty()) {
            throw ApiException.conflict("recipe_gone", "recipe " + targetRecipeId + " does not exist or was deleted");
        }

        var existing = resolutions.find(captureId, index);
        boolean same = existing.map(r -> r.action() == action && java.util.Objects.equals(r.targetRecipeId(), targetRecipeId))
                .orElse(false);
        if (existing.isPresent() && existing.get().applied()) {
            if (same) {
                return;
            }
            throw ApiException.conflict("already_resolved", "candidate " + index + " was already resolved differently");
        }
        if (!same) {
            resolutions.upsertPending(captureId, index, action, targetRecipeId, device.userId(), device.deviceId());
        }

        var run = processing.latestCompletedRun(captureId);
        if (run.isPresent() && run.get().status() == RunStatus.SUCCEEDED) {
            List<StoredCandidate> candidates = processing.candidates(run.get().id());
            if (index >= candidates.size()) {
                throw new NotFoundException("capture " + captureId + " has no candidate " + index);
            }
            apply(captureId, run.get().id(), candidates, false);
        }
    }

    /**
     * Applies stored decisions to the candidates of a finished run and updates the capture state. With
     * {@code newRun} (reprocessing), candidates already resolved earlier update their recipe automatically, which
     * never moves a head with origin {@code USER}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID captureId, UUID runId, List<StoredCandidate> candidates, boolean newRun) {
        Map<Integer, Resolution> byIndex = resolutions.forCapture(captureId).stream()
                .collect(Collectors.toMap(Resolution::index, Function.identity()));
        boolean targetGone = false;
        for (StoredCandidate candidate : candidates) {
            Resolution resolution = byIndex.get(candidate.index());
            if (resolution == null) {
                continue;
            }
            if (!resolution.applied()) {
                targetGone |= !applyDecision(captureId, runId, candidate, resolution);
            } else if (newRun && resolution.appliedRecipeId() != null) {
                applyAutomatic(captureId, runId, candidate, resolution.appliedRecipeId());
            }
        }
        updateState(captureId, candidates, targetGone);
    }

    /** @return false if the UPDATE target disappeared and the user has to decide again */
    private boolean applyDecision(UUID captureId, UUID runId, StoredCandidate candidate, Resolution resolution) {
        var content = new RevisionService.Content(candidate.content(), Origin.MACHINE, runId, captureId,
                resolution.userId(), resolution.deviceId(), candidate.needsReview());
        RevisionService.Outcome outcome;
        List<String> messages = new ArrayList<>();
        if (resolution.action() == Action.NEW) {
            outcome = revisions.createRecipe(content);
            messages.add("Created recipe \"" + titleOf(outcome.recipeId()) + "\".");
        } else {
            var target = recipes.liveTarget(resolution.targetRecipeId());
            if (target.isEmpty()) {
                resolutions.delete(captureId, candidate.index());
                return false;
            }
            outcome = revisions.addRevision(target.get(), content, true);
            messages.add((outcome.created() ? "Updated recipe \"" : "Recipe already up to date: \"")
                    + titleOf(outcome.recipeId()) + "\".");
        }
        messages.addAll(attachKeys(captureId, candidate, outcome.recipeId()));
        resolutions.markApplied(captureId, candidate.index(), runId, outcome.recipeId(), outcome.revisionId(),
                String.join(" ", messages));
        return true;
    }

    private void applyAutomatic(UUID captureId, UUID runId, StoredCandidate candidate, UUID recipeId) {
        recipes.liveTarget(recipeId).ifPresent(target -> {
            var content = new RevisionService.Content(candidate.content(), Origin.MACHINE, runId, captureId, null, null,
                    candidate.needsReview());
            revisions.addRevision(target, content, false);
            attachKeys(captureId, candidate, target);
        });
    }

    /** Adds the capture's keys to the recipe; keys owned by another recipe are reported, not moved. */
    private List<String> attachKeys(UUID captureId, StoredCandidate candidate, UUID recipeId) {
        List<String> messages = new ArrayList<>();
        for (RecipeKey key : candidate.keys()) {
            UUID owner = recipes.addKey(key, recipeId, captureId);
            if (!owner.equals(recipeId)) {
                messages.add("The %s %s already belongs to recipe \"%s\" (%s)."
                        .formatted(key.kind() == RecipeKey.Kind.CANONICAL_URL ? "URL" : "JSON-LD id", key.value(),
                                titleOf(owner), owner));
            }
        }
        return messages;
    }

    private void updateState(UUID captureId, List<StoredCandidate> candidates, boolean targetGone) {
        Map<Integer, Resolution> byIndex = resolutions.forCapture(captureId).stream()
                .collect(Collectors.toMap(Resolution::index, Function.identity()));
        boolean allApplied = candidates.stream()
                .allMatch(c -> byIndex.containsKey(c.index()) && byIndex.get(c.index()).applied());
        if (allApplied) {
            String message = candidates.stream().map(c -> byIndex.get(c.index()).message())
                    .filter(java.util.Objects::nonNull).collect(Collectors.joining(" "));
            processing.setState(captureId, CaptureStatus.DONE, null, message.isEmpty() ? null : message);
        } else if (targetGone) {
            processing.setState(captureId, CaptureStatus.NEEDS_DECISION, TARGET_GONE,
                    "The recipe chosen for this capture was deleted; please choose again.");
        } else {
            processing.setState(captureId, CaptureStatus.NEEDS_DECISION, null, null);
        }
    }

    private String titleOf(UUID recipeId) {
        return recipes.title(recipeId).orElse("");
    }
}
