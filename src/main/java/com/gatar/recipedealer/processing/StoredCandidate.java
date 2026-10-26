package com.gatar.recipedealer.processing;

import java.util.List;
import java.util.Map;

import com.gatar.recipedealer.recipe.RecipeKey;

import tools.jackson.databind.node.ObjectNode;

/** A candidate as stored on its processing run. {@code schemaVersion}: the contract version {@code content} follows. */
public record StoredCandidate(int index, int schemaVersion, ObjectNode content, String contentHash,
                              Map<String, String> provenance, boolean needsReview, List<RecipeKey> keys) {
}
