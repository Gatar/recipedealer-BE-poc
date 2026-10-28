package com.gatar.recipedealer.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The static part of every extraction request (system prompt and output schema) and the mapping of the model's JSON
 * to the recipe DTO of the capture contract. Changing the prompt or the schema means a new {@link #VERSION}, which is
 * part of the extractor version stored on every run.
 */
public final class RecipePrompt {

    public static final String VERSION = "p1";

    /** Provenance stage of every field the model produced; a weak stage in {@code ReviewPolicy}. */
    public static final String STAGE = "llm";

    /** Byte-identical across requests: it is the cached prefix (with {@code cache_control} on it). */
    public static final String SYSTEM_PROMPT = """
            You extract cooking recipes for a private household recipe collection. The input is what a family member \
            saved on their phone: photos of cookbook pages or handwritten recipe cards, screenshots, PDF documents, \
            and text that the phone already read from them (OCR, PDF text, the readable text of a web page). Return \
            the recipe as JSON that follows the given schema.

            Rules:
            1. Use only what the source contains. Never add ingredients, quantities, steps, times or servings that are \
            not in the source, and never fill gaps with what a typical recipe would contain. Leave a field null or a \
            list empty when the source does not give it.
            2. Keep the language of the source exactly; most recipes are Polish. Do not translate, do not modernize \
            spelling, keep diacritics (ą, ć, ę, ł, ń, ó, ś, ź, ż). Fix only obvious OCR errors when the image shows \
            the correct text.
            3. title: the recipe's name as written in the source. If the source has no title, use a short name taken \
            from the source's own words (for example the dish named in the first sentence).
            4. language: the ISO 639-1 code of the recipe text, for example "pl" or "en".
            5. ingredientGroups: each ingredient is one string exactly as written, quantity and unit included \
            ("250 g mąki pszennej", "2 jajka", "sól do smaku"). Do not split quantity and name, do not convert units. \
            When the source groups ingredients under headings (for example "Ciasto", "Krem", "Polewa"), make one \
            group per heading with that heading as name; otherwise make one group with name null.
            6. stepGroups: each step is one string, in the source's order. Split numbered or clearly separate steps \
            into separate strings; keep a paragraph that describes one action as one step. Use groups with names \
            the same way as for ingredients. Drop step numbers ("1.", "Krok 2:") from the text.
            7. servings: text exactly as written ("4 porcje", "tortownica 24 cm"); count only when the source states \
            a number of servings or pieces, otherwise null.
            8. times: minutes, only when the source states them. prepMinutes for preparation, cookMinutes for baking \
            or cooking, totalMinutes for the total. Convert hours to minutes ("1,5 godziny" is 90).
            9. notes: the source's own tips, variants, storage advice or remarks that belong to the recipe. Leave out \
            advertising, comments, ratings, nutrition tables and navigation text of web pages.
            10. Handwriting: transcribe carefully. When a word is hard to read, write your best reading of it; do not \
            replace it with a different word that would merely make sense.
            11. When both an image and text read from it are given, the image is the authority; the text may contain \
            recognition errors and may mix in unrelated parts of the page.
            12. When the source contains several recipes, extract the main one: the one the source is about, or the \
            first complete one.
            13. Set recipeFound to false when the source contains no recipe (for example an unrelated photo or an \
            empty page) and leave all other fields empty. Never invent a recipe.
            14. Return only the extracted fields. Do not repeat the source, do not add explanations.""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RecipePrompt() {
    }

    /** Output JSON schema; structured outputs need {@code additionalProperties: false} and every property required. */
    public static Map<String, Object> schema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> nullableInteger = Map.of("type", List.of("integer", "null"));
        Map<String, Object> group = object(Map.of(
                "name", nullableString,
                "items", Map.of("type", "array", "items", Map.of("type", "string"))));
        Map<String, Object> groups = Map.of("type", "array", "items", Map.of("$ref", "#/$defs/group"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("recipeFound", Map.of("type", "boolean"));
        properties.put("title", nullableString);
        properties.put("language", nullableString);
        properties.put("servings", object(Map.of("text", nullableString, "count", nullableInteger)));
        properties.put("times", object(Map.of(
                "prepMinutes", nullableInteger, "cookMinutes", nullableInteger, "totalMinutes", nullableInteger)));
        properties.put("ingredientGroups", groups);
        properties.put("stepGroups", groups);
        properties.put("notes", Map.of("type", "array", "items", Map.of("type", "string")));

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

    /**
     * @param recipe     the recipe DTO (only fields the model filled)
     * @param provenance {@link #STAGE} for every filled field
     */
    public record Parsed(boolean recipeFound, ObjectNode recipe, Map<String, String> provenance) {
    }

    /** @throws IllegalArgumentException if the text is not a JSON object */
    public static Parsed parse(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("output is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("output is not a JSON object");
        }
        ObjectNode recipe = JsonNodeFactory.instance.objectNode();
        Map<String, String> provenance = new LinkedHashMap<>();
        boolean found = root.path("recipeFound").asBoolean(true);

        String title = text(root.get("title"));
        if (title != null) {
            recipe.put("title", title);
            provenance.put("title", STAGE);
        }
        String language = text(root.get("language"));
        if (language != null) {
            recipe.put("language", language);
            provenance.put("language", STAGE);
        }
        JsonNode servings = root.path("servings");
        String servingsText = text(servings.get("text"));
        Integer count = bounded(servings.get("count"), 1, 1000);
        if (servingsText != null || count != null) {
            ObjectNode node = recipe.putObject("servings");
            node.put("text", servingsText != null ? servingsText : String.valueOf(count));
            node.put("count", count);
            provenance.put("servings", STAGE);
        }
        JsonNode times = root.path("times");
        ObjectNode timesNode = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("prepMinutes", "cookMinutes", "totalMinutes")) {
            Integer minutes = bounded(times.get(field), 0, 7 * 24 * 60);
            if (minutes != null) {
                timesNode.put(field, minutes);
                provenance.put(field, STAGE);
            }
        }
        if (!timesNode.isEmpty()) {
            recipe.set("times", timesNode);
        }
        if (groups(root.get("ingredientGroups"), recipe.putArray("ingredientGroups"))) {
            provenance.put("ingredients", STAGE);
        }
        if (groups(root.get("stepGroups"), recipe.putArray("stepGroups"))) {
            provenance.put("steps", STAGE);
        }
        List<String> notes = strings(root.get("notes"));
        if (!notes.isEmpty()) {
            ArrayNode notesNode = recipe.putArray("notes");
            notes.forEach(notesNode::add);
            provenance.put("notes", STAGE);
        }
        return new Parsed(found, recipe, provenance);
    }

    /** Copies non-empty groups (blank items dropped); returns whether any item was copied. */
    private static boolean groups(JsonNode source, ArrayNode target) {
        boolean any = false;
        if (source == null || !source.isArray()) {
            return false;
        }
        for (JsonNode group : source) {
            List<String> items = strings(group.get("items"));
            if (items.isEmpty()) {
                continue;
            }
            ObjectNode node = target.addObject();
            node.put("name", text(group.get("name")));
            ArrayNode itemsNode = node.putArray("items");
            items.forEach(itemsNode::add);
            any = true;
        }
        return any;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                String value = text(item);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isString() || node.asString().isBlank() ? null : node.asString().strip();
    }

    /** Values outside a plausible range are dropped rather than failing the run. */
    private static Integer bounded(JsonNode node, int min, int max) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        long value = node.asLong();
        return value < min || value > max ? null : (int) value;
    }
}
