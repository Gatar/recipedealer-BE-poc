package com.gatar.recipedealer.capture;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates capture JSON against {@code schema/capture-v1.json}, including {@code uuid} and {@code date-time} formats. */
@Component
public class CaptureSchemaValidator {

    private final JsonMapper jsonMapper;
    private final Schema schemaV1;
    private final Schema recipeSchema;

    public CaptureSchemaValidator(JsonMapper jsonMapper) throws IOException {
        this.jsonMapper = jsonMapper;
        var config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).locale(Locale.ENGLISH).build();
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(config));
        JsonNode schemaJson;
        try (InputStream in = new ClassPathResource("schema/capture-v1.json").getInputStream()) {
            schemaJson = jsonMapper.readTree(in);
        }
        this.schemaV1 = registry.getSchema(schemaJson);
        // The recipe DTO as a schema of its own, for user edits: the same $defs with the recipe as root.
        ObjectNode recipeRoot = jsonMapper.createObjectNode();
        recipeRoot.set("$schema", schemaJson.get("$schema"));
        recipeRoot.set("$defs", schemaJson.get("$defs"));
        recipeRoot.put("$ref", "#/$defs/recipe");
        recipeRoot.put("type", "object");
        this.recipeSchema = registry.getSchema(recipeRoot);
    }

    /** Validates a recipe DTO (the {@code recipe} object of the contract) sent by an editor. */
    public void validateRecipe(JsonNode recipe) {
        var errors = recipeSchema.validate(recipe);
        if (!errors.isEmpty()) {
            throw new InvalidCaptureException("invalid_recipe", errors.stream()
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                    .toList());
        }
    }

    /** Parses and validates the capture; returns the parsed tree. */
    public JsonNode validate(byte[] json) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidCaptureException("capture is not valid JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new InvalidCaptureException("capture must be a JSON object");
        }
        var errors = schemaV1.validate(node);
        if (!errors.isEmpty()) {
            throw new InvalidCaptureException(errors.stream()
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                    .toList());
        }
        return node;
    }
}
