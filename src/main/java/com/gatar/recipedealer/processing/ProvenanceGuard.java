package com.gatar.recipedealer.processing;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fields the user edited on the phone (provenance {@code user}) are never changed by an automatic step: after any
 * extractor, such fields are copied back from the capture's recipe.
 */
public final class ProvenanceGuard {

    public static final String USER = "user";

    /** Provenance keys used by the Android app → location in the recipe DTO. */
    static final Map<String, JsonPointer> FIELDS = Map.of(
            "title", JsonPointer.compile("/title"),
            "language", JsonPointer.compile("/language"),
            "servings", JsonPointer.compile("/servings"),
            "prepMinutes", JsonPointer.compile("/times/prepMinutes"),
            "cookMinutes", JsonPointer.compile("/times/cookMinutes"),
            "totalMinutes", JsonPointer.compile("/times/totalMinutes"),
            "ingredients", JsonPointer.compile("/ingredientGroups"),
            "steps", JsonPointer.compile("/stepGroups"),
            "notes", JsonPointer.compile("/notes"),
            "images", JsonPointer.compile("/images"));

    private ProvenanceGuard() {
    }

    public static RecipeCandidate apply(Capture capture, RecipeCandidate candidate) {
        ObjectNode recipe = candidate.recipe().deepCopy();
        Map<String, String> provenance = new LinkedHashMap<>(candidate.provenance());
        capture.provenance().forEach((key, stage) -> {
            JsonPointer pointer = FIELDS.get(key);
            if (!USER.equals(stage) || pointer == null) {
                return;
            }
            copy(capture.recipe().at(pointer), recipe, pointer);
            provenance.put(key, USER);
        });
        return new RecipeCandidate(recipe, provenance);
    }

    private static void copy(JsonNode value, ObjectNode target, JsonPointer pointer) {
        ObjectNode parent = target;
        JsonPointer current = pointer;
        while (!current.tail().matches()) {
            parent = parent.has(current.getMatchingProperty()) && parent.get(current.getMatchingProperty()).isObject()
                    ? (ObjectNode) parent.get(current.getMatchingProperty())
                    : parent.putObject(current.getMatchingProperty());
            current = current.tail();
        }
        if (value.isMissingNode()) {
            parent.remove(current.getMatchingProperty());
        } else {
            parent.set(current.getMatchingProperty(), value.deepCopy());
        }
    }
}
