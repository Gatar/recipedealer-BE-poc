package com.gatar.recipedealer.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Translation of new recipes by an LLM ({@link AutoTranslator}), for captures uploaded with {@code Translate-To}. Also
 * needs the Claude API key ({@code recipedealer.llm}): without it nothing is translated and captures do not wait.
 *
 * @param effort low … max
 */
@ConfigurationProperties("recipedealer.translation")
public record AutoTranslationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("claude-haiku-5-5") String model,
        @DefaultValue("high") String effort) {
}
