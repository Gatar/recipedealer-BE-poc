package com.gatar.recipedealer.blob;

import java.io.IOException;
import java.time.Duration;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.web.ETags;
import com.gatar.recipedealer.web.NotFoundException;

/** Blob content by SHA-256. Content never changes, so clients may cache it forever. */
@RestController
public class BlobController {

    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private final BlobStore blobs;
    private final JdbcClient jdbc;

    public BlobController(BlobStore blobs, JdbcClient jdbc) {
        this.blobs = blobs;
        this.jdbc = jdbc;
    }

    private record Meta(long size, String mediaType) {
    }

    @GetMapping("/v1/blobs/{sha256}")
    public ResponseEntity<InputStreamResource> blob(@PathVariable String sha256,
                                                    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {
        if (!BlobStore.isValidSha256(sha256)) {
            throw new NotFoundException("blob " + sha256 + " not found");
        }
        Meta meta = jdbc.sql("""
                        SELECT b.size_bytes,
                               coalesce((SELECT a.media_type FROM capture_attachment a WHERE a.blob_sha256 = b.sha256 LIMIT 1),
                                        (SELECT w.media_type FROM web_image w WHERE w.blob_sha256 = b.sha256 LIMIT 1)) AS media_type,
                               EXISTS (SELECT 1 FROM capture c WHERE c.body_sha256 = b.sha256) AS capture_body
                        FROM blob b WHERE b.sha256 = :sha256""")
                .param("sha256", sha256)
                .query((rs, n) -> new Meta(rs.getLong("size_bytes"), rs.getString("media_type") != null
                        ? rs.getString("media_type")
                        : rs.getBoolean("capture_body") ? MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .optional()
                .orElseThrow(() -> new NotFoundException("blob " + sha256 + " not found"));
        if (ETags.matches(ifNoneMatch, sha256)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(ETags.of(sha256)).cacheControl(IMMUTABLE).build();
        }
        return ResponseEntity.ok()
                .eTag(ETags.of(sha256))
                .cacheControl(IMMUTABLE)
                .contentType(MediaType.parseMediaType(meta.mediaType()))
                .contentLength(meta.size())
                .body(new InputStreamResource(blobs.open(sha256)));
    }
}
