package com.gatar.recipedealer.tag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tagging of new recipes by an LLM ({@link AutoTagger}). Also needs the Claude API key ({@code recipedealer.llm}):
 * without it nothing is tagged and captures do not wait for it.
 *
 * @param effort low … max
 */
@ConfigurationProperties("recipedealer.tagging")
public record AutoTagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("claude-haiku-5-5") String model,
        @DefaultValue("medium") String effort) {
}
