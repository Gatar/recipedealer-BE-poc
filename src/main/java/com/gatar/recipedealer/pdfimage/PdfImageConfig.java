package com.gatar.recipedealer.pdfimage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PdfImageProperties.class)
public class PdfImageConfig {

    @Bean
    PdfImageExtractor pdfImageExtractor(PdfImageProperties properties) {
        return new PdfImageExtractor(properties.maxEdge());
    }
}
