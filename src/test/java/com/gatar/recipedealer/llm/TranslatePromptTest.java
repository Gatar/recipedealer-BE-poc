package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TranslatePromptTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final JsonNode RECIPE = JSON.readTree("""
            {"title": "Pancakes", "language": "en", "servings": {"text": "4 servings", "count": 4},
             "times": {"prepMinutes": 10, "cookMinutes": 20, "totalMinutes": null},
             "ingredientGroups": [{"name": null, "items": ["1 cup flour", "1 egg"]}],
             "stepGroups": [{"name": "Batter", "items": ["Whisk."]}], "notes": [],
             "images": ["https://example.com/p.jpg"]}""");

    @Test
    void inputHasTheTargetLanguageAndOnlyTheTexts() {
        String input = TranslatePrompt.input("pl", RECIPE);

        assertThat(input).startsWith("Target language: Polish (pl)\n\nRecipe:\n")
                .contains("\"servingsText\" : \"4 servings\"").contains("1 cup flour")
                .doesNotContain("images").doesNotContain("prepMinutes");
    }

    @Test
    void appliesTheTextsAndKeepsEverythingElse() {
        var parsed = TranslatePrompt.parse("""
                {"sourceLanguage": "en", "alreadyInTarget": false, "title": "Naleśniki", "servingsText": "4 porcje",
                 "ingredientGroups": [{"name": null, "items": ["1 szklanka (cup) mąki", "1 jajko"]}],
                 "stepGroups": [{"name": "Ciasto", "items": ["Roztrzepać."]}], "notes": []}""", RECIPE, "pl");

        assertThat(parsed.alreadyInTarget()).isFalse();
        assertThat(parsed.sourceLanguage()).isEqualTo("en");
        assertThat(parsed.recipe().get("title").asString()).isEqualTo("Naleśniki");
        assertThat(parsed.recipe().get("language").asString()).isEqualTo("pl");
        assertThat(parsed.recipe().at("/servings/count").asInt()).isEqualTo(4);
        assertThat(parsed.recipe().at("/times/cookMinutes").asInt()).isEqualTo(20);
        assertThat(parsed.recipe().at("/images/0").asString()).isEqualTo("https://example.com/p.jpg");
        assertThat(parsed.recipe().at("/stepGroups/0/name").asString()).isEqualTo("Ciasto");
        assertThat(RECIPE.get("title").asString()).isEqualTo("Pancakes");
    }

    @Test
    void alreadyInTheTargetLanguage() {
        var parsed = TranslatePrompt.parse("""
                {"sourceLanguage": "pl", "alreadyInTarget": true, "title": null, "servingsText": null,
                 "ingredientGroups": [], "stepGroups": [], "notes": []}""", RECIPE, "pl");

        assertThat(parsed.alreadyInTarget()).isTrue();
        assertThat(parsed.recipe()).isNull();
    }

    @Test
    void rejectsAChangedStructure() {
        assertThatThrownBy(() -> TranslatePrompt.parse("""
                {"sourceLanguage": "en", "alreadyInTarget": false, "title": "Naleśniki", "servingsText": "4 porcje",
                 "ingredientGroups": [{"name": null, "items": ["mąka, jajko"]}],
                 "stepGroups": [{"name": "Ciasto", "items": ["Roztrzepać."]}], "notes": []}""", RECIPE, "pl"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ingredientGroups[0]");
        assertThatThrownBy(() -> TranslatePrompt.parse("""
                {"sourceLanguage": "en", "alreadyInTarget": false, "title": "Naleśniki", "servingsText": "4 porcje",
                 "ingredientGroups": [{"name": null, "items": ["1 szklanka mąki", "1 jajko"]}],
                 "stepGroups": [{"name": null, "items": ["Roztrzepać."]}], "notes": []}""", RECIPE, "pl"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stepGroups[0]");
        assertThatThrownBy(() -> TranslatePrompt.parse("no json", RECIPE, "pl")).isInstanceOf(IllegalArgumentException.class);
    }
}
