package com.gatar.recipedealer.capture;

import java.util.List;

/** The capture is rejected with 400; the phone shows the details and does not retry. */
public class InvalidCaptureException extends RuntimeException {

    private final List<String> details;

    public InvalidCaptureException(List<String> details) {
        super(String.join("; ", details));
        this.details = List.copyOf(details);
    }

    public InvalidCaptureException(String detail) {
        this(List.of(detail));
    }

    public List<String> details() {
        return details;
    }
}
