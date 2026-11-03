package com.gatar.recipedealer.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Translation request of a new recipe: static system prompt, the target language and the recipe's texts as JSON, and
 * an output schema with the same texts. Only texts change: times, servings count, photos and everything else stay.
 * The answer must keep the structure (same groups, same number of items), otherwise it is rejected. Changing the
 * prompt or the schema means a new {@link #VERSION}, stored with every translation.
 */
public final class TranslatePrompt {

    public static final String VERSION = "r1";

    /** Byte-identical across requests (the target language and the recipe go into the user message). */
    public static final String SYSTEM_PROMPT = """
            You translate recipes for a private household recipe collection. The user message gives the target \
            language and the recipe's texts as JSON (title, servings, ingredient groups, step groups, notes). Return \
            the translated texts as JSON that follows the given schema.

            Rules:
            1. Translate every text into the target language: the title, the servings text, group names, every \
            ingredient, every step and every note. Keep the meaning exactly. Never add, drop, merge, split or reorder \
            anything: the answer has the same groups, each with the same number of items in the same order. A group \
            name that is null stays null.
            2. Write natural cooking language of the target language, as its cookbooks do (for steps the form its \
            recipes usually use). Keep the source's level of detail.
            3. Quantities: keep every number as written. Units without a fixed size (cups, tablespoons, teaspoons, \
            sticks, pinches, handfuls, cans, pieces and the like) are only translated, never converted.
            4. Units with a fixed conversion factor are converted to metric, by plain arithmetic, with the original \
            in parentheses after the converted value:
               - degrees Fahrenheit to degrees Celsius: (F − 32) × 5 / 9, rounded to whole degrees ("350°F" → \
            "177°C (350°F)");
               - ounces (weight) to grams × 28.35; pounds to grams × 453.6 (kilograms from 1000 g);
               - fluid ounces to millilitres × 29.57; pints × 473.2 ml; quarts × 946.4 ml; gallons × 3.785 l \
            (US measures; imperial ones only when the recipe is clearly British: pint 568.3 ml, gallon 4.546 l);
               - inches to centimetres × 2.54.
               Round grams and millilitres to whole numbers (one decimal below 10). A range converts both ends. Never \
            convert a unit whose size is not fixed (rule 3), and never touch values that are already metric.
            5. Titles: translate the name of the dish, but keep a name the target language uses unchanged (for \
            example "Guacamole", "Tiramisu", "Pad thai").
            6. sourceLanguage: the ISO 639-1 code of the recipe's language. When the recipe is already written in the \
            target language, set alreadyInTarget to true and return the texts unchanged.
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TranslatePrompt() {
    }

    /** Output schema; structured outputs need {@code additionalProperties: false} and every property required. */
    public static Map<String, Object> schema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> group = object(Map.of("name", nullableString, "items", strings));
        Map<String, Object> groups = Map.of("type", "array", "items", Map.of("$ref", "#/$defs/group"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceLanguage", nullableString);
        properties.put("alreadyInTarget", Map.of("type", "boolean"));
        properties.put("title", nullableString);
        properties.put("servingsText", nullableString);
        properties.put("ingredientGroups", groups);
        properties.put("stepGroups", groups);
        properties.put("notes", strings);
        Map<String, Object> schema = new LinkedHashMap<>(object(properties));
        schema.put("$defs", Map.of("group", group));
        return schema;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("properties", properties);
        object.put("required", List.copyOf(properties.keySet()));
        object.put("additionalProperties", false);
        return object;
    }

    /** The user message: the target language, then the texts of {@code recipe} in the output's shape. */
    public static String input(String targetLanguage, JsonNode recipe) {
        String name = Locale.forLanguageTag(targetLanguage).getDisplayLanguage(Locale.ENGLISH);
        return "Target language: " + (name.isBlank() ? targetLanguage : name + " (" + targetLanguage + ")")
                + "\n\nRecipe:\n" + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(texts(recipe));
    }

    /** The translatable texts of a recipe DTO. */
    static ObjectNode texts(JsonNode recipe) {
        ObjectNode texts = JsonNodeFactory.instance.objectNode();
        texts.set("title", recipe.path("title").isString() ? recipe.get("title") : JsonNodeFactory.instance.nullNode());
        JsonNode servings = recipe.path("servings").path("text");
        texts.set("servingsText", servings.isString() ? servings : JsonNodeFactory.instance.nullNode());
        texts.set("ingredientGroups", groups(recipe.path("ingredientGroups")));
        texts.set("stepGroups", groups(recipe.path("stepGroups")));
        ArrayNode notes = texts.putArray("notes");
        recipe.path("notes").forEach(note -> {
            if (note.isString()) {
                notes.add(note);
            }
        });
        return texts;
    }

    private static ArrayNode groups(JsonNode source) {
        ArrayNode groups = JsonNodeFactory.instance.arrayNode();
        source.forEach(group -> {
            ObjectNode copy = groups.addObject();
            copy.set("name", group.path("name").isString() ? group.get("name") : JsonNodeFactory.instance.nullNode());
            ArrayNode items = copy.putArray("items");
            group.path("items").forEach(item -> {
                if (item.isString()) {
                    items.add(item);
                }
            });
        });
        return groups;
    }

    /**
     * @param recipe the recipe with the translated texts and {@code language} set to the target (null when
     *               {@code alreadyInTarget})
     */
    public record Parsed(String sourceLanguage, boolean alreadyInTarget, ObjectNode recipe) {
    }

    /**
     * Applies the answer to a copy of {@code recipe}.
     *
     * @throws IllegalArgumentException if the output is not valid JSON or does not keep the recipe's structure
     */
    public static Parsed parse(String text, JsonNode recipe, String targetLanguage) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("output is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("output is not a JSON object");
        }
        String source = root.path("sourceLanguage").isString() ? root.get("sourceLanguage").asString() : null;
        if (root.path("alreadyInTarget").asBoolean(false)) {
            return new Parsed(source, true, null);
        }
        ObjectNode original = texts(recipe);
        List<String> problems = new ArrayList<>();
        sameShape(original.get("ingredientGroups"), root.get("ingredientGroups"), "ingredientGroups", problems);
        sameShape(original.get("stepGroups"), root.get("stepGroups"), "stepGroups", problems);
        if (!root.path("notes").isArray() || root.get("notes").size() != original.get("notes").size()) {
            problems.add("notes");
        }
        if (original.get("title").isString() && !nonBlank(root.get("title"))) {
            problems.add("title");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("structure changed: " + String.join(", ", problems));
        }
        ObjectNode translated = ((ObjectNode) recipe).deepCopy();
        if (nonBlank(root.get("title"))) {
            translated.put("title", root.get("title").asString().strip());
        }
        if (translated.path("servings").isObject() && nonBlank(root.get("servingsText"))) {
            ((ObjectNode) translated.get("servings")).put("text", root.get("servingsText").asString().strip());
        }
        translated.set("ingredientGroups", root.get("ingredientGroups").deepCopy());
        translated.set("stepGroups", root.get("stepGroups").deepCopy());
        translated.set("notes", root.get("notes").deepCopy());
        translated.put("language", targetLanguage);
        return new Parsed(source, false, translated);
    }

    private static void sameShape(JsonNode original, JsonNode answer, String field, List<String> problems) {
        if (answer == null || !answer.isArray() || answer.size() != original.size()) {
            problems.add(field);
            return;
        }
        for (int i = 0; i < original.size(); i++) {
            JsonNode items = answer.get(i).path("items");
            boolean named = original.get(i).get("name").isString();
            if (!items.isArray() || items.size() != original.get(i).get("items").size()
                    || named != nonBlank(answer.get(i).get("name"))) {
                problems.add(field + "[" + i + "]");
            }
        }
    }

    private static boolean nonBlank(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank();
    }
}
