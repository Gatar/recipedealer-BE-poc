package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobWorker;

/** The shared test context has no API key: LLM extraction is disabled. */
@IntegrationTest
class LlmDisabledTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JobWorker worker;

    @Test
    void forcingAndComparingAnswerServiceUnavailable() {
        String token = TestAuth.enroll(mvc, "NoLlm", "phone");
        UUID captureId = TestCaptures.upload(mvc, token, TestCaptures.webCapture(UUID.randomUUID(),
                "https://www.nollm-" + UUID.randomUUID() + ".pl/", "Bez LLM " + UUID.randomUUID()));
        worker.drain();

        assertThat(mvc.post().uri("/v1/captures/{id}/llm-extraction", captureId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(503).bodyJson().extractingPath("$.error").isEqualTo("llm_unavailable");
        assertThat(mvc.post().uri("/v1/admin/llm-comparisons").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"captureIds\": [\"" + captureId + "\"], \"steps\": [\"haiku\"]}"))
                .hasStatus(503);
    }

    @Test
    void apiKeyCheckFailsWithoutAKey() {
        assertThat(mvc.post().uri("/v1/admin/api-key-check").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()))
                .hasStatusOk().bodyJson().extractingPath("$.errorType").isEqualTo("no_api_key");
    }
}
