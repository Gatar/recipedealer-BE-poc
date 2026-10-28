package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmPricingTest {

    private static BigDecimal usd(String value) {
        return new BigDecimal(value);
    }

    private final LlmPricing pricing = new LlmPricing(new LlmProperties("key", Duration.ofSeconds(1), 0, 1, 1, 1, 1,
            List.of(), Map.of(
            "claude-haiku-5-5", new LlmProperties.Price(usd("0.10"), usd("0.125"), usd("0.01"), usd("0.50"),
                    new LlmProperties.LongContext(100_000, usd("0.50"), usd("0.625"), usd("0.05"), usd("2.50"))),
            "claude-sonnet-5-5", new LlmProperties.Price(usd("2.00"), usd("2.50"), usd("0.10"), usd("10.00"), null))));

    private static LlmUsage usage(String model, long input, long cacheWrite, long cacheRead, long output) {
        return new LlmUsage(model, "end_turn", input, cacheWrite, cacheRead, output, 100);
    }

    @Test
    void costAddsEveryTokenKindAtItsPrice() {
        // 2000 × 2.00 + 1000 × 2.50 + 4000 × 0.10 + 3000 × 10.00 = 36,900 USD per million tokens
        assertThat(pricing.cost(usage("claude-sonnet-5-5", 2000, 1000, 4000, 3000))).isEqualByComparingTo("0.036900");
    }

    @Test
    void haikuPromptsOverOneHundredThousandTokensUseTheLongContextPrices() {
        assertThat(pricing.cost(usage("claude-haiku-5-5", 90_000, 10_000, 0, 1000))).isEqualByComparingTo("0.010750");
        assertThat(pricing.cost(usage("claude-haiku-5-5", 90_000, 10_001, 0, 1000))).isEqualByComparingTo("0.053751");
    }

    @Test
    void unknownModelOrNoUsageHasNoCost() {
        assertThat(pricing.cost(usage("claude-opus-5-5", 1, 1, 1, 1))).isNull();
        assertThat(pricing.cost(null)).isNull();
    }
}
