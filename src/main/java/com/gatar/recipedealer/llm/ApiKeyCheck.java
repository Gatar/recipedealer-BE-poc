package com.gatar.recipedealer.llm;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gatar.recipedealer.common.Ids;

/**
 * Checks that the API key authenticates and the account can serve requests (a {@code max_tokens: 0} request to the
 * first LLM step's model), records the result and pings the dead man's switch URL only on success. Runs on its own
 * schedule, outside the job queue, so it never waits for or blocks extraction jobs.
 */
@Service
public class ApiKeyCheck {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCheck.class);

    private final ClaudeClient client;
    private final LlmProperties llm;
    private final ApiKeyCheckProperties properties;
    private final LlmPricing pricing;
    private final JdbcClient jdbc;
    private final HttpClient http;

    public ApiKeyCheck(ClaudeClient client, LlmProperties llm, ApiKeyCheckProperties properties, LlmPricing pricing,
                       JdbcClient jdbc) {
        this.client = client;
        this.llm = llm;
        this.properties = properties;
        this.pricing = pricing;
        this.jdbc = jdbc;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    public record Result(UUID id, Instant checkedAt, boolean success, String model, String errorType,
                         Integer httpStatus, Long inputTokens, Long outputTokens, BigDecimal costUsd, Long latencyMs,
                         boolean pinged) {
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfDisabled() {
        if (!properties.enabled()) {
            log.warn("DEADMAN_API_KEY_URL is not set: the scheduled API key check is disabled");
        }
    }

    public Result check() {
        String model = model();
        LlmUsage usage = null;
        LlmException failure = null;
        if (model == null) {
            failure = new LlmException(LlmException.Kind.UNAVAILABLE, "no_model", null, "no LLM step is configured", null);
        } else {
            try {
                usage = client.ping(model).usage();
            } catch (LlmException e) {
                failure = e;
            }
        }
        boolean success = failure == null;
        boolean pinged = success && properties.enabled() && ping();
        if (!success) {
            // Error type and status only: never the key, headers or the URL.
            log.error("Anthropic API key check failed: {} (HTTP {}); the dead man's switch is not pinged",
                    failure.errorType(), failure.httpStatus() == null ? "-" : failure.httpStatus());
        }
        var result = new Result(Ids.newId(), Instant.now(), success, model, success ? null : failure.errorType(),
                success ? null : failure.httpStatus(), usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(), pricing.cost(usage),
                usage == null ? null : usage.latencyMs(), pinged);
        jdbc.sql("""
                        INSERT INTO api_key_check (id, success, model, error_type, http_status, input_tokens, output_tokens,
                                                   cost_usd, latency_ms, pinged)
                        VALUES (:id, :success, :model, :errorType, :httpStatus, :input, :output, :cost, :latency, :pinged)""")
                .param("id", result.id())
                .param("success", result.success())
                .param("model", result.model())
                .param("errorType", result.errorType())
                .param("httpStatus", result.httpStatus())
                .param("input", result.inputTokens())
                .param("output", result.outputTokens())
                .param("cost", result.costUsd())
                .param("latency", result.latencyMs())
                .param("pinged", result.pinged())
                .update();
        return result;
    }

    private String model() {
        if (properties.model() != null && !properties.model().isBlank()) {
            return properties.model();
        }
        return llm.steps().isEmpty() ? null : llm.steps().getFirst().model();
    }

    private boolean ping() {
        try {
            var request = HttpRequest.newBuilder(URI.create(properties.url())).timeout(properties.timeout()).GET().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 == 2) {
                return true;
            }
            log.warn("Dead man's switch ping for the API key check answered HTTP {}", status);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Dead man's switch ping for the API key check failed: {}", e.getClass().getSimpleName());
        }
        return false;
    }
}
