package com.gatar.recipedealer.processing;

/**
 * Turns a capture into recipe candidates. Implementations must be deterministic for a given version as far as
 * possible; the name and version are stored on every processing run.
 */
public interface RecipeExtractor {

    String name();

    String version();

    ExtractionResult extract(Capture capture) throws Exception;
}
