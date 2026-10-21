package com.gatar.recipedealer.processing;

import java.util.Map;

import tools.jackson.databind.node.ObjectNode;

/** One recipe found in a capture: content in the recipe DTO shape plus per-field provenance. */
public record RecipeCandidate(ObjectNode recipe, Map<String, String> provenance) {
}
