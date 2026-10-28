package com.gatar.recipedealer.processing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.common.Ids;

/**
 * PostgreSQL job table. A job is claimed with {@code FOR UPDATE SKIP LOCKED} and leased; a worker that dies leaves a
 * running job whose lease expires, so it is claimed again. All times use the database clock. One job runs one
 * cascade step ({@link ProcessingTask}).
 */
@Component
public class JobQueue {

    public static final String PROCESS_CAPTURE = "process_capture";
    /** Admin model comparison; does not count as processing in progress. */
    public static final String COMPARE_CAPTURE = "compare_capture";

    private final JdbcClient jdbc;
    private final WorkerProperties properties;

    public JobQueue(JdbcClient jdbc, WorkerProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Job(UUID id, String kind, UUID captureId, int attempts, int maxAttempts, String step,
                      Purpose purpose, UUID cascadeId) {

        /** Jobs queued before cascades existed have no cascade id: the job id stands in. */
        public ProcessingTask task() {
            return new ProcessingTask(captureId, step, cascadeId != null ? cascadeId : id, purpose);
        }
    }

    public record Failure(boolean terminal, int attempts) {
    }

    /** Normal processing of a new capture, from the first step. */
    public UUID enqueue(UUID captureId) {
        return enqueue(ProcessingTask.pipeline(captureId));
    }

    public UUID enqueue(ProcessingTask task) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO job (id, kind, capture_id, status, max_attempts, step, purpose, cascade_id)
                        VALUES (:id, :kind, :captureId, 'queued', :max, :step, :purpose, :cascadeId)""")
                .param("id", id)
                .param("kind", task.purpose() == Purpose.COMPARISON ? COMPARE_CAPTURE : PROCESS_CAPTURE)
                .param("captureId", task.captureId())
                .param("max", properties.maxAttempts())
                .param("step", task.step())
                .param("purpose", task.purpose().value())
                .param("cascadeId", task.cascadeId())
                .update();
        return id;
    }

    /** A processing job (not a comparison) for the capture is queued or running. */
    public boolean hasActive(UUID captureId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM job WHERE capture_id = :id AND kind = :kind
                                                       AND status IN ('queued', 'running'))""")
                .param("id", captureId)
                .param("kind", PROCESS_CAPTURE)
                .query(Boolean.class)
                .single();
    }

    public Optional<Job> claim() {
        return jdbc.sql("""
                        UPDATE job SET status = 'running', attempts = attempts + 1,
                                       locked_until = now() + make_interval(secs => :leaseSeconds), updated_at = now()
                        WHERE id = (
                            SELECT id FROM job
                            WHERE (status = 'queued' AND next_attempt_at <= now())
                               OR (status = 'running' AND locked_until < now())
                            ORDER BY next_attempt_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, kind, capture_id, attempts, max_attempts, step, purpose, cascade_id""")
                .param("leaseSeconds", properties.lease().toSeconds())
                .query((rs, n) -> new Job(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getObject("capture_id", UUID.class), rs.getInt("attempts"), rs.getInt("max_attempts"),
                        rs.getString("step"), Purpose.of(rs.getString("purpose")), rs.getObject("cascade_id", UUID.class)))
                .optional();
    }

    public void complete(UUID jobId) {
        jdbc.sql("UPDATE job SET status = 'done', locked_until = NULL, last_error = NULL, updated_at = now() WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    /** Schedules a retry with exponential backoff, or gives up after the maximum number of attempts. */
    public Failure fail(Job job, String error) {
        return fail(job, error, false);
    }

    /** {@code giveUp}: the failure is not retryable, end the job now. */
    public Failure fail(Job job, String error, boolean giveUp) {
        boolean terminal = giveUp || job.attempts() >= job.maxAttempts();
        jdbc.sql("""
                        UPDATE job SET status = :status, locked_until = NULL, last_error = :error, updated_at = now(),
                                       next_attempt_at = now() + make_interval(secs => :delaySeconds)
                        WHERE id = :id""")
                .param("id", job.id())
                .param("status", terminal ? "needs_attention" : "queued")
                .param("error", error)
                .param("delaySeconds", terminal ? 0 : properties.backoff(job.attempts()).toSeconds())
                .update();
        return new Failure(terminal, job.attempts());
    }
}
