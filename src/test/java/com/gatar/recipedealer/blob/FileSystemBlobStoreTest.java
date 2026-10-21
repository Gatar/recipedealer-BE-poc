package com.gatar.recipedealer.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemBlobStoreTest {

    // sha256("hello")
    private static final String HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path root;

    FileSystemBlobStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new FileSystemBlobStore(root);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private long regularFiles() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void storesContentUnderItsSha256() throws IOException {
        BlobRef ref = store.put(stream("hello"));

        assertThat(ref).isEqualTo(new BlobRef(HELLO_SHA, 5));
        assertThat(root.resolve("2c/f2/" + HELLO_SHA)).hasContent("hello");
        assertThat(store.exists(HELLO_SHA)).isTrue();
        try (InputStream in = store.open(HELLO_SHA)) {
            assertThat(in.readAllBytes()).asString(StandardCharsets.UTF_8).isEqualTo("hello");
        }
    }

    @Test
    void identicalContentIsStoredOnce() throws IOException {
        BlobRef first = store.put(stream("hello"));
        BlobRef second = store.put(stream("hello"));

        assertThat(second).isEqualTo(first);
        assertThat(regularFiles()).isEqualTo(1);
    }

    @Test
    void failedWriteLeavesNoBlobAndNoTempFile() {
        InputStream failing = new InputStream() {
            private int remaining = 10_000;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0];
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (remaining <= 0) {
                    throw new IOException("connection reset");
                }
                int n = Math.min(length, remaining);
                java.util.Arrays.fill(buffer, offset, offset + n, (byte) 'x');
                remaining -= n;
                return n;
            }
        };

        assertThatThrownBy(() -> store.put(failing)).isInstanceOf(IOException.class);
        assertThat(root.resolve("tmp")).isEmptyDirectory();
    }

    @Test
    void largeContentIsStreamed() throws IOException {
        byte[] content = new byte[5 * 1024 * 1024 + 17];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        BlobRef ref = store.put(new ByteArrayInputStream(content));

        assertThat(ref.sizeBytes()).isEqualTo(content.length);
        try (InputStream in = store.open(ref.sha256())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void unknownBlobDoesNotExist() {
        String missing = "0".repeat(64);
        assertThat(store.exists(missing)).isFalse();
        assertThatThrownBy(() -> store.open(missing)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void rejectsInvalidDigestsInsteadOfResolvingPaths() {
        assertThat(store.exists("../../etc/passwd")).isFalse();
        assertThatThrownBy(() -> store.open("../" + "a".repeat(61))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(HELLO_SHA.toUpperCase())).isInstanceOf(IllegalArgumentException.class);
    }
}
