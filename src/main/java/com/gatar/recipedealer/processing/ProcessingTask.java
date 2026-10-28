package com.gatar.recipedealer.processing;

import java.util.UUID;

import com.gatar.recipedealer.common.Ids;

/**
 * One cascade step for one capture. {@code cascadeId} groups the runs of one pass through the cascade (the fallback
 * picks the best of them).
 */
public record ProcessingTask(UUID captureId, String step, UUID cascadeId, Purpose purpose) {

    /** Normal processing from the first step. */
    public static ProcessingTask pipeline(UUID captureId) {
        return new ProcessingTask(captureId, ExtractionCascade.HEURISTIC, Ids.newId(), Purpose.PIPELINE);
    }

    public ProcessingTask next(String nextStep) {
        return new ProcessingTask(captureId, nextStep, cascadeId, purpose);
    }
}
