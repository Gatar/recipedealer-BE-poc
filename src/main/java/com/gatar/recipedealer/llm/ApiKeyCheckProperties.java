package com.gatar.recipedealer.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Dead man's switch for the API key: a scheduled minimal request; the URL (e.g. a healthchecks.io check) is pinged
 * only when it succeeds. Without {@code url} the scheduled check is disabled.
 *
 * @param model the model to call; empty: the model of the first LLM step
 */
@ConfigurationProperties("recipedealer.deadman.api-key")
public record ApiKeyCheckProperties(
        String url,
        @DefaultValue("0 0 6 * * *") String cron,
        @DefaultValue("10s") Duration timeout,
        String model) {

    public boolean enabled() {
        return url != null && !url.isBlank();
    }
}
