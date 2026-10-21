package com.gatar.recipedealer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same major version and initdb settings as production. */
    public static final String POSTGRES_IMAGE = "postgres:18.6";

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "test-admin-password";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C.UTF-8");
    }

    @Bean
    DynamicPropertyRegistrar adminCredentials() {
        String hash = new BCryptPasswordEncoder().encode(ADMIN_PASSWORD);
        return registry -> {
            registry.add("recipedealer.admin.username", () -> ADMIN_USERNAME);
            registry.add("recipedealer.admin.password-hash", () -> hash);
        };
    }
}
