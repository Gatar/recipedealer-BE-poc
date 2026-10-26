package com.gatar.recipedealer.backup;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/** Runs {@code pg_dump} against the application's own database; credentials go through the environment. */
public class PgDumpDumper implements DatabaseDumper {

    private static final String JDBC_PREFIX = "jdbc:";

    private final String command;
    private final Duration timeout;
    private final JdbcConnectionDetails connection;

    public PgDumpDumper(String command, Duration timeout, JdbcConnectionDetails connection) {
        this.command = command;
        this.timeout = timeout;
        this.connection = connection;
    }

    @Override
    public void dump(Path target) throws IOException {
        Path output = Files.createTempFile(target.toAbsolutePath().getParent(), ".pg_dump-", ".log");
        try {
            var builder = new ProcessBuilder(command, "--format=custom", "--no-password", "--file=" + target,
                    "--dbname=" + libpqUri(connection.getJdbcUrl()))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("PGUSER", connection.getUsername());
            builder.environment().put("PGPASSWORD", connection.getPassword());
            Process process = builder.start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("pg_dump did not finish within " + timeout);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for pg_dump");
            }
            if (process.exitValue() != 0) {
                throw new IOException("pg_dump failed with exit code " + process.exitValue() + ": "
                        + Files.readString(output).strip());
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** {@code jdbc:postgresql://host:port/db?...} as a libpq URI; JDBC-only query parameters are dropped. */
    static String libpqUri(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        String uri = jdbcUrl.substring(JDBC_PREFIX.length());
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }
}
