package com.gatar.recipedealer.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Claude API settings. Without {@code apiKey} the LLM steps are disabled and the cascade stops after the heuristic.
 * {@code timeout} × ({@code maxRetries} + 1) must stay below the job lease ({@code recipedealer.worker.lease}).
 *
 * @param steps   LLM steps of the cascade, in order (each runs only when the previous one fails validation)
 * @param pricing USD per million tokens, per model id
 */
@ConfigurationProperties("recipedealer.llm")
public record LlmProperties(
        String apiKey,
        @DefaultValue("150s") Duration timeout,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("16000") long maxTokens,
        // The API accepts 32 MB per request; base64 and the JSON envelope count towards it.
        @DefaultValue("30000000") long maxRequestBytes,
        // Long edge after downscaling; at most 2000 px also satisfies the limit for requests with over 20 images.
        @DefaultValue("2000") int maxImageEdge,
        @DefaultValue("400000") int maxTextChars,
        List<Step> steps,
        Map<String, Price> pricing) {

    public LlmProperties {
        steps = steps == null ? List.of() : List.copyOf(steps);
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param name step name stored on runs and jobs, e.g. {@code haiku}; {@code effort}: low … max */
    public record Step(String name, String model, @DefaultValue("medium") String effort) {
    }

    /**
     * @param cacheWrite  5-minute cache writes
     * @param longContext prices for prompts above a size (Claude Haiku 5.5: over 100,000 tokens), if the model has them
     */
    public record Price(BigDecimal input, BigDecimal cacheWrite, BigDecimal cacheRead, BigDecimal output,
                       LongContext longContext) {
    }

    /** @param aboveInputTokens applies when input + cache write + cache read tokens exceed this */
    public record LongContext(long aboveInputTokens, BigDecimal input, BigDecimal cacheWrite, BigDecimal cacheRead,
                              BigDecimal output) {
    }
}
