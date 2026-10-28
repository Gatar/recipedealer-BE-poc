package com.gatar.recipedealer.llm;

/**
 * A failed Claude API call. {@link Kind#TRANSIENT} is retried (by the SDK, then by the job's backoff) and never moves
 * the cascade to the next step; the other kinds are not retried.
 */
public class LlmException extends Exception {

    public enum Kind {
        /** 5xx, overload, rate limit, timeout, connection problems. */
        TRANSIENT,
        /** Invalid key, missing permission, no credit: no request can succeed until someone fixes the account. */
        UNAVAILABLE,
        /** Any other 4xx: this request cannot succeed (e.g. an image the API does not accept). */
        REJECTED
    }

    private final Kind kind;
    private final String errorType;
    private final Integer httpStatus;

    public LlmException(Kind kind, String errorType, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** API error type (e.g. {@code authentication_error}, {@code billing_error}) or a local one (e.g. {@code timeout}). */
    public String errorType() {
        return errorType;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
