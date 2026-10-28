package com.gatar.recipedealer.processing;

import java.util.List;
import java.util.Map;

import com.gatar.recipedealer.recipe.RecipeKey;

import tools.jackson.databind.node.ObjectNode;

/** A candidate as stored on its processing run. {@code schemaVersion}: the contract version {@code content} follows. */
public record StoredCandidate(int index, int schemaVersion, ObjectNode content, String contentHash,
                              Map<String, String> provenance, boolean needsReview, List<RecipeKey> keys) {

    /** Candidates of a run that failed validation always need review, should the fallback ever apply them. */
    public StoredCandidate needingReview() {
        return new StoredCandidate(index, schemaVersion, content, contentHash, provenance, true, keys);
    }
}
