package com.gatar.recipedealer.purge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.photo.PhotoService;

/**
 * Purges deleted recipes and households a few minutes after the delete, and converts the remaining JPEG photos to
 * WebP (runs with the job worker; tests call {@link PurgeService} and {@link PhotoService} directly).
 */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PurgePoller {

    static final int BATCH = 50;

    private final PurgeService purge;
    private final PhotoService photos;

    public PurgePoller(PurgeService purge, PhotoService photos) {
        this.purge = purge;
        this.photos = photos;
    }

    @Scheduled(initialDelayString = "${recipedealer.purge.initial-delay:1m}", fixedDelayString = "${recipedealer.purge.interval:5m}")
    public void run() {
        while (purge.purge(BATCH) == BATCH) {
            // more are waiting
        }
        photos.convertToWebp(BATCH);
    }
}
