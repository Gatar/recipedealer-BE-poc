package com.gatar.recipedealer.capture;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.capture.CaptureRepository.NewAttachment;
import com.gatar.recipedealer.capture.CaptureRepository.NewCapture;
import com.gatar.recipedealer.common.Hashing;

import jakarta.servlet.http.Part;
import tools.jackson.databind.JsonNode;

/**
 * Stores a capture: validate, write all blobs, then one transaction for the capture rows. A crash between the
 * two steps leaves only orphan blobs, never rows pointing to missing blobs.
 */
@Service
public class CaptureIngestService {

    public static final String CAPTURE_PART = "capture";

    private static final Logger log = LoggerFactory.getLogger(CaptureIngestService.class);

    private final CaptureSchemaValidator validator;
    private final BlobStore blobs;
    private final CaptureRepository captures;
    private final TransactionTemplate tx;

    public CaptureIngestService(CaptureSchemaValidator validator, BlobStore blobs, CaptureRepository captures,
                                TransactionTemplate tx) {
        this.validator = validator;
        this.blobs = blobs;
        this.captures = captures;
        this.tx = tx;
    }

    /** @return true if the capture was created, false if it already existed (the stored one wins) */
    public boolean ingest(UUID captureId, Collection<Part> parts, DevicePrincipal device) throws IOException {
        Map<String, Part> byName = new LinkedHashMap<>();
        for (Part part : parts) {
            if (byName.putIfAbsent(part.getName(), part) != null) {
                throw new InvalidCaptureException("duplicate part '" + part.getName() + "'");
            }
        }
        Part capturePart = byName.remove(CAPTURE_PART);
        if (capturePart == null) {
            throw new InvalidCaptureException("missing part '" + CAPTURE_PART + "'");
        }
        byte[] body;
        try (InputStream in = capturePart.getInputStream()) {
            body = in.readAllBytes();
        }

        if (alreadyStored(captureId, body)) {
            return false;
        }

        JsonNode json = validator.validate(body);
        if (!captureId.toString().equalsIgnoreCase(json.path("captureId").asString())) {
            throw new InvalidCaptureException("captureId in the body does not match the URL");
        }
        List<JsonNode> declared = declaredAttachments(json, byName);

        BlobRef bodyBlob = blobs.put(new ByteArrayInputStream(body));
        List<NewAttachment> attachments = new ArrayList<>();
        for (JsonNode attachment : declared) {
            Part part = byName.get(attachment.get("name").asString());
            BlobRef blob;
            try (InputStream in = part.getInputStream()) {
                blob = blobs.put(in);
            }
            attachments.add(new NewAttachment(part.getName(), attachment.get("role").asString(),
                    attachment.get("mimeType").asString(), part.getSubmittedFileName(), blob,
                    intOrNull(attachment.get("width")), intOrNull(attachment.get("height"))));
        }

        JsonNode source = json.get("source");
        var capture = new NewCapture(captureId, OffsetDateTime.parse(json.get("capturedAt").asString()),
                device.userId(), device.deviceId(), json.get("schemaVersion").asInt(), bodyBlob, body,
                source.get("type").asString(), textOrNull(source.get("url")), attachments);
        Boolean created = tx.execute(status -> captures.insert(capture));
        if (!Boolean.TRUE.equals(created)) {
            // Lost a race with a concurrent upload of the same capture.
            alreadyStored(captureId, body);
            return false;
        }
        log.info("Accepted capture {} ({}, {} attachments) from device {}", captureId, capture.sourceType(),
                attachments.size(), device.deviceId());
        return true;
    }

    private boolean alreadyStored(UUID captureId, byte[] body) {
        return captures.findBodySha256(captureId).map(storedSha -> {
            String newSha = Hashing.sha256Hex(body);
            if (!storedSha.equals(newSha)) {
                log.warn("Capture {} uploaded again with a different body (stored {}, new {}); keeping the stored one",
                        captureId, storedSha, newSha);
            }
            return true;
        }).orElse(false);
    }

    /** Every declared attachment needs exactly one part with its name, and every extra part must be declared. */
    private static List<JsonNode> declaredAttachments(JsonNode json, Map<String, Part> parts) {
        List<JsonNode> declared = new ArrayList<>();
        var names = new LinkedHashSet<String>();
        List<String> problems = new ArrayList<>();
        for (JsonNode attachment : json.get("attachments")) {
            String name = attachment.get("name").asString();
            if (!names.add(name)) {
                problems.add("attachment '" + name + "' is declared twice");
            } else if (!parts.containsKey(name)) {
                problems.add("attachment '" + name + "' has no matching part");
            } else {
                declared.add(attachment);
            }
        }
        for (String partName : parts.keySet()) {
            if (!names.contains(partName)) {
                problems.add("part '" + partName + "' is not declared in attachments");
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidCaptureException(problems);
        }
        return declared;
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
