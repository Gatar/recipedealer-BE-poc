package com.gatar.recipedealer.pdfimage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Search for the photo in PDF attachments. */
@ConfigurationProperties("recipedealer.pdf-images")
public record PdfImageProperties(
        @DefaultValue("1m") Duration pollInterval,
        @DefaultValue("5") int batchSize,
        @DefaultValue("" + com.gatar.recipedealer.common.Images.PHOTO_MAX_EDGE) int maxEdge) {
}
