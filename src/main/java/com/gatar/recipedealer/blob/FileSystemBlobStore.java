package com.gatar.recipedealer.blob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gatar.recipedealer.common.Hashing;

/**
 * Blobs as files {@code <root>/ab/cd/<sha256>}. Content is streamed into a temp file under {@code <root>/tmp}
 * (same file system) while hashing, fsynced, then atomically renamed, so a blob path never holds partial content.
 */
public class FileSystemBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBlobStore.class);

    private final Path root;
    private final Path tmp;

    public FileSystemBlobStore(Path root) throws IOException {
        this.root = root.toAbsolutePath();
        this.tmp = this.root.resolve("tmp");
        Files.createDirectories(tmp);
    }

    @Override
    public BlobRef put(InputStream content) throws IOException {
        Path temp = Files.createTempFile(tmp, "upload-", ".part");
        try {
            var digest = Hashing.sha256();
            long size;
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                 OutputStream out = new DigestOutputStream(Channels.newOutputStream(channel), digest)) {
                size = content.transferTo(out);
                out.flush();
                channel.force(true);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            Path target = path(sha256);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException e) {
                    // Stored concurrently with identical content.
                }
                fsyncDirectory(target.getParent());
            }
            return new BlobRef(sha256, size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(String sha256) throws IOException {
        return Files.newInputStream(path(sha256));
    }

    @Override
    public boolean exists(String sha256) {
        return BlobStore.isValidSha256(sha256) && Files.isRegularFile(path(sha256));
    }

    Path path(String sha256) {
        if (!BlobStore.isValidSha256(sha256)) {
            throw new IllegalArgumentException("not a SHA-256 hex digest: " + sha256);
        }
        return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    /** Makes the rename itself durable. Not supported on every platform; best effort. */
    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            log.debug("Directory fsync not supported for {}", directory, e);
        }
    }
}
