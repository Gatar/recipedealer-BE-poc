package com.gatar.recipedealer.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.capture.CaptureStatus;

/**
 * Runs queued jobs; failures are retried with backoff and end in {@code needs_attention}. A
 * {@link NonRetryableProcessingException} ends the job at once. Comparison jobs never change the capture state.
 */
@Component
public class JobWorker {

    public static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final ProcessingService processing;
    private final ProcessingRepository repository;
    private final TransactionTemplate tx;

    public JobWorker(JobQueue queue, ProcessingService processing, ProcessingRepository repository, TransactionTemplate tx) {
        this.queue = queue;
        this.processing = processing;
        this.repository = repository;
        this.tx = tx;
    }

    /** Runs all jobs that are due; returns how many were run. */
    public int drain() {
        int count = 0;
        while (runOnce()) {
            count++;
        }
        return count;
    }

    /** @return false if no job was due */
    public boolean runOnce() {
        var claimed = queue.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var job = claimed.get();
        boolean comparison = job.purpose() == Purpose.COMPARISON;
        try {
            processing.process(job.task());
            queue.complete(job.id());
        } catch (NonRetryableProcessingException e) {
            queue.fail(job, e.reason() + ": " + e.getMessage(), true);
            log.error("Job {} for capture {} ({} step {}) failed and will not be retried: {}", job.id(), job.captureId(),
                    job.purpose().value(), job.step(), e.getMessage());
            if (!comparison) {
                tx.executeWithoutResult(status -> repository.setState(job.captureId(), CaptureStatus.NEEDS_ATTENTION,
                        e.reason(), e.getMessage()));
            }
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            var failure = queue.fail(job, error);
            if (failure.terminal()) {
                log.error("Job {} for capture {} failed {} times, giving up", job.id(), job.captureId(), failure.attempts(), e);
                if (!comparison) {
                    tx.executeWithoutResult(status -> repository.setState(job.captureId(), CaptureStatus.NEEDS_ATTENTION,
                            PROCESSING_FAILED, "Processing failed after " + failure.attempts() + " attempts: " + error));
                }
            } else {
                log.warn("Job {} for capture {} failed (attempt {}), will retry", job.id(), job.captureId(), failure.attempts(), e);
            }
        }
        return true;
    }
}
