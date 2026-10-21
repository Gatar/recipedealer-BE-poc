package com.gatar.recipedealer.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.gatar.recipedealer.common.Hashing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Read helpers for the recipe DTO (the {@code recipe} object of the capture contract), kept as a JSON tree. */
public final class RecipeContent {

    private static final JsonMapper CANONICAL = JsonMapper.builder().build();

    private RecipeContent() {
    }

    /** SHA-256 of the JSON with object keys sorted, so equal content gives an equal hash. */
    public static String contentHash(JsonNode recipe) {
        return Hashing.sha256Hex(CANONICAL.writeValueAsBytes(sorted(recipe)));
    }

    public static String title(JsonNode recipe) {
        JsonNode title = recipe.get("title");
        return title == null || title.isNull() || title.asString().isBlank() ? null : title.asString().strip();
    }

    public static List<String> ingredientLines(JsonNode recipe) {
        return lines(recipe, "ingredientGroups");
    }

    public static List<String> stepLines(JsonNode recipe) {
        return lines(recipe, "stepGroups");
    }

    public static boolean hasIngredients(JsonNode recipe) {
        return !ingredientLines(recipe).isEmpty();
    }

    public static boolean hasSteps(JsonNode recipe) {
        return !stepLines(recipe).isEmpty();
    }

    public static String ingredientsText(JsonNode recipe) {
        List<String> lines = ingredientLines(recipe);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static List<String> lines(JsonNode recipe, String groupsField) {
        List<String> lines = new ArrayList<>();
        JsonNode groups = recipe.get(groupsField);
        if (groups == null || !groups.isArray()) {
            return lines;
        }
        for (JsonNode group : groups) {
            JsonNode items = group.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    if (item.isString() && !item.asString().isBlank()) {
                        lines.add(item.asString().strip());
                    }
                }
            }
        }
        return lines;
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), sorted(field.getValue()));
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            fields.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(sorted(element)));
            return result;
        }
        return node;
    }
}
