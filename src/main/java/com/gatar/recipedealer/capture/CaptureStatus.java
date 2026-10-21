package com.gatar.recipedealer.capture;

/** Processing status reported to the phone (lower-case in JSON and in the database). */
public enum CaptureStatus {
    ACCEPTED, PROCESSING, NEEDS_DECISION, NEEDS_ATTENTION, DONE;

    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static CaptureStatus of(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
