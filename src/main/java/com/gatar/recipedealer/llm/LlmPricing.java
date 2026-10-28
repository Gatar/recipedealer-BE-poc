package com.gatar.recipedealer.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Cost of one call from the configured prices (USD per million tokens). Computed and stored when a run finishes, so a
 * later price change does not rewrite history.
 */
@Component
public class LlmPricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmProperties properties;

    public LlmPricing(LlmProperties properties) {
        this.properties = properties;
    }

    public boolean knows(String model) {
        return properties.pricing().containsKey(model);
    }

    /** @return null for a call without usage or for a model without configured prices */
    public BigDecimal cost(LlmUsage usage) {
        if (usage == null || !knows(usage.model())) {
            return null;
        }
        LlmProperties.Price price = properties.pricing().get(usage.model());
        BigDecimal input = price.input();
        BigDecimal cacheWrite = price.cacheWrite();
        BigDecimal cacheRead = price.cacheRead();
        BigDecimal output = price.output();
        LlmProperties.LongContext longContext = price.longContext();
        if (longContext != null && usage.promptTokens() > longContext.aboveInputTokens()) {
            input = longContext.input();
            cacheWrite = longContext.cacheWrite();
            cacheRead = longContext.cacheRead();
            output = longContext.output();
        }
        return input.multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(cacheWrite.multiply(BigDecimal.valueOf(usage.cacheCreationInputTokens())))
                .add(cacheRead.multiply(BigDecimal.valueOf(usage.cacheReadInputTokens())))
                .add(output.multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
