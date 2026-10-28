package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SDK adapter against a local fake of the Messages API: what goes over the wire, how responses and errors are
 * read. Also proves the SDK (Jackson 2) works with the Jackson 2 version Spring Boot manages.
 */
class AnthropicClaudeClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    HttpServer server;
    List<JsonNode> requests = new ArrayList<>();
    List<String> apiKeys = new ArrayList<>();
    int status;
    String responseBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            apiKeys.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicClaudeClient client() {
        var properties = new LlmProperties("sk-ant-test", Duration.ofSeconds(5), 0, 16000, 30_000_000, 2000, 400_000,
                List.of(), Map.of());
        return AnthropicClaudeClient.create(properties, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    private static String message(String stopReason, String text) {
        return """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-haiku-5-5",
                 "content": [{"type": "text", "text": %s}], "stop_reason": "%s", "stop_sequence": null,
                 "usage": {"input_tokens": 1200, "output_tokens": 345, "cache_creation_input_tokens": 900,
                           "cache_read_input_tokens": 0}}""".formatted(JSON.writeValueAsString(text), stopReason);
    }

    @Test
    void extractionRequestCachesTheSystemPromptAndAsksForTheSchema() throws Exception {
        respond(200, message("end_turn", "{\"recipeFound\": true}"));

        var response = client().extract(new ClaudeClient.Request("claude-haiku-5-5", "medium", "SYSTEM",
                RecipePrompt.schema(), List.of(new ClaudeClient.Image("image/jpeg", new byte[] {1, 2, 3}),
                new ClaudeClient.Pdf(new byte[] {4}), new ClaudeClient.Text("Extract."))));

        JsonNode request = requests.getFirst();
        // SDK 3.x sends the API key as a bearer token.
        assertThat(apiKeys.getFirst()).isEqualTo("Bearer sk-ant-test");
        assertThat(request.path("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(request.path("max_tokens").asLong()).isEqualTo(16000);
        assertThat(request.path("system").get(0).path("text").asString()).isEqualTo("SYSTEM");
        assertThat(request.path("system").get(0).path("cache_control").path("type").asString()).isEqualTo("ephemeral");
        assertThat(request.has("cache_control")).as("no top-level automatic caching").isFalse();
        assertThat(request.has("thinking")).isFalse();
        assertThat(request.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(request.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(request.path("output_config").path("format").path("schema").path("additionalProperties").asBoolean(true))
                .isFalse();
        JsonNode content = request.path("messages").get(0).path("content");
        assertThat(content.get(0).path("type").asString()).isEqualTo("image");
        assertThat(content.get(0).path("source").path("data").asString()).isEqualTo("AQID");
        assertThat(content.get(0).path("source").path("media_type").asString()).isEqualTo("image/jpeg");
        assertThat(content.get(1).path("type").asString()).isEqualTo("document");
        assertThat(content.get(1).path("source").path("media_type").asString()).isEqualTo("application/pdf");
        assertThat(content.get(2).path("text").asString()).isEqualTo("Extract.");

        assertThat(response.text()).isEqualTo("{\"recipeFound\": true}");
        assertThat(response.refusalCategory()).isNull();
        assertThat(response.usage()).extracting(LlmUsage::model, LlmUsage::stopReason, LlmUsage::inputTokens,
                LlmUsage::cacheCreationInputTokens, LlmUsage::cacheReadInputTokens, LlmUsage::outputTokens)
                .containsExactly("claude-haiku-5-5", "end_turn", 1200L, 900L, 0L, 345L);
    }

    @Test
    void pingSendsAPlainRequestWithZeroMaxTokens() throws Exception {
        respond(200, """
                {"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-haiku-5-5", "content": [],
                 "stop_reason": "max_tokens", "stop_sequence": null, "usage": {"input_tokens": 8, "output_tokens": 0}}""");

        var response = client().ping("claude-haiku-5-5");

        JsonNode request = requests.getFirst();
        assertThat(request.path("max_tokens").asLong()).isZero();
        assertThat(request.has("system")).isFalse();
        assertThat(request.has("output_config")).isFalse();
        assertThat(request.has("stream")).isFalse();
        assertThat(response.usage().outputTokens()).isZero();
        assertThat(response.usage().cacheCreationInputTokens()).isZero();
    }

    @Test
    void refusalCarriesItsCategory() throws Exception {
        respond(200, """
                {"id": "msg_3", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5", "content": [],
                 "stop_reason": "refusal", "stop_sequence": null,
                 "stop_details": {"type": "refusal", "category": "cyber", "explanation": null},
                 "usage": {"input_tokens": 10, "output_tokens": 0}}""");

        assertThat(client().ping("claude-sonnet-5-5").refusalCategory()).isEqualTo("cyber");
    }

    private LlmException failure(int status, String type) {
        respond(status, """
                {"type": "error", "error": {"type": "%s", "message": "something"}}""".formatted(type));
        try {
            client().ping("claude-haiku-5-5");
        } catch (LlmException e) {
            return e;
        }
        throw new AssertionError("no exception");
    }

    @Test
    void authAndCreditErrorsMakeTheApiUnavailable() {
        assertThat(failure(401, "authentication_error")).extracting(LlmException::kind, LlmException::errorType,
                LlmException::httpStatus).containsExactly(LlmException.Kind.UNAVAILABLE, "authentication_error", 401);
        assertThat(failure(402, "billing_error").kind()).isEqualTo(LlmException.Kind.UNAVAILABLE);
        assertThat(failure(403, "permission_error").kind()).isEqualTo(LlmException.Kind.UNAVAILABLE);
    }

    @Test
    void overloadAndRateLimitAreTransientOtherClientErrorsRejected() {
        assertThat(failure(529, "overloaded_error").kind()).isEqualTo(LlmException.Kind.TRANSIENT);
        assertThat(failure(429, "rate_limit_error").kind()).isEqualTo(LlmException.Kind.TRANSIENT);
        assertThat(failure(500, "api_error").kind()).isEqualTo(LlmException.Kind.TRANSIENT);
        assertThat(failure(400, "invalid_request_error").kind()).isEqualTo(LlmException.Kind.REJECTED);
        assertThat(failure(413, "request_too_large").kind()).isEqualTo(LlmException.Kind.REJECTED);
    }

    @Test
    void unreachableApiIsTransient() {
        server.stop(0);
        assertThatThrownBy(() -> client().ping("claude-haiku-5-5"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TRANSIENT));
    }
}
