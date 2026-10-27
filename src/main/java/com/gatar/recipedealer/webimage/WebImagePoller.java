package com.gatar.recipedealer.webimage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with the job worker (tests disable both and call {@link WebImageService} directly). */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WebImagePoller {

    private final WebImageService images;

    public WebImagePoller(WebImageService images) {
        this.images = images;
    }

    @Scheduled(fixedDelayString = "${recipedealer.web-images.poll-interval:1m}", initialDelayString = "30s")
    public void poll() {
        images.drain();
    }
}
