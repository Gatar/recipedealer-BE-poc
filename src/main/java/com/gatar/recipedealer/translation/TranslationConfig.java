package com.gatar.recipedealer.translation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutoTranslationProperties.class)
public class TranslationConfig {
}
