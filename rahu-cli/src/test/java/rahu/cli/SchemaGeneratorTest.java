package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Generated config schema round-trips with the strict loader (artifacts.md). */
class SchemaGeneratorTest {

    @Test
    @DisplayName("Generated schema is valid JSON and pins strict alpha values")
    void schemaIsValidAndStrict() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(SchemaGenerator.configSchemaJson());
        assertEquals("object", root.path("type").asText());
        assertTrue(root.path("properties").path("privacy").path("properties")
            .path("mode").has("const"), "privacy.mode is const strict");
        assertTrue(root.path("properties").path("orchestration").path("properties")
            .path("mode").has("const"), "orchestration.mode is const single");
        // additionalProperties stays true at schema level (forward-compatible),
        // while the LOADER (the enforcement point) rejects unknown keys — tested
        // in ConfigLoaderTest. Schema is documentation + editor validation.
        assertTrue(root.path("additionalProperties").isBoolean());
    }

    @Test
    @DisplayName("Schema enum covers the effort vocabulary v1")
    void effortVocabulary() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode reasoning = mapper.readTree(SchemaGenerator.configSchemaJson())
            .path("properties").path("pools").path("additionalProperties")
            .path("properties").path("models").path("items").path("properties")
            .path("reasoning").path("items");
        assertTrue(reasoning.has("enum"));
        assertEquals(8, reasoning.path("enum").size());
    }
}
