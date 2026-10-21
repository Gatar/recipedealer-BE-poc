package com.gatar.recipedealer.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ProvenanceGuardTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Capture capture(String json) {
        return new Capture(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(), 1, "0".repeat(64),
                JSON.readTree(json), List.of());
    }

    @Test
    void userFieldsAreRestoredFromTheCapture() {
        Capture capture = capture("""
                {"recipe": {"title": "Babcine pierogi", "times": {"prepMinutes": 40},
                            "ingredientGroups": [{"items": ["mąka"]}]},
                 "provenance": {"title": "user", "prepMinutes": "user", "ingredients": "ocr"}}""");
        ObjectNode extracted = (ObjectNode) JSON.readTree("""
                {"title": "Pierogi", "times": {"prepMinutes": 30, "cookMinutes": 10},
                 "ingredientGroups": [{"items": ["mąka", "woda"]}]}""");

        RecipeCandidate guarded = ProvenanceGuard.apply(capture, new RecipeCandidate(extracted,
                Map.of("title", "llm", "prepMinutes", "llm", "ingredients", "llm")));

        assertThat(guarded.recipe().get("title").asString()).isEqualTo("Babcine pierogi");
        assertThat(guarded.recipe().at("/times/prepMinutes").asInt()).isEqualTo(40);
        assertThat(guarded.recipe().at("/times/cookMinutes").asInt()).isEqualTo(10);
        assertThat(guarded.recipe().at("/ingredientGroups/0/items")).hasSize(2);
        assertThat(guarded.provenance()).containsEntry("title", "user").containsEntry("ingredients", "llm");
    }

    @Test
    void fieldTheUserClearedStaysCleared() {
        Capture capture = capture("""
                {"recipe": {"title": "Sernik"}, "provenance": {"title": "user", "notes": "user"}}""");
        ObjectNode extracted = (ObjectNode) JSON.readTree("""
                {"title": "Sernik", "notes": ["from the page"]}""");

        RecipeCandidate guarded = ProvenanceGuard.apply(capture, new RecipeCandidate(extracted, Map.of()));

        assertThat(guarded.recipe().has("notes")).isFalse();
    }

    @Test
    void passthroughKeepsTheRecipeUnchanged() {
        Capture capture = capture("""
                {"recipe": {"title": "Sernik", "stepGroups": [{"items": ["Upiec."]}]},
                 "provenance": {"title": "user", "steps": "jsonLd"}}""");
        RecipeCandidate candidate = new PassthroughExtractor().extract(capture).candidates().getFirst();

        assertThat(ProvenanceGuard.apply(capture, candidate).recipe()).isEqualTo(capture.recipe());
    }
}
