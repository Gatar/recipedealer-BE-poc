package com.gatar.recipedealer.processing;

import java.util.Map;
import java.util.Set;

import com.gatar.recipedealer.recipe.RecipeContent;

import tools.jackson.databind.JsonNode;

/** Completeness is not correctness: ingredients or steps from weak stages must be reviewed by a person. */
public final class ReviewPolicy {

    /** {@code llm}: every LLM result needs review. */
    static final Set<String> WEAK_STAGES = Set.of("heuristic", "ocr", "page", "llm");

    private ReviewPolicy() {
    }

    public static boolean needsReview(JsonNode recipe, Map<String, String> provenance) {
        return RecipeContent.hasIngredients(recipe) && WEAK_STAGES.contains(provenance.get("ingredients"))
                || RecipeContent.hasSteps(recipe) && WEAK_STAGES.contains(provenance.get("steps"));
    }

    /** Neither ingredients nor steps: nothing a person could cook from (e.g. unreadable handwriting). */
    public static boolean isEmpty(JsonNode recipe) {
        return !RecipeContent.hasIngredients(recipe) && !RecipeContent.hasSteps(recipe);
    }
}
