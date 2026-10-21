package com.gatar.recipedealer.web;

import java.util.List;

/** Error body understood by the phone: {@code {"error": "invalid_capture", "details": ["..."]}}. */
public record ApiError(String error, List<String> details) {

    public static ApiError of(String error, String... details) {
        return new ApiError(error, List.of(details));
    }

    /** Hand-written JSON for servlet filters that run before Spring MVC. */
    public String toJson() {
        var sb = new StringBuilder("{\"error\":").append(quote(error)).append(",\"details\":[");
        for (int i = 0; i < details.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(quote(details.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
