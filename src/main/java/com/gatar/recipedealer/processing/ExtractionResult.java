package com.gatar.recipedealer.processing;

import java.util.List;

import com.gatar.recipedealer.llm.LlmUsage;

/**
 * Zero or more candidates (v1: always exactly one; phase 2 may split a capture into several recipes).
 *
 * @param usage     API usage of an LLM step, null for local extractors
 * @param problems  what the extractor itself found wrong (e.g. {@code max_tokens}, {@code refusal:cyber}); any problem
 *                  fails validation
 * @param dishPhoto what an LLM step found about the dish photo; null when nothing looked at the capture's images
 */
public record ExtractionResult(List<RecipeCandidate> candidates, LlmUsage usage, List<String> problems,
                               DishPhotoFinding dishPhoto) {

    public ExtractionResult(List<RecipeCandidate> candidates) {
        this(candidates, null, List.of(), null);
    }
}
