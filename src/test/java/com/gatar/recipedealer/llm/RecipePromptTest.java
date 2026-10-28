package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RecipePromptTest {

    @Test
    void outputIsMappedToTheRecipeDtoWithLlmProvenance() {
        var parsed = RecipePrompt.parse("""
                {"recipeFound": true, "title": " Szarlotka babci ", "language": "pl",
                 "servings": {"text": "12 kawałków", "count": 12},
                 "times": {"prepMinutes": 30, "cookMinutes": 50, "totalMinutes": null},
                 "ingredientGroups": [{"name": "Ciasto", "items": ["300 g mąki", " ", "200 g masła"]},
                                      {"name": "Pusty", "items": []}],
                 "stepGroups": [{"name": null, "items": ["Zagnieść ciasto.", "Piec 50 minut."]}],
                 "notes": ["Najlepsza na drugi dzień."]}""");

        assertThat(parsed.recipeFound()).isTrue();
        assertThat(JsonMapper.builder().build().writeValueAsString(parsed.recipe())).isEqualTo("""
                {"title":"Szarlotka babci","language":"pl","servings":{"text":"12 kawałków","count":12},\
                "times":{"prepMinutes":30,"cookMinutes":50},\
                "ingredientGroups":[{"name":"Ciasto","items":["300 g mąki","200 g masła"]}],\
                "stepGroups":[{"name":null,"items":["Zagnieść ciasto.","Piec 50 minut."]}],\
                "notes":["Najlepsza na drugi dzień."]}""");
        assertThat(parsed.provenance()).containsOnlyKeys("title", "language", "servings", "prepMinutes", "cookMinutes",
                "ingredients", "steps", "notes").allSatisfy((field, stage) -> assertThat(stage).isEqualTo("llm"));
    }

    @Test
    void implausibleNumbersAreDroppedNotFailed() {
        var parsed = RecipePrompt.parse("""
                {"recipeFound": true, "title": "X", "servings": {"text": null, "count": 0},
                 "times": {"prepMinutes": -5, "cookMinutes": 999999, "totalMinutes": 20},
                 "ingredientGroups": [], "stepGroups": [], "notes": []}""");

        assertThat(parsed.recipe().has("servings")).isFalse();
        assertThat(parsed.recipe().path("times").propertyNames()).containsExactly("totalMinutes");
    }

    @Test
    void noRecipeFoundAndInvalidOutputAreReported() {
        assertThat(RecipePrompt.parse("{\"recipeFound\": false, \"title\": null}").recipeFound()).isFalse();
        assertThatThrownBy(() -> RecipePrompt.parse("{\"title\": \"cut off")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipePrompt.parse("[]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dishPhotoBoxIsReadWhenFoundAndPlausible() {
        assertThat(RecipePrompt.parse("""
                {"recipeFound": true, "dishPhoto": {"found": true, "image": 2, "left": 0, "top": 120, "right": 1000, "bottom": 480,
                 "rotation": 270}}""")
                .dishPhoto()).isEqualTo(new RecipePrompt.DishPhoto(2, new RecipePrompt.Box(0, 120, 1000, 480), 270));
        assertThat(RecipePrompt.parse("""
                {"recipeFound": true, "dishPhoto": {"found": true, "image": 1, "left": 0, "top": 0, "right": 10, "bottom": 10,
                 "rotation": 45}}""")
                .dishPhoto().rotation()).isZero();
        assertThat(RecipePrompt.parse("""
                {"recipeFound": true, "dishPhoto": {"found": false, "image": 0, "left": 0, "top": 0, "right": 0, "bottom": 0}}""")
                .dishPhoto()).isNull();
        assertThat(RecipePrompt.parse("""
                {"recipeFound": true, "dishPhoto": {"found": true, "image": 1, "left": 500, "top": 0, "right": 400, "bottom": 300}}""")
                .dishPhoto()).isNull();
        assertThat(RecipePrompt.parse("{\"recipeFound\": true}").dishPhoto()).isNull();
    }

    @Test
    void secondLookAtTheDishPhotoIsParsed() {
        assertThat(RecipePrompt.parseDishPhotoBox("""
                {"found": true, "left": 10, "top": 20, "right": 900, "bottom": 700}"""))
                .contains(new RecipePrompt.Box(10, 20, 900, 700));
        assertThat(RecipePrompt.parseDishPhotoBox("""
                {"found": false, "left": 0, "top": 0, "right": 0, "bottom": 0}""")).isEmpty();
        assertThatThrownBy(() -> RecipePrompt.parseDishPhotoBox("""
                {"found": true, "left": 900, "top": 20, "right": 10, "bottom": 700}"""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipePrompt.parseDishPhotoBox("{\"found\": tr")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaFollowsTheStructuredOutputRules() {
        Map<String, Object> schema = RecipePrompt.schema();
        assertThat(schema).containsEntry("additionalProperties", false);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat((List<String>) schema.get("required")).containsExactlyElementsOf(properties.keySet());
        assertThat(properties).doesNotContainKey("images");
        Map<String, Object> group = (Map<String, Object>) ((Map<String, Object>) schema.get("$defs")).get("group");
        assertThat(group).containsEntry("additionalProperties", false);
        Map<String, Object> dishPhoto = (Map<String, Object>) properties.get("dishPhoto");
        assertThat(dishPhoto).containsEntry("additionalProperties", false);
        assertThat((List<String>) dishPhoto.get("required"))
                .containsExactly("found", "image", "left", "top", "right", "bottom", "rotation");
        Map<String, Object> secondLook = RecipePrompt.dishPhotoSchema();
        assertThat(secondLook).containsEntry("additionalProperties", false);
        assertThat((List<String>) secondLook.get("required")).containsExactly("found", "left", "top", "right", "bottom");
    }

    @Test
    void systemPromptIsStaticAndLongEnoughToCache() {
        // The minimum cacheable prefix of Claude Haiku 5.5 / Sonnet 5.5 is 512 tokens (~4 characters per token).
        assertThat(RecipePrompt.SYSTEM_PROMPT.length()).isGreaterThan(512 * 4);
        assertThat(RecipePrompt.SYSTEM_PROMPT).doesNotContain("%s").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }
}
