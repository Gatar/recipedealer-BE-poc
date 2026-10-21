package com.gatar.recipedealer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Full application context against a real PostgreSQL container (shared across test classes). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// The worker is disabled (bean conditions are evaluated before DynamicPropertyRegistrar runs, so it must be set here);
// tests run jobs explicitly with JobWorker.drain().
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "recipedealer.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
