package com.gatar.recipedealer.tag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutoTagProperties.class)
public class TagConfig {
}
