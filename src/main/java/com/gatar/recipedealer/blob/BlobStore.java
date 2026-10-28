package com.gatar.recipedealer.blob;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/** Content-addressed storage for raw bytes. Storing identical content twice is a no-op. */
public interface BlobStore {

    Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** Streams the content to durable storage and returns its SHA-256 and size. */
    BlobRef put(InputStream content) throws IOException;

    /** @throws java.nio.file.NoSuchFileException if the blob does not exist */
    InputStream open(String sha256) throws IOException;

    boolean exists(String sha256);

    /** Removes the content; a missing blob is not an error. Only for blobs no row refers to. */
    void delete(String sha256) throws IOException;

    static boolean isValidSha256(String sha256) {
        return sha256 != null && SHA256_HEX.matcher(sha256).matches();
    }
}
