package com.gatar.recipedealer.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gatar.recipedealer.recipe.RecipeContent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tagging request of a new recipe: static system prompt, the household's catalog and the recipe as text, and an output
 * schema whose enum is the catalog's tag ids (so the model cannot invent tags). Changing the prompt or the schema
 * means a new {@link #VERSION}, stored with every tagging.
 */
public final class TagPrompt {

    public static final String VERSION = "t2";

    /** Byte-identical across requests (the catalog and the recipe go into the user message). */
    public static final String SYSTEM_PROMPT = """
            You assign tags to a recipe in a private household recipe collection. The household keeps its own tag \
            catalog. The user message lists every tag of the catalog as "id: category title / tag title", followed by \
            the recipe. Most titles and recipes are Polish. Return the ids of the tags that apply, as JSON that \
            follows the given schema.

            Rules:
            1. Choose only ids from the catalog.
            2. A tag applies when the recipe clearly is what the tag says, judged from its title, ingredients, steps, \
            times and notes. The category title tells what the tags in it describe (for example the meal, the kind of \
            dish, the cuisine, the diet, the occasion).
            3. Fewer tags are better than more. Choose a tag only when a family member looking for that tag would \
            clearly expect this recipe; when you are unsure, leave it out. Returning no tags is a valid answer.
            4. Usually choose at most one tag per category, two when both are equally obvious (a soup that is a \
            typical lunch and dinner). Never choose a tag just because it is not wrong.
            5. Cuisine: the cuisine the dish comes from, never the country or language of the website or of the \
            recipe's author.
            6. Tags with the same title in different categories mean different things: read the category title \
            (for example "no baking" in a dessert category is a kind of dessert, in an equipment category it is a way \
            of preparing; a dish that is simply not baked does not get either).
            7. A tag that says what a dish does not contain (vegetarian, vegan, gluten-free, lactose-free and the like) \
            applies only when no ingredient contradicts it.
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TagPrompt() {
    }

    /** One tag of the catalog; {@code id} is {@code category:tag}. */
    public record Choice(String id, String categoryTitle, String tagTitle) {
    }

    /** Output schema: the chosen ids, each one of {@code ids}. */
    public static Map<String, Object> schema(List<String> ids) {
        Map<String, Object> tags = Map.of("type", "array", "items", Map.of("type", "string", "enum", List.copyOf(ids)));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("tags", tags));
        schema.put("required", List.of("tags"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** The user message: the catalog, then the recipe (title, servings, times, ingredients, steps, notes). */
    public static String input(List<Choice> catalog, JsonNode recipe) {
        StringBuilder text = new StringBuilder("Tag catalog:\n");
        catalog.forEach(c -> text.append(c.id()).append(": ").append(c.categoryTitle()).append(" / ")
                .append(c.tagTitle()).append('\n'));
        String title = RecipeContent.title(recipe);
        text.append("\nRecipe:\nTitle: ").append(title == null ? "(none)" : title).append('\n');
        String servings = recipe.path("servings").path("text").asString("");
        if (!servings.isBlank()) {
            text.append("Servings: ").append(servings.strip()).append('\n');
        }
        JsonNode times = recipe.path("times");
        for (String field : List.of("prepMinutes", "cookMinutes", "totalMinutes")) {
            if (times.path(field).isIntegralNumber()) {
                text.append(field).append(": ").append(times.path(field).asLong()).append('\n');
            }
        }
        list(text, "Ingredients", RecipeContent.ingredientLines(recipe));
        list(text, "Steps", RecipeContent.stepLines(recipe));
        List<String> notes = new ArrayList<>();
        recipe.path("notes").forEach(note -> {
            if (note.isString() && !note.asString().isBlank()) {
                notes.add(note.asString().strip());
            }
        });
        list(text, "Notes", notes);
        return text.toString();
    }

    private static void list(StringBuilder text, String heading, List<String> lines) {
        if (!lines.isEmpty()) {
            text.append(heading).append(":\n");
            lines.forEach(line -> text.append("- ").append(line).append('\n'));
        }
    }

    /**
     * The chosen ids in {@code allowed}'s order, deduplicated; ids outside {@code allowed} are dropped.
     *
     * @throws IllegalArgumentException if the output is not a JSON object with a {@code tags} array
     */
    public static List<String> parse(String text, List<String> allowed) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("output is not valid JSON", e);
        }
        if (root == null || !root.isObject() || !root.path("tags").isArray()) {
            throw new IllegalArgumentException("output has no tags array");
        }
        Set<String> chosen = new LinkedHashSet<>();
        root.path("tags").forEach(id -> {
            if (id.isString()) {
                chosen.add(id.asString());
            }
        });
        return allowed.stream().filter(chosen::contains).toList();
    }
}
