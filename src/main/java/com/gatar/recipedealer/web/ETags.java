package com.gatar.recipedealer.web;

import java.util.Arrays;
import java.util.Objects;

/** Strong entity tags of the form {@code "<value>"}. */
public final class ETags {

    private ETags() {
    }

    public static String of(Object value) {
        return "\"" + value + "\"";
    }

    /** True if the {@code If-None-Match} / {@code If-Match} header lists the value (or {@code *}). */
    public static boolean matches(String header, Object value) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String expected = Objects.toString(value);
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .map(tag -> tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"") ? tag.substring(1, tag.length() - 1) : tag)
                .anyMatch(tag -> tag.equals("*") || tag.equalsIgnoreCase(expected));
    }
}
