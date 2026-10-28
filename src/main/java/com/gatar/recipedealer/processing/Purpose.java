package com.gatar.recipedealer.processing;

import java.util.Locale;

/** Why a run (and its job) exists; stored lower-case. */
public enum Purpose {
    /** Normal processing after an upload. */
    PIPELINE,
    /** A device asked for LLM extraction ({@code POST /v1/captures/{id}/llm-extraction}) or the admin reprocessed. */
    FORCED,
    /** Admin model comparison: never applied, never changes the capture state. */
    COMPARISON;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Purpose of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
