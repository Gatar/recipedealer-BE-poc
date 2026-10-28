package com.gatar.recipedealer.llm;

import java.util.List;
import java.util.Map;

/** The Claude Messages API as the application uses it; tests replace it with a stub, CI never calls the real API. */
public interface ClaudeClient {

    /** One structured-output request: static system prompt (cached), the source as content blocks, JSON schema. */
    Response extract(Request request) throws LlmException;

    /** A minimal request ({@code max_tokens: 0}): proves the key authenticates and the account can serve requests. */
    Response ping(String model) throws LlmException;

    /** @param schema JSON schema of the output, as plain maps and lists */
    record Request(String model, String effort, String systemPrompt, Map<String, Object> schema, List<Part> parts) {
    }

    /** A content block of the user message. */
    sealed interface Part {
    }

    record Text(String text) implements Part {
    }

    /** {@code mediaType}: image/jpeg, image/png, image/gif or image/webp. */
    record Image(String mediaType, byte[] data) implements Part {
    }

    record Pdf(byte[] data) implements Part {
    }

    /**
     * @param text            the concatenated text blocks (empty when there are none)
     * @param refusalCategory set when {@code stop_reason} is {@code refusal}
     */
    record Response(String text, LlmUsage usage, String refusalCategory) {
    }
}
