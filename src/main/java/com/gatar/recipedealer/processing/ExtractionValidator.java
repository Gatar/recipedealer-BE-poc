package com.gatar.recipedealer.processing;

import java.util.ArrayList;
import java.util.List;

import com.gatar.recipedealer.recipe.RecipeContent;

/**
 * Decides whether a cascade step succeeded: every candidate has a title, at least one ingredient and at least one
 * step, and the extractor reported no problem (schema violation, {@code max_tokens}, refusal, no recipe found). The
 * same rules apply to the heuristic, so a partial result (e.g. no steps) goes on to the LLM.
 */
public final class ExtractionValidator {

    private ExtractionValidator() {
    }

    /** @return validation errors; empty when the result is valid */
    public static List<String> validate(ExtractionResult result, List<StoredCandidate> candidates) {
        List<String> errors = new ArrayList<>(result.problems());
        if (candidates.isEmpty()) {
            errors.add("no_candidates");
        }
        for (StoredCandidate candidate : candidates) {
            String prefix = candidates.size() == 1 ? "" : candidate.index() + ":";
            if (RecipeContent.title(candidate.content()) == null) {
                errors.add(prefix + "no_title");
            }
            if (!RecipeContent.hasIngredients(candidate.content())) {
                errors.add(prefix + "no_ingredients");
            }
            if (!RecipeContent.hasSteps(candidate.content())) {
                errors.add(prefix + "no_steps");
            }
        }
        return errors.stream().distinct().toList();
    }

    /** For the fallback: one point each for a title, ingredients and steps (best candidate of a run). */
    public static int score(List<StoredCandidate> candidates) {
        return candidates.stream().mapToInt(candidate -> (RecipeContent.title(candidate.content()) != null ? 1 : 0)
                + (RecipeContent.hasIngredients(candidate.content()) ? 1 : 0)
                + (RecipeContent.hasSteps(candidate.content()) ? 1 : 0)).max().orElse(0);
    }
}
