package com.gatar.recipedealer.photo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly removal of photos that are not current ({@link PhotoService#sweep}; runs with the job worker, tests call it
 * directly). At night, so it does not race with people editing; the purge of deleted recipes also runs it.
 */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PhotoCleanupPoller {

    private final PhotoService photos;

    public PhotoCleanupPoller(PhotoService photos) {
        this.photos = photos;
    }

    @Scheduled(cron = "${recipedealer.photos.cleanup-cron:0 30 4 * * *}")
    public void cleanup() {
        photos.sweep();
    }
}
