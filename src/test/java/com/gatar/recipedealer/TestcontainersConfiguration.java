package com.gatar.recipedealer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.gatar.recipedealer.backup.DatabaseDumper;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same major version and initdb settings as production. */
    public static final String POSTGRES_IMAGE = "postgres:18.6";

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "test-admin-password";
    public static final String REGISTRATION_CODE = "test-household-code";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C.UTF-8");
    }

    /**
     * pg_dump from inside the container: the build machine has no PostgreSQL 18 client. Production uses
     * {@code PgDumpDumper}.
     */
    @Bean
    @Primary
    DatabaseDumper containerDumper(PostgreSQLContainer postgres) {
        return target -> {
            String inContainer = "/tmp/backup-" + UUID.randomUUID() + ".dump";
            try {
                var result = postgres.execInContainer("pg_dump", "--format=custom", "--file=" + inContainer,
                        "--username=" + postgres.getUsername(), "--dbname=" + postgres.getDatabaseName());
                if (result.getExitCode() != 0) {
                    throw new IOException("pg_dump failed: " + result.getStderr());
                }
                postgres.copyFileFromContainer(inContainer, target.toString());
                postgres.execInContainer("rm", "-f", inContainer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while dumping");
            }
        };
    }

    @Bean
    DynamicPropertyRegistrar testProperties() {
        String hash = new BCryptPasswordEncoder().encode(ADMIN_PASSWORD);
        Path blobDir;
        Path backupDir;
        try {
            blobDir = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-blobs")), "run-");
            backupDir = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-backups")), "run-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return registry -> {
            registry.add("recipedealer.storage.blob-dir", blobDir::toString);
            registry.add("recipedealer.backup.dir", backupDir::toString);
            registry.add("recipedealer.admin.username", () -> ADMIN_USERNAME);
            registry.add("recipedealer.admin.password-hash", () -> hash);
            registry.add("recipedealer.registration.code", () -> REGISTRATION_CODE);
            // Tests share one database and enroll many users; AccountTest checks the limit with its own service.
            registry.add("recipedealer.registration.max-users", () -> 100_000);
            // Tests use unique titles, so every capture would become a recipe at once; AutoNewTest runs its own service.
            registry.add("recipedealer.processing.auto-new", () -> false);
            // WebImageTest serves photos from localhost.
            registry.add("recipedealer.web-images.allow-private-addresses", () -> true);
        };
    }
}
