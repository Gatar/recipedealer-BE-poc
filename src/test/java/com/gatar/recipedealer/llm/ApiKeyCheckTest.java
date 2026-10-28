package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.sun.net.httpserver.HttpServer;

/** Dead man's switch for the API key: ping only after a successful check. */
@IntegrationTest
@TestPropertySource(properties = "recipedealer.llm.api-key=test-key")
@Import(LlmTestConfiguration.class)
class ApiKeyCheckTest {

    static final String HAIKU = "claude-haiku-5-5";

    @Autowired
    StubClaudeClient claude;

    @Autowired
    LlmProperties llm;

    @Autowired
    LlmPricing pricing;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    HttpServer deadman;
    AtomicInteger pings = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        claude.reset();
        deadman = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        deadman.createContext("/ping", exchange -> {
            pings.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        deadman.start();
    }

    @AfterEach
    void stop() {
        deadman.stop(0);
    }

    private ApiKeyCheck check(String url) {
        return new ApiKeyCheck(claude, llm, new ApiKeyCheckProperties(url, "0 0 6 * * *", Duration.ofSeconds(5), null),
                pricing, jdbc);
    }

    private String url() {
        return "http://127.0.0.1:" + deadman.getAddress().getPort() + "/ping";
    }

    private static ClaudeClient.Response pong() {
        return StubClaudeClient.response(HAIKU, "max_tokens", "", 8, 0, 0, 0);
    }

    @Test
    void successfulCheckPingsAndIsRecorded() {
        claude.willAnswer("ping:" + HAIKU, pong());

        var result = check(url()).check();

        assertThat(result.success()).isTrue();
        assertThat(result.pinged()).isTrue();
        assertThat(pings).hasValue(1);
        assertThat(claude.pings).containsExactly(HAIKU);
        assertThat(jdbc.sql("SELECT success AND pinged AND model = :model AND input_tokens = 8 FROM api_key_check WHERE id = :id")
                .param("id", result.id()).param("model", HAIKU).query(Boolean.class).single()).isTrue();
    }

    @Test
    void authErrorDoesNotPing() {
        claude.willAnswer("ping:" + HAIKU, StubClaudeClient.error(LlmException.Kind.UNAVAILABLE, "authentication_error", 401));

        var result = check(url()).check();

        assertThat(result).extracting(ApiKeyCheck.Result::success, ApiKeyCheck.Result::errorType,
                ApiKeyCheck.Result::httpStatus, ApiKeyCheck.Result::pinged)
                .containsExactly(false, "authentication_error", 401, false);
        assertThat(pings).hasValue(0);
    }

    @Test
    void creditErrorDoesNotPing() {
        claude.willAnswer("ping:" + HAIKU, StubClaudeClient.error(LlmException.Kind.UNAVAILABLE, "billing_error", 402));

        assertThat(check(url()).check().pinged()).isFalse();
        assertThat(pings).hasValue(0);
    }

    @Test
    void withoutUrlTheScheduledCheckIsDisabled() {
        new ApiKeyCheckPoller(check(null), new ApiKeyCheckProperties(null, "0 0 6 * * *", Duration.ofSeconds(5), null)).poll();

        assertThat(claude.pings).isEmpty();
        assertThat(pings).hasValue(0);
    }

    @Test
    void adminCanRunTheCheckNow() {
        claude.willAnswer("ping:" + HAIKU, pong());

        var result = mvc.post().uri("/v1/admin/api-key-check").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic());

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.success").isEqualTo(true);
    }
}
