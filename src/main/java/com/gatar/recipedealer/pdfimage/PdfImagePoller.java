package com.gatar.recipedealer.pdfimage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with the job worker (tests disable both and call {@link PdfImageService} directly). */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PdfImagePoller {

    private final PdfImageService images;

    public PdfImagePoller(PdfImageService images) {
        this.images = images;
    }

    @Scheduled(fixedDelayString = "${recipedealer.pdf-images.poll-interval:1m}", initialDelayString = "45s")
    public void poll() {
        images.drain();
    }
}
