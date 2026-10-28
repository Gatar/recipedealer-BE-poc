package com.gatar.recipedealer.llm;

/** Usage of one Messages API call as reported by the API; {@code model} is the model that answered. */
public record LlmUsage(String model, String stopReason, long inputTokens, long cacheCreationInputTokens,
                       long cacheReadInputTokens, long outputTokens, long latencyMs) {

    /** All input tokens of the prompt, cached or not (decides long-context pricing). */
    public long promptTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    /** This call and a follow-up call of the same step together; the stop reason stays this call's. */
    public LlmUsage plus(LlmUsage other) {
        return other == null ? this : new LlmUsage(model, stopReason, inputTokens + other.inputTokens,
                cacheCreationInputTokens + other.cacheCreationInputTokens, cacheReadInputTokens + other.cacheReadInputTokens,
                outputTokens + other.outputTokens, latencyMs + other.latencyMs);
    }
}
