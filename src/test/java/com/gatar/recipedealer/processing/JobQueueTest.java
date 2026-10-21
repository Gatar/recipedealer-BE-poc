package com.gatar.recipedealer.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;

@IntegrationTest
class JobQueueTest {

    @Autowired
    JobQueue queue;

    @Autowired
    JobWorker worker;

    @Autowired
    WorkerProperties properties;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    UUID captureId;

    @BeforeEach
    void setUp() {
        String token = TestAuth.enroll(mvc, "Jobs", "phone");
        worker.drain();
        captureId = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID()));
    }

    private JobQueue.Job claimOwn() {
        var job = queue.claim().orElseThrow();
        assertThat(job.captureId()).isEqualTo(captureId);
        return job;
    }

    private void makeDue(UUID jobId) {
        jdbc.sql("UPDATE job SET next_attempt_at = now() WHERE id = :id").param("id", jobId).update();
    }

    @Test
    void failedJobIsRetriedWithBackoffUntilMaxAttempts() {
        var job = claimOwn();
        assertThat(job.attempts()).isEqualTo(1);

        assertThat(queue.fail(job, "boom").terminal()).isFalse();
        var row = jdbc.sql("SELECT status, last_error, next_attempt_at > now() + interval '20 seconds' AS later FROM job WHERE id = :id")
                .param("id", job.id()).query().singleRow();
        assertThat(row).containsEntry("status", "queued").containsEntry("last_error", "boom").containsEntry("later", true);
        assertThat(queue.claim()).isEmpty();

        for (int attempt = 2; attempt <= properties.maxAttempts(); attempt++) {
            makeDue(job.id());
            job = claimOwn();
            assertThat(job.attempts()).isEqualTo(attempt);
            assertThat(queue.fail(job, "boom").terminal()).isEqualTo(attempt == properties.maxAttempts());
        }
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", job.id()).query(String.class).single())
                .isEqualTo("needs_attention");
        makeDue(job.id());
        assertThat(queue.claim()).isEmpty();
    }

    @Test
    void jobWithExpiredLeaseIsClaimedAgain() {
        var job = claimOwn();
        assertThat(queue.claim()).isEmpty();

        jdbc.sql("UPDATE job SET locked_until = now() - interval '1 second' WHERE id = :id").param("id", job.id()).update();

        var again = claimOwn();
        assertThat(again.id()).isEqualTo(job.id());
        assertThat(again.attempts()).isEqualTo(2);
    }

    @Test
    void completedJobIsNotClaimedAgain() {
        var job = claimOwn();
        queue.complete(job.id());
        assertThat(queue.claim()).isEmpty();
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(properties.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.backoff(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(properties.backoff(30)).isEqualTo(Duration.ofHours(1));
    }
}
