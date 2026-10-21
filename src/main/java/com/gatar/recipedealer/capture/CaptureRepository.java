package com.gatar.recipedealer.capture;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.gatar.recipedealer.blob.BlobRef;

@Repository
public class CaptureRepository {

    private final JdbcClient jdbc;

    public CaptureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NewAttachment(String name, String role, String mediaType, String originalFileName, BlobRef blob,
                                Integer width, Integer height) {
    }

    public record NewCapture(UUID id, OffsetDateTime capturedAt, UUID userId, UUID deviceId, int schemaVersion,
                             BlobRef body, byte[] bodyJson, String sourceType, String sourceUrl,
                             List<NewAttachment> attachments) {
    }

    public record State(UUID captureId, CaptureStatus status, String message) {
    }

    public Optional<String> findBodySha256(UUID captureId) {
        return jdbc.sql("SELECT body_sha256 FROM capture WHERE id = :id").param("id", captureId)
                .query(String.class).optional();
    }

    /** Inserts the capture, its attachments and its state; returns false if the capture id already exists. */
    public boolean insert(NewCapture capture) {
        insertBlob(capture.body());
        int inserted = jdbc.sql("""
                        INSERT INTO capture (id, captured_at, user_id, device_id, schema_version, body_sha256, body,
                                             source_type, source_url)
                        VALUES (:id, :capturedAt, :userId, :deviceId, :schemaVersion, :bodySha256, CAST(:body AS jsonb),
                                :sourceType, :sourceUrl)
                        ON CONFLICT (id) DO NOTHING""")
                .param("id", capture.id())
                .param("capturedAt", capture.capturedAt())
                .param("userId", capture.userId())
                .param("deviceId", capture.deviceId())
                .param("schemaVersion", capture.schemaVersion())
                .param("bodySha256", capture.body().sha256())
                .param("body", new String(capture.bodyJson(), StandardCharsets.UTF_8))
                .param("sourceType", capture.sourceType())
                .param("sourceUrl", capture.sourceUrl())
                .update();
        if (inserted == 0) {
            return false;
        }
        for (NewAttachment attachment : capture.attachments()) {
            insertBlob(attachment.blob());
            jdbc.sql("""
                            INSERT INTO capture_attachment (capture_id, name, role, media_type, original_file_name,
                                                            blob_sha256, size_bytes, width, height)
                            VALUES (:captureId, :name, :role, :mediaType, :originalFileName, :sha256, :size, :width, :height)""")
                    .param("captureId", capture.id())
                    .param("name", attachment.name())
                    .param("role", attachment.role())
                    .param("mediaType", attachment.mediaType())
                    .param("originalFileName", attachment.originalFileName())
                    .param("sha256", attachment.blob().sha256())
                    .param("size", attachment.blob().sizeBytes())
                    .param("width", attachment.width())
                    .param("height", attachment.height())
                    .update();
        }
        jdbc.sql("INSERT INTO capture_state (capture_id, status) VALUES (:id, :status)")
                .param("id", capture.id())
                .param("status", CaptureStatus.ACCEPTED.value())
                .update();
        return true;
    }

    public Optional<State> findState(UUID captureId) {
        return jdbc.sql("SELECT capture_id, status, message FROM capture_state WHERE capture_id = :id")
                .param("id", captureId)
                .query((rs, n) -> new State(rs.getObject("capture_id", UUID.class), CaptureStatus.of(rs.getString("status")),
                        rs.getString("message")))
                .optional();
    }

    private void insertBlob(BlobRef blob) {
        jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                .param("sha256", blob.sha256())
                .param("size", blob.sizeBytes())
                .update();
    }
}
