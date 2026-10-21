package com.gatar.recipedealer.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;

@IntegrationTest
class HealthControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthReturnsOk() {
        assertThat(mvc.get().uri("/v1/health"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("ok");
    }
}
