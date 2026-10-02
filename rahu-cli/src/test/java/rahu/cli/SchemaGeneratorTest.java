package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @DisplayName("The committed artifact matches the generator byte for byte")
    void committedArtifactIsInSync() throws Exception {
        // Guards two real defects: the published schema omitting a key the loader
        // accepts (so editor validation flagged valid configs), and a hand-edited
        // artifact drifting from its generator.
        Path artifact = repoFile("docs/generated/config.schema.json");
        assertTrue(Files.exists(artifact),
            "docs/generated/config.schema.json is missing; regenerate with SchemaGenerator");
        assertEquals(SchemaGenerator.configSchemaJson(),
            Files.readString(artifact, StandardCharsets.UTF_8),
            "committed config.schema.json is stale — re-run SchemaGenerator and commit it");
    }

    @Test
    @DisplayName("The schema documents every top-level key the loader accepts")
    void schemaDocumentsEveryAcceptedKey() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode properties = mapper.readTree(SchemaGenerator.configSchemaJson())
            .path("properties");
        for (String key : rahu.cli.config.ConfigLoader.acceptedTopLevelKeys()) {
            assertTrue(properties.has(key),
                "config.schema.json does not document loader-accepted key \"" + key + "\"");
        }
        assertTrue(properties.path("injection").path("properties").path("mode").has("enum"),
            "injection.mode must publish its vocabulary, not accept free text");
    }

    private static Path repoFile(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        return root.resolve(relative);
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
