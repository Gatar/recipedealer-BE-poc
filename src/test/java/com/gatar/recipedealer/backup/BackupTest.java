package com.gatar.recipedealer.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.auth.AdminAuthRateLimiter;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class BackupTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    AdminAuthRateLimiter rateLimiter;

    String suffix;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void resetRateLimit() {
        rateLimiter.reset();
    }

    @Test
    void backupHoldsDumpAndBlobsAndRestoresIntoAnEmptyDatabase() throws Exception {
        String token = TestAuth.enroll(mvc, "Backup", "phone");
        UUID recipeId = TestCaptures.newRecipe(mvc, token, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://backup-" + suffix + ".pl/sernik", "Sernik " + suffix));
        String bodyBlob = jdbc.sql("""
                        SELECT c.body_sha256 FROM capture c JOIN recipe_revision v ON v.capture_id = c.id
                        WHERE v.recipe_id = :id""")
                .param("id", recipeId).query(String.class).single();
        long recipes = jdbc.sql("SELECT count(*) FROM recipe WHERE deleted_at IS NULL AND merged_into IS NULL")
                .query(Long.class).single();

        var created = create("{\"label\":\"core-" + suffix + "\"}");
        assertThat(created).hasStatus(201);
        String body = TestAuth.body(created);
        String name = JsonPath.read(body, "$.name");
        assertThat(name).matches("recipedealer-\\d{8}T\\d{6}Z-core-" + suffix + "\\.zip");
        assertThat(JsonPath.<String>read(body, "$.manifest.label")).isEqualTo("core-" + suffix);
        assertThat(JsonPath.<Number>read(body, "$.manifest.recipes").longValue()).isEqualTo(recipes);
        assertThat(JsonPath.<String>read(body, "$.manifest.schemaVersion")).isEqualTo(jdbc.sql(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1").query(String.class).single());

        assertThat(JsonPath.<List<String>>read(TestAuth.body(admin(mvc.get().uri("/v1/admin/backups"))), "$[*].name"))
                .contains(name);

        var download = admin(mvc.get().uri("/v1/admin/backups/{name}", name));
        assertThat(download).hasStatusOk().hasContentType("application/zip")
                .hasHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        Map<String, byte[]> entries = unzip(download.getResponse().getContentAsByteArray());
        assertThat(entries).containsKeys(BackupService.MANIFEST, BackupService.DUMP,
                BackupService.BLOBS + bodyBlob.substring(0, 2) + "/" + bodyBlob.substring(2, 4) + "/" + bodyBlob);
        assertThat(entries.keySet()).noneMatch(entry -> entry.startsWith(BackupService.BLOBS + "tmp/"));

        assertThat(restoredRecipeCount(entries.get(BackupService.DUMP), recipeId)).isEqualTo("1");
    }

    @Test
    void backupWithoutBodyHasNoLabel() {
        var created = admin(mvc.post().uri("/v1/admin/backups"));
        assertThat(created).hasStatus(201);
        assertThat(JsonPath.<String>read(TestAuth.body(created), "$.name")).matches("recipedealer-\\d{8}T\\d{6}Z\\.zip");
    }

    @Test
    void labelMustBeASlug() {
        assertThat(create("{\"label\":\"Rdzeń\"}")).hasStatus(400);
        assertThat(create("{\"label\":\"" + "a".repeat(BackupService.MAX_LABEL + 1) + "\"}")).hasStatus(400);
    }

    @Test
    void deletedBackupIsGone() {
        String name = JsonPath.read(TestAuth.body(create("{\"label\":\"delete-" + suffix + "\"}")), "$.name");

        assertThat(admin(mvc.delete().uri("/v1/admin/backups/{name}", name))).hasStatus(204);
        assertThat(admin(mvc.get().uri("/v1/admin/backups/{name}", name))).hasStatus(404);
        assertThat(admin(mvc.delete().uri("/v1/admin/backups/{name}", name))).hasStatus(404);
    }

    @Test
    void onlyBackupNamesCanBeDownloaded() {
        assertThat(admin(mvc.get().uri("/v1/admin/backups/{name}", "recipedealer-20000101T000000Z.zip"))).hasStatus(404);
        assertThat(admin(mvc.get().uri("/v1/admin/backups/{name}", "application.yaml"))).hasStatus(404);
    }

    @Test
    void backupsNeedTheAdmin() {
        String token = TestAuth.enroll(mvc, "Backup", "tablet");
        assertThat(mvc.get().uri("/v1/admin/backups")).hasStatus(401);
        assertThat(mvc.post().uri("/v1/admin/backups").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(401);
    }

    private MvcTestResult create(String json) {
        return admin(mvc.post().uri("/v1/admin/backups").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static MvcTestResult admin(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** Restores the dump into a fresh database in the same container, like deploy/restore.sh does. */
    private String restoredRecipeCount(byte[] dump, UUID recipeId) throws Exception {
        String database = "restore_" + suffix.replace('-', '_');
        String file = "/tmp/" + database + ".dump";
        String user = "--username=" + postgres.getUsername();
        postgres.copyFileToContainer(Transferable.of(dump), file);
        try {
            assertThat(postgres.execInContainer("createdb", user, database).getExitCode()).isZero();
            var restore = postgres.execInContainer("pg_restore", user, "--dbname=" + database, "--no-owner",
                    "--exit-on-error", file);
            assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
            return postgres.execInContainer("psql", user, "--dbname=" + database, "-tAc",
                    "SELECT count(*) FROM recipe WHERE id = '" + recipeId + "'").getStdout().strip();
        } finally {
            postgres.execInContainer("dropdb", user, "--if-exists", database);
            postgres.execInContainer("rm", "-f", file);
        }
    }
}
