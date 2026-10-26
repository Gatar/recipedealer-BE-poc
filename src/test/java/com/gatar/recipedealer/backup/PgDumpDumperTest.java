package com.gatar.recipedealer.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

class PgDumpDumperTest {

    private static final JdbcConnectionDetails CONNECTION = new JdbcConnectionDetails() {
        @Override
        public String getUsername() {
            return "recipedealer";
        }

        @Override
        public String getPassword() {
            return "secret";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:postgresql://postgres:5432/recipedealer?ApplicationName=app";
        }
    };

    @TempDir
    Path dir;

    @Test
    void jdbcUrlBecomesALibpqUri() {
        assertThat(PgDumpDumper.libpqUri("jdbc:postgresql://postgres:5432/recipedealer"))
                .isEqualTo("postgresql://postgres:5432/recipedealer");
        assertThat(PgDumpDumper.libpqUri("jdbc:postgresql://localhost:5432/db?ApplicationName=x"))
                .isEqualTo("postgresql://localhost:5432/db");
        assertThatThrownBy(() -> PgDumpDumper.libpqUri("jdbc:mysql://localhost/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesTheTargetDatabaseAndCredentials() throws IOException {
        Path fake = script("""
                #!/bin/sh
                for arg in "$@"; do
                  case "$arg" in --file=*) target="${arg#--file=}" ;; esac
                done
                printf '%s\\n' "$@" "user=$PGUSER" "password=$PGPASSWORD" > "$target"
                """);
        Path target = dir.resolve("out.dump");

        new PgDumpDumper(fake.toString(), Duration.ofSeconds(30), CONNECTION).dump(target);

        assertThat(Files.readAllLines(target)).containsExactly("--format=custom", "--no-password", "--file=" + target,
                "--dbname=postgresql://postgres:5432/recipedealer", "user=recipedealer", "password=secret");
    }

    @Test
    void failureReportsTheOutputAndLeavesNoLog() throws IOException {
        Path fake = script("""
                #!/bin/sh
                echo "pg_dump: error: connection refused" >&2
                exit 1
                """);

        assertThatThrownBy(() -> new PgDumpDumper(fake.toString(), Duration.ofSeconds(30), CONNECTION)
                .dump(dir.resolve("out.dump")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit code 1")
                .hasMessageContaining("connection refused");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("pg_dump");
        }
    }

    private Path script(String content) throws IOException {
        Path script = dir.resolve("pg_dump");
        Files.writeString(script, content);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
