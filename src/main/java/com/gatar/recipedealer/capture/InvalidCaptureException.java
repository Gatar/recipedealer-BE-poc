package com.gatar.recipedealer.capture;

import java.util.List;

/** The capture (or an edited recipe) is rejected with 400; the phone shows the details and does not retry. */
public class InvalidCaptureException extends RuntimeException {

    private final String error;
    private final List<String> details;

    public InvalidCaptureException(String error, List<String> details) {
        super(String.join("; ", details));
        this.error = error;
        this.details = List.copyOf(details);
    }

    public InvalidCaptureException(List<String> details) {
        this("invalid_capture", details);
    }

    public InvalidCaptureException(String detail) {
        this(List.of(detail));
    }

    public String error() {
        return error;
    }

    public List<String> details() {
        return details;
    }
}
