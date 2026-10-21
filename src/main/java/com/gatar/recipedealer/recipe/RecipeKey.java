package com.gatar.recipedealer.recipe;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Identity key of a recipe: one web page = one recipe. */
public record RecipeKey(Kind kind, String value, String rawValue, int normalizerVersion) {

    public enum Kind { CANONICAL_URL, JSONLD_ID }

    /**
     * Keys of a capture: the canonical URL ({@code raw.web.canonicalUrl}, else {@code source.url}; a screenshot
     * shared with its page URL uses that URL) and an absolute JSON-LD {@code @id} ({@code raw.web.jsonLdRecipeId}).
     */
    public static List<RecipeKey> fromCapture(JsonNode capture) {
        List<RecipeKey> keys = new ArrayList<>();
        JsonNode web = capture.path("raw").path("web");
        String url = text(web.get("canonicalUrl"));
        if (url == null || UrlNormalizer.normalize(url).isEmpty()) {
            url = text(capture.path("source").get("url"));
        }
        String rawUrl = url;
        UrlNormalizer.normalize(rawUrl)
                .ifPresent(value -> keys.add(new RecipeKey(Kind.CANONICAL_URL, value, rawUrl, UrlNormalizer.VERSION)));
        String jsonLdId = text(web.get("jsonLdRecipeId"));
        UrlNormalizer.normalizeKeepingFragment(jsonLdId)
                .ifPresent(value -> keys.add(new RecipeKey(Kind.JSONLD_ID, value, jsonLdId, UrlNormalizer.VERSION)));
        return keys;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isString() || node.asString().isBlank() ? null : node.asString();
    }
}
