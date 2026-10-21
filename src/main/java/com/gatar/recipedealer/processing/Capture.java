package com.gatar.recipedealer.processing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** A stored capture as seen by extractors: the original JSON (parsed from its blob) and attachment blobs. */
public record Capture(UUID id, Instant receivedAt, UUID userId, UUID deviceId, int schemaVersion, String bodySha256,
                      JsonNode body, List<Attachment> attachments) {

    public record Attachment(String name, String role, String mediaType, String blobSha256, long sizeBytes) {
    }

    public JsonNode recipe() {
        return body.path("recipe");
    }

    /** Field → stage, e.g. {@code ingredients → heuristic}. */
    public Map<String, String> provenance() {
        Map<String, String> provenance = new LinkedHashMap<>();
        body.path("provenance").properties().forEach(field -> {
            if (field.getValue().isString()) {
                provenance.put(field.getKey(), field.getValue().asString());
            }
        });
        return provenance;
    }
}
