package com.gatar.recipedealer.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code autoNew}: a candidate without a decision and without any existing recipe it could belong to (same identity
 * key or similar title) becomes a new recipe without asking.
 */
@ConfigurationProperties("recipedealer.processing")
public record ProcessingProperties(@DefaultValue("true") boolean autoNew) {
}
