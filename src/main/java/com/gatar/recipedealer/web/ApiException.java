package com.gatar.recipedealer.web;

import org.springframework.http.HttpStatus;

/** An error with its HTTP status and machine-readable code, rendered as {@link ApiError}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    public static ApiException conflict(String error, String message) {
        return new ApiException(HttpStatus.CONFLICT, error, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
