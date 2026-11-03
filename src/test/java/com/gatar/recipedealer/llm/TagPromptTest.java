package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class TagPromptTest {

    static final List<String> CATALOG = List.of("meal:breakfast", "meal:dinner", "diet:vegetarian");

    @Test
    void keepsCatalogIdsInCatalogOrderOnce() {
        assertThat(TagPrompt.parse("{\"tags\": [\"diet:vegetarian\", \"made:up\", \"meal:breakfast\", \"diet:vegetarian\"]}",
                CATALOG)).containsExactly("meal:breakfast", "diet:vegetarian");
        assertThat(TagPrompt.parse("{\"tags\": []}", CATALOG)).isEmpty();
    }

    @Test
    void rejectsOutputWithoutATagsArray() {
        assertThatThrownBy(() -> TagPrompt.parse("not json", CATALOG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagPrompt.parse("{\"tag\": []}", CATALOG)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputListsTheCatalogAndTheRecipe() {
        var recipe = JsonMapper.builder().build().readTree("""
                {"title": "Owsianka", "servings": {"text": "2 porcje", "count": 2},
                 "times": {"prepMinutes": null, "cookMinutes": 10, "totalMinutes": null},
                 "ingredientGroups": [{"name": null, "items": ["50 g płatków", "200 ml mleka"]}],
                 "stepGroups": [{"name": null, "items": ["Zagotować mleko."]}], "notes": ["Z owocami."]}""");

        String input = TagPrompt.input(List.of(new TagPrompt.Choice("meal:breakfast", "Posiłek", "Śniadanie")), recipe);

        assertThat(input).isEqualTo("""
                Tag catalog:
                meal:breakfast: Posiłek / Śniadanie

                Recipe:
                Title: Owsianka
                Servings: 2 porcje
                cookMinutes: 10
                Ingredients:
                - 50 g płatków
                - 200 ml mleka
                Steps:
                - Zagotować mleko.
                Notes:
                - Z owocami.
                """);
    }
}
