package com.gatar.recipedealer.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gatar.recipedealer.llm.ClaudeClient;
import com.gatar.recipedealer.llm.ClaudeExtractor;
import com.gatar.recipedealer.llm.DishPhotoCropper;
import com.gatar.recipedealer.llm.LlmInputBuilder;
import com.gatar.recipedealer.llm.LlmProperties;

/**
 * The extraction steps in order: the heuristic (the phone's result), then the configured LLM steps (Claude Haiku,
 * Claude Sonnet). A step runs only when the previous one failed validation. Without an API key, or for a capture
 * without anything an LLM could read, the cascade ends after the heuristic.
 */
@Component
public class ExtractionCascade {

    public static final String HEURISTIC = "heuristic";

    private final PassthroughExtractor heuristic;
    private final List<ClaudeExtractor> llmSteps = new ArrayList<>();
    private final LlmInputBuilder inputs;

    public ExtractionCascade(PassthroughExtractor heuristic, LlmProperties properties, ClaudeClient client,
                             LlmInputBuilder inputs, DishPhotoCropper photos) {
        this.heuristic = heuristic;
        this.inputs = inputs;
        if (properties.enabled()) {
            properties.steps().forEach(step -> llmSteps.add(new ClaudeExtractor(step, client, inputs, photos)));
        }
    }

    public boolean llmEnabled() {
        return !llmSteps.isEmpty();
    }

    public List<String> llmStepNames() {
        return llmSteps.stream().map(extractor -> extractor.step().name()).toList();
    }

    public String firstLlmStep() {
        return llmSteps.getFirst().step().name();
    }

    public boolean hasLlmInput(Capture capture) {
        return inputs.hasInput(capture);
    }

    /** @throws IllegalArgumentException for a step that is not configured (anymore) */
    public RecipeExtractor extractor(String step) {
        if (HEURISTIC.equals(step)) {
            return heuristic;
        }
        return llmSteps.stream().filter(extractor -> extractor.step().name().equals(step)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("extraction step '" + step + "' is not configured"));
    }

    /** The step after {@code step}, if there is one and the capture has input for it. */
    public Optional<String> next(String step, Capture capture) {
        List<String> order = new ArrayList<>();
        order.add(HEURISTIC);
        order.addAll(llmStepNames());
        int index = order.indexOf(step);
        if (index < 0 || index + 1 >= order.size() || !inputs.hasInput(capture)) {
            return Optional.empty();
        }
        return Optional.of(order.get(index + 1));
    }
}
