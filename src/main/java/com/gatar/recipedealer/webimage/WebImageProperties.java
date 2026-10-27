package com.gatar.recipedealer.webimage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Downloads of recipe photos from other sites. {@code allowPrivateAddresses} is for tests only: by default the server
 * never fetches from loopback, private or link-local addresses (its own containers, the cloud metadata service).
 */
@ConfigurationProperties("recipedealer.web-images")
public record WebImageProperties(
        @DefaultValue("1m") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("10MB") DataSize maxSize,
        @DefaultValue("20s") Duration timeout,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1h") Duration retryBase,
        @DefaultValue("false") boolean allowPrivateAddresses) {
}
