package com.gatar.recipedealer.processing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("recipedealer.worker")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration backoffBase,
        @DefaultValue("1h") Duration backoffMax) {

    /** Delay before the next attempt after {@code attempts} failed attempts: base × 2^(attempts-1), capped. */
    public Duration backoff(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = backoffBase.multipliedBy(factor);
        return delay.compareTo(backoffMax) > 0 ? backoffMax : delay;
    }
}
