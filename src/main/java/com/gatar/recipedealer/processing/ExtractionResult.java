package com.gatar.recipedealer.processing;

import java.util.List;

import com.gatar.recipedealer.llm.LlmUsage;

/**
 * Zero or more candidates (v1: always exactly one; phase 2 may split a capture into several recipes).
 *
 * @param usage    API usage of an LLM step, null for local extractors
 * @param problems what the extractor itself found wrong (e.g. {@code max_tokens}, {@code refusal:cyber}); any problem
 *                 fails validation
 */
public record ExtractionResult(List<RecipeCandidate> candidates, LlmUsage usage, List<String> problems) {

    public ExtractionResult(List<RecipeCandidate> candidates) {
        this(candidates, null, List.of());
    }
}
