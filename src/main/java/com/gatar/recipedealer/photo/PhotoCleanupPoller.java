package com.gatar.recipedealer.photo;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily removal of uploaded photos nothing refers to (runs with the job worker; tests call
 * {@link PhotoService#deleteUnused} directly). The grace period covers a capture with a photo that waits on the phone
 * before it is sent.
 */
@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PhotoCleanupPoller {

    private final PhotoService photos;
    private final Duration grace;

    public PhotoCleanupPoller(PhotoService photos, @Value("${recipedealer.photos.unused-grace:7d}") Duration grace) {
        this.photos = photos;
        this.grace = grace;
    }

    @Scheduled(cron = "${recipedealer.photos.cleanup-cron:0 30 4 * * *}")
    public void cleanup() {
        photos.deleteUnused(grace);
    }
}
