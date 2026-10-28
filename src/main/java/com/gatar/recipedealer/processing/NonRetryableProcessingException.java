package com.gatar.recipedealer.processing;

/**
 * A failure that another attempt cannot fix (e.g. the API key is invalid or the account has no credit): the job ends
 * at once and the capture goes to {@code needs_attention} with {@link #reason()}.
 */
public class NonRetryableProcessingException extends Exception {

    private final String reason;

    public NonRetryableProcessingException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
