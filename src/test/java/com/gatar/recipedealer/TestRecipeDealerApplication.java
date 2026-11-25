package com.gatar.recipedealer;

import org.springframework.boot.SpringApplication;

/**
 * The application with the test PostgreSQL container and the test settings ({@link TestcontainersConfiguration}:
 * admin {@code admin} / {@code test-admin-password}, empty database), for trying it locally, e.g. the admin
 * dashboard: {@code ./mvnw spring-boot:test-run}, then {@code http://localhost:8080/admin/}.
 */
public class TestRecipeDealerApplication {

    public static void main(String[] args) {
        SpringApplication.from(RecipeDealerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
