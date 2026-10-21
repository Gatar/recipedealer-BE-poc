package com.gatar.recipedealer.capture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A capture's processing state. The phone reads {@code status}, {@code recipes[].title} and {@code message};
 * {@code candidates} is present for {@code needs_decision}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureView(UUID captureId, String status, String reason, String message, List<RecipeRef> recipes,
                          List<CandidateSummary> candidates, Instant receivedAt, Instant capturedAt, String sourceType,
                          String sourceUrl, String title) {

    public record RecipeRef(UUID recipeId, String title) {
    }

    /** {@code resolution} is the stored decision ({@code NEW} / {@code UPDATE}) if the user already chose. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateSummary(int index, String title, int ingredientCount, int stepCount, boolean needsReview,
                                   List<String> urls, String resolution, UUID targetRecipeId) {
    }
}
