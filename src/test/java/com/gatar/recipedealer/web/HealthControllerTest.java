package com.gatar.recipedealer.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;

@IntegrationTest
class HealthControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthReturnsOkForValidToken() {
        String token = TestAuth.enroll(mvc, "Health", "phone");

        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("ok");
    }

    @Test
    void healthRequiresToken() {
        assertThat(mvc.get().uri("/v1/health")).hasStatus(401)
                .bodyJson().extractingPath("$.error").isEqualTo("unauthorized");
    }
}
