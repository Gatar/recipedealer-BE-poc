package com.gatar.recipedealer.processing;

import java.util.List;

/** Zero or more candidates (v1: always exactly one; phase 2 may split a capture into several recipes). */
public record ExtractionResult(List<RecipeCandidate> candidates) {
}
