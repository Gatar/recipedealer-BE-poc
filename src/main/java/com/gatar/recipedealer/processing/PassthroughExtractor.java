package com.gatar.recipedealer.processing;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** v1: takes the recipe the phone extracted as it is. */
@Component
public class PassthroughExtractor implements RecipeExtractor {

    @Override
    public String name() {
        return "passthrough";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public ExtractionResult extract(Capture capture) {
        ObjectNode recipe = capture.recipe().isObject()
                ? (ObjectNode) capture.recipe().deepCopy()
                : JsonNodeFactory.instance.objectNode();
        return new ExtractionResult(List.of(new RecipeCandidate(recipe, capture.provenance())));
    }
}
