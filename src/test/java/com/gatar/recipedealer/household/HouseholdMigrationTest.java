package com.gatar.recipedealer.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.gatar.recipedealer.IntegrationTest;

/**
 * V19 on a database that already has data (the shared test database is migrated while empty): every existing row
 * goes to the default household, also the immutable captures, whose trigger is on again afterwards.
 */
@IntegrationTest
class HouseholdMigrationTest {

    private static final String SHA = "a".repeat(64);

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    JdbcClient shared;

    @Test
    void existingRowsMoveToTheDefaultHousehold() {
        String database = "v19_" + UUID.randomUUID().toString().replace("-", "");
        shared.sql("CREATE DATABASE " + database).update();
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        var dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("18").load();
        flyway.migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID capture = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, name) VALUES (:id, 'Ania')").param("id", user).update();
        jdbc.sql("INSERT INTO device_token (id, user_id, device_name, token_hash) VALUES (:id, :user, 'Pixel', '\\x01')")
                .param("id", device).param("user", user).update();
        jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha, 1)").param("sha", SHA).update();
        jdbc.sql("""
                        INSERT INTO capture (id, user_id, device_id, schema_version, body_sha256, body, source_type)
                        VALUES (:id, :user, :device, 1, :sha, '{}', 'WEB')""")
                .param("id", capture).param("user", user).param("device", device).param("sha", SHA).update();
        jdbc.sql("INSERT INTO recipe (id) VALUES (:id)").param("id", recipe).update();
        jdbc.sql("""
                        INSERT INTO recipe_key (kind, value, raw_value, normalizer_version, recipe_id, capture_id)
                        VALUES ('CANONICAL_URL', 'https://a.pl/x', 'https://a.pl/x', 1, :recipe, :capture)""")
                .param("recipe", recipe).param("capture", capture).update();
        jdbc.sql("INSERT INTO tag_category (name, title, position) VALUES ('meal', 'Posiłek', 0)").update();
        jdbc.sql("INSERT INTO tag (category, name, title, position) VALUES ('meal', 'dinner', 'Obiad', 0)").update();
        jdbc.sql("INSERT INTO recipe_tag (recipe_id, category, tag) VALUES (:recipe, 'meal', 'dinner')")
                .param("recipe", recipe).update();
        jdbc.sql("INSERT INTO user_photo (blob_sha256, width, height, device_id) VALUES (:sha, 1, 1, :device)")
                .param("sha", SHA).param("device", device).update();

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        for (String table : new String[] {"app_user", "capture", "recipe", "recipe_key", "tag_category", "tag",
                "recipe_tag", "user_photo"}) {
            assertThat(jdbc.sql("SELECT DISTINCT household_id FROM " + table).query(UUID.class).list()).as(table)
                    .containsExactly(Households.DEFAULT_ID);
        }
        assertThat(jdbc.sql("SELECT role FROM app_user").query(String.class).single()).isEqualTo("owner");
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture SET source_url = 'x'").update())
                .hasMessageContaining("capture rows are immutable");

        shared.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }
}
