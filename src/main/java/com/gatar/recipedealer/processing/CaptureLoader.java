package com.gatar.recipedealer.processing;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.json.JsonMapper;

/** Loads a capture for processing from its original bytes, so reprocessing depends only on stored content. */
@Component
public class CaptureLoader {

    private final JdbcClient jdbc;
    private final BlobStore blobs;
    private final JsonMapper jsonMapper;

    public CaptureLoader(JdbcClient jdbc, BlobStore blobs, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.jsonMapper = jsonMapper;
    }

    private record Row(UUID id, java.time.Instant receivedAt, UUID userId, UUID deviceId, int schemaVersion, String bodySha256) {
    }

    public Capture load(UUID captureId) throws IOException {
        Row row = jdbc.sql("SELECT id, received_at, user_id, device_id, schema_version, body_sha256 FROM capture WHERE id = :id")
                .param("id", captureId)
                .query((rs, n) -> new Row(Jdbc.uuid(rs, "id"), Jdbc.instant(rs, "received_at"), Jdbc.uuid(rs, "user_id"),
                        Jdbc.uuid(rs, "device_id"), rs.getInt("schema_version"), rs.getString("body_sha256")))
                .optional()
                .orElseThrow(() -> new NotFoundException("capture " + captureId + " not found"));
        var attachments = jdbc.sql("""
                        SELECT name, role, media_type, blob_sha256, size_bytes FROM capture_attachment
                        WHERE capture_id = :id ORDER BY name""")
                .param("id", captureId)
                .query((rs, n) -> new Capture.Attachment(rs.getString("name"), rs.getString("role"),
                        rs.getString("media_type"), rs.getString("blob_sha256"), rs.getLong("size_bytes")))
                .list();
        byte[] body;
        try (InputStream in = blobs.open(row.bodySha256())) {
            body = in.readAllBytes();
        }
        return new Capture(row.id(), row.receivedAt(), row.userId(), row.deviceId(), row.schemaVersion(),
                row.bodySha256(), jsonMapper.readTree(body), attachments);
    }
}
