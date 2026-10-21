package com.gatar.recipedealer.capture;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code GET /v1/captures/{id}}; the phone reads {@code status}, {@code recipes[].title} and {@code message}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureStatusView(UUID captureId, String status, List<RecipeRef> recipes, String message) {

    public record RecipeRef(UUID recipeId, String title) {
    }
}
