package com.gatar.recipedealer.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewPolicyTest {

    private static final JsonNode RECIPE = JsonMapper.builder().build().readTree("""
            {"ingredientGroups": [{"items": ["mąka"]}], "stepGroups": [{"items": ["Wymieszać."]}]}""");

    @Test
    void structuredDataNeedsNoReview() {
        assertThat(ReviewPolicy.needsReview(RECIPE, Map.of("ingredients", "jsonLd", "steps", "microdata"))).isFalse();
        assertThat(ReviewPolicy.needsReview(RECIPE, Map.of("ingredients", "user", "steps", "manual"))).isFalse();
    }

    @Test
    void heuristicOrOcrIngredientsOrStepsNeedReview() {
        assertThat(ReviewPolicy.needsReview(RECIPE, Map.of("ingredients", "jsonLd", "steps", "heuristic"))).isTrue();
        assertThat(ReviewPolicy.needsReview(RECIPE, Map.of("ingredients", "ocr", "steps", "jsonLd"))).isTrue();
        assertThat(ReviewPolicy.needsReview(RECIPE, Map.of("ingredients", "page", "steps", "jsonLd"))).isTrue();
    }

    @Test
    void emptyRecipeIsDetected() {
        JsonNode empty = JsonMapper.builder().build().readTree("""
                {"title": "Kartka od babci", "ingredientGroups": [{"items": ["  "]}], "stepGroups": []}""");
        assertThat(ReviewPolicy.isEmpty(empty)).isTrue();
        assertThat(ReviewPolicy.isEmpty(RECIPE)).isFalse();
    }
}
