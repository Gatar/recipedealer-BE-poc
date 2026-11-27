package com.gatar.recipedealer.blob;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes blobs nothing refers to any more: the row inside the caller's transaction ({@link #deleteRows}), the file
 * only after the commit ({@link #deleteFiles}), so a rollback never leaves rows pointing to missing files.
 */
@Component
public class BlobJanitor {

    private static final Logger log = LoggerFactory.getLogger(BlobJanitor.class);

    /** Every table that refers to a blob. */
    private static final String UNREFERENCED = """
            NOT EXISTS (SELECT 1 FROM capture WHERE body_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM capture_attachment WHERE blob_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM pdf_image WHERE blob_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM dish_photo WHERE blob_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM web_image WHERE blob_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM user_photo WHERE blob_sha256 = b.sha256)
            AND NOT EXISTS (SELECT 1 FROM recipe WHERE thumbnail_blob = b.sha256)""";

    private final JdbcClient jdbc;
    private final BlobStore blobs;

    public BlobJanitor(JdbcClient jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** Deletes the rows of the candidates nothing refers to; returns them (pass them to {@link #deleteFiles}). */
    public List<String> deleteRows(Collection<String> candidates) {
        return new LinkedHashSet<>(candidates).stream()
                .filter(sha256 -> sha256 != null && jdbc.sql("DELETE FROM blob b WHERE b.sha256 = :sha256 AND " + UNREFERENCED)
                        .param("sha256", sha256).update() == 1)
                .toList();
    }

    /** After the commit: deletes the files, unless a blob row was inserted again since (the same file stored meanwhile). */
    public void deleteFiles(Collection<String> deleted) {
        for (String sha256 : deleted) {
            boolean storedAgain = jdbc.sql("SELECT EXISTS (SELECT 1 FROM blob WHERE sha256 = :sha256)")
                    .param("sha256", sha256).query(Boolean.class).single();
            if (!storedAgain) {
                try {
                    blobs.delete(sha256);
                } catch (IOException e) {
                    log.warn("Could not delete the file of blob {}", sha256, e);
                }
            }
        }
    }
}
