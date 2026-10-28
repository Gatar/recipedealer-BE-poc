package com.gatar.recipedealer.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled API key check. With virtual threads Spring runs every scheduled task on its own thread, so this never
 * waits for the job worker. Disabled without a dead man's switch URL (tests disable it with the worker).
 */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ApiKeyCheckPoller {

    private final ApiKeyCheck check;
    private final ApiKeyCheckProperties properties;

    public ApiKeyCheckPoller(ApiKeyCheck check, ApiKeyCheckProperties properties) {
        this.check = check;
        this.properties = properties;
    }

    @Scheduled(cron = "${recipedealer.deadman.api-key.cron:0 0 6 * * *}")
    public void poll() {
        if (properties.enabled()) {
            check.check();
        }
    }
}
