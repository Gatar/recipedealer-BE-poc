package com.gatar.recipedealer.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.gatar.recipedealer.processing.Capture;
import com.gatar.recipedealer.processing.ExtractionResult;
import com.gatar.recipedealer.processing.RecipeCandidate;
import com.gatar.recipedealer.processing.RecipeExtractor;

import tools.jackson.databind.node.JsonNodeFactory;

/** One LLM step of the cascade: one model, the shared prompt. The version names the model and the prompt version. */
public class ClaudeExtractor implements RecipeExtractor {

    public static final String NAME = "claude";

    private final LlmProperties.Step step;
    private final ClaudeClient client;
    private final LlmInputBuilder inputs;

    public ClaudeExtractor(LlmProperties.Step step, ClaudeClient client, LlmInputBuilder inputs) {
        this.step = step;
        this.client = client;
        this.inputs = inputs;
    }

    public LlmProperties.Step step() {
        return step;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return step.model() + "/" + RecipePrompt.VERSION;
    }

    /** @throws LlmException when the API call fails (see {@link LlmException.Kind}) */
    @Override
    public ExtractionResult extract(Capture capture) throws Exception {
        var request = new ClaudeClient.Request(step.model(), step.effort(), RecipePrompt.SYSTEM_PROMPT,
                RecipePrompt.schema(), inputs.build(capture));
        ClaudeClient.Response response = client.extract(request);

        List<String> problems = new ArrayList<>();
        String stopReason = response.usage().stopReason();
        if (response.refusalCategory() != null) {
            problems.add("refusal:" + response.refusalCategory());
        } else if ("max_tokens".equals(stopReason)) {
            problems.add("max_tokens");
        } else if ("model_context_window_exceeded".equals(stopReason)) {
            problems.add("context_window_exceeded");
        }
        RecipeCandidate candidate = new RecipeCandidate(JsonNodeFactory.instance.objectNode(), Map.of());
        if (problems.isEmpty()) {
            try {
                RecipePrompt.Parsed parsed = RecipePrompt.parse(response.text());
                candidate = new RecipeCandidate(parsed.recipe(), parsed.provenance());
                if (!parsed.recipeFound()) {
                    problems.add("no_recipe_found");
                }
            } catch (IllegalArgumentException e) {
                problems.add("invalid_output");
            }
        }
        return new ExtractionResult(List.of(candidate), response.usage(), problems);
    }
}
