package com.gatar.recipedealer.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "recipedealer.worker.enabled", havingValue = "true", matchIfMissing = true)
public class JobPoller {

    private final JobWorker worker;

    public JobPoller(JobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${recipedealer.worker.poll-interval:1s}")
    public void poll() {
        worker.drain();
    }
}
