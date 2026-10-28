package com.gatar.recipedealer.llm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;

/**
 * {@link ClaudeClient} on the official Java SDK. The SDK retries transient errors itself ({@code maxRetries}); what
 * still fails is classified into {@link LlmException.Kind}. Neither the key nor request payloads are ever logged.
 */
public class AnthropicClaudeClient implements ClaudeClient {

    private final AnthropicClient client;
    private final long maxTokens;

    public AnthropicClaudeClient(AnthropicClient client, long maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    public static AnthropicClaudeClient create(LlmProperties properties, String baseUrl) {
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return new AnthropicClaudeClient(builder.build(), properties.maxTokens());
    }

    @Override
    public Response extract(Request request) throws LlmException {
        var system = TextBlockParam.builder()
                .text(request.systemPrompt())
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();
        var schema = JsonOutputFormat.Schema.builder();
        request.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        var params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(system))
                .addUserMessageOfContentBlockParams(blocks(request.parts()))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(request.effort()))
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .build();
        return send(params);
    }

    @Override
    public Response ping(String model) throws LlmException {
        return send(MessageCreateParams.builder()
                .model(model)
                .maxTokens(0)
                .addUserMessage("ping")
                .build());
    }

    private Response send(MessageCreateParams params) throws LlmException {
        long started = System.nanoTime();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw classify(e);
        } catch (AnthropicIoException e) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "io_error", null, "Claude API not reachable: " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new LlmException(LlmException.Kind.TRANSIENT, "client_error", null, "Claude API call failed: " + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        var usage = message.usage();
        String stopReason = message.stopReason().map(StopReason::asString).orElse(null);
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .collect(Collectors.joining());
        String refusalCategory = StopReason.REFUSAL.asString().equals(stopReason)
                ? message.stopDetails().flatMap(details -> details.category()).map(c -> c.asString()).orElse("unknown")
                : null;
        return new Response(text, new LlmUsage(message.model().asString(), stopReason, usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L),
                usage.outputTokens(), latencyMs), refusalCategory);
    }

    static LlmException classify(AnthropicServiceException e) {
        int status = e.statusCode();
        String type = e.errorType().map(ErrorType::asString).orElse("http_" + status);
        boolean unavailable = status == 401 || status == 402 || status == 403
                || e.errorType().filter(t -> t.equals(ErrorType.AUTHENTICATION_ERROR) || t.equals(ErrorType.BILLING_ERROR)
                || t.equals(ErrorType.PERMISSION_ERROR)).isPresent();
        LlmException.Kind kind;
        if (unavailable) {
            kind = LlmException.Kind.UNAVAILABLE;
        } else if (status == 408 || status == 409 || status == 429 || status >= 500) {
            kind = LlmException.Kind.TRANSIENT;
        } else {
            kind = LlmException.Kind.REJECTED;
        }
        return new LlmException(kind, type, status, "Claude API error " + status + " (" + type + "): " + e.getMessage(), e);
    }

    private static List<ContentBlockParam> blocks(List<Part> parts) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        Base64.Encoder base64 = Base64.getEncoder();
        for (Part part : parts) {
            switch (part) {
                case Text text -> blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
                case Image image -> blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                                .data(base64.encodeToString(image.data()))
                                .build())
                        .build()));
                case Pdf pdf -> blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(base64.encodeToString(pdf.data())).build())
                        .build()));
            }
        }
        return blocks;
    }
}
