package com.gatar.recipedealer.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Hashing;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.json.JsonMapper;

/**
 * On-request backups: one ZIP per backup with {@code database.dump} ({@code pg_dump --format=custom}), {@code blobs/}
 * (the blob store layout) and {@code manifest.json}. The dump is taken before the blobs are copied: a blob is written
 * before the rows that reference it and never changes, so every blob the dump refers to is in the archive.
 * Restored with {@code deploy/restore.sh}.
 */
@Service
public class BackupService {

    public static final int FORMAT = 1;
    public static final int MAX_LABEL = 40;
    static final String MANIFEST = "manifest.json";
    static final String DUMP = "database.dump";
    static final String BLOBS = "blobs/";

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern NAME = Pattern.compile("recipedealer-\\d{8}T\\d{6}Z(-[a-z0-9]+(-[a-z0-9]+)*)?\\.zip");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    /** Files being written start with a dot and are never listed. */
    private static final String PARTIAL_PREFIX = ".";

    private final Path dir;
    private final Path blobDir;
    private final DatabaseDumper dumper;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final ReentrantLock creating = new ReentrantLock();

    public BackupService(BackupProperties properties, @Value("${recipedealer.storage.blob-dir}") Path blobDir,
                         DatabaseDumper dumper, JdbcClient jdbc, JsonMapper jsonMapper) throws IOException {
        this.dir = Files.createDirectories(properties.dir()).toAbsolutePath();
        this.blobDir = blobDir.toAbsolutePath();
        this.dumper = dumper;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path leftover : files.filter(p -> p.getFileName().toString().startsWith(PARTIAL_PREFIX)).toList()) {
                Files.deleteIfExists(leftover);
            }
        }
    }

    /** What the archive holds; counts are taken just before the dump. {@code schemaVersion}: last Flyway migration. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Manifest(int format, Instant createdAt, String label, String schemaVersion, long recipes,
                           long deletedRecipes, long revisions, long captures, long blobs, long blobBytes,
                           String dumpSha256) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BackupInfo(String name, long sizeBytes, Manifest manifest) {
    }

    private record Counts(String schemaVersion, long recipes, long deletedRecipes, long revisions, long captures) {
    }

    public BackupInfo create(String label) {
        if (label != null && (label.length() > MAX_LABEL || !LABEL.matcher(label).matches())) {
            throw ApiException.badRequest("label must be lower-case letters, digits and single hyphens, at most "
                    + MAX_LABEL + " characters");
        }
        if (!creating.tryLock()) {
            throw ApiException.conflict("backup_running", "another backup is being created");
        }
        try {
            Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String name = "recipedealer-" + STAMP.format(createdAt) + (label == null ? "" : "-" + label) + ".zip";
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                throw ApiException.conflict("backup_exists", "backup " + name + " already exists");
            }
            Path dump = dir.resolve(PARTIAL_PREFIX + name + ".dump");
            Path partial = dir.resolve(PARTIAL_PREFIX + name);
            try {
                Counts counts = counts();
                dumper.dump(dump);
                writeArchive(partial, dump, createdAt, label, counts);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.error("Backup {} failed", name, e);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "backup_failed", e.getMessage());
            } finally {
                deleteQuietly(dump);
                deleteQuietly(partial);
            }
            BackupInfo info = info(target);
            log.info("Created backup {} ({} bytes)", name, info.sizeBytes());
            return info;
        } finally {
            creating.unlock();
        }
    }

    /** Newest first. */
    public List<BackupInfo> list() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .map(this::info)
                    .toList();
        }
    }

    public Path file(String name) {
        Path file = NAME.matcher(name).matches() ? dir.resolve(name) : null;
        if (file == null || !Files.isRegularFile(file)) {
            throw new NotFoundException("backup " + name + " not found");
        }
        return file;
    }

    public void delete(String name) throws IOException {
        Files.delete(file(name));
        log.info("Deleted backup {}", name);
    }

    private Counts counts() {
        return jdbc.sql("""
                        SELECT (SELECT version FROM flyway_schema_history WHERE success
                                ORDER BY installed_rank DESC LIMIT 1) AS schema_version,
                               (SELECT count(*) FROM recipe WHERE deleted_at IS NULL AND merged_into IS NULL) AS recipes,
                               (SELECT count(*) FROM recipe WHERE deleted_at IS NOT NULL OR merged_into IS NOT NULL)
                                   AS deleted_recipes,
                               (SELECT count(*) FROM recipe_revision) AS revisions,
                               (SELECT count(*) FROM capture) AS captures""")
                .query((rs, n) -> new Counts(rs.getString("schema_version"), rs.getLong("recipes"),
                        rs.getLong("deleted_recipes"), rs.getLong("revisions"), rs.getLong("captures")))
                .single();
    }

    private void writeArchive(Path archive, Path dump, Instant createdAt, String label, Counts counts) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            // Dumps and photos are compressed already.
            zip.setLevel(Deflater.BEST_SPEED);
            var digest = Hashing.sha256();
            try (InputStream in = new DigestInputStream(Files.newInputStream(dump), digest)) {
                copy(zip, DUMP, in);
            }
            long blobs = 0;
            long blobBytes = 0;
            for (Path blob : blobFiles()) {
                try (InputStream in = Files.newInputStream(blob)) {
                    blobBytes += copy(zip, BLOBS + blobDir.relativize(blob).toString().replace('\\', '/'), in);
                }
                blobs++;
            }
            var manifest = new Manifest(FORMAT, createdAt, label, counts.schemaVersion(), counts.recipes(),
                    counts.deletedRecipes(), counts.revisions(), counts.captures(), blobs, blobBytes,
                    HexFormat.of().formatHex(digest.digest()));
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
        }
    }

    /** Stored blobs ({@code ab/cd/<sha256>}), without the store's temp directory. */
    private List<Path> blobFiles() throws IOException {
        if (!Files.isDirectory(blobDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(blobDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> BlobStore.isValidSha256(p.getFileName().toString()))
                    .filter(p -> !blobDir.relativize(p).startsWith("tmp"))
                    .sorted()
                    .toList();
        }
    }

    private static long copy(ZipOutputStream zip, String entry, InputStream in) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        long size = in.transferTo(zip);
        zip.closeEntry();
        return size;
    }

    private BackupInfo info(Path file) {
        try {
            long size = Files.size(file);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                ZipEntry entry = zip.getEntry(MANIFEST);
                Manifest manifest = null;
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        manifest = jsonMapper.readValue(in, Manifest.class);
                    }
                }
                return new BackupInfo(file.getFileName().toString(), size, manifest);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read backup {}", file, e);
            return new BackupInfo(file.getFileName().toString(), file.toFile().length(), null);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Cannot delete {}", file, e);
        }
    }
}
