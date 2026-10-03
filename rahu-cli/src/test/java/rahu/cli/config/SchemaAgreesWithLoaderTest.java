package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT-2026-10-03-ab: the published schema and the loader disagreed about which keys
 * are legal, so a typo passed editor validation and then failed at load time.
 *
 * <p>The schema set {@code additionalProperties: true} at the top level and left every
 * section unconstrained, while {@link ConfigLoader} rejects any key it does not honour.
 * I confirmed the consequence before changing anything: with a config that is otherwise
 * valid, {@code decision.timeoutMilis}, {@code routing.confidencFloor},
 * {@code tools.resultByte}, {@code trace.captureTypo} and {@code privacy.inputClass}
 * were ALL ACCEPTED by the committed schema. The same keys are refused by the loader.
 *
 * <p>That is the worst direction for a drift: the schema is what an editor consults, so
 * the tool that is supposed to catch the mistake vouched for it first.
 *
 * <p>These tests derive both sides from the code rather than a hand-written list, so
 * they cannot drift into asserting their own fixture. Keys come from
 * {@link ConfigLoader#acceptedTopLevelKeys()} and from the loader's own behaviour (a
 * typo must fail); sections and their keys come from the committed schema.
 */
class SchemaAgreesWithLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal valid config; tests mutate one key at a time. */
    private static final String VALID = """
        {
          "schemaVersion": 1,
          "mode": "offline",
          "decision": {"adapter": "fake", "model": "demo-decision"},
          "generation": {"adapter": "fake"},
          "routing": {"mode": "shadow", "pool": "demo", "baseline": "fast@low",
            "fallback": "fast@low"},
          "pools": {"demo": {"models": [
            {"alias": "fast", "id": "demo-fast", "reasoning": ["low"]}]}},
          "session": {"mode": "in-process"},
          "tools": {"root": "."},
          "trace": {"directory": ".rahu/runs"},
          "privacy": {"mode": "strict"}
        }
        """;

    @TempDir
    Path tmp;

    private static Path schemaFile() {
        Path fromRoot = Path.of("docs/generated/config.schema.json");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        // surefire runs with rahu-cli as the working directory.
        Path fromModule = Path.of("../docs/generated/config.schema.json");
        assertTrue(Files.isRegularFile(fromModule),
            "config.schema.json not found; run SchemaGenerator");
        return fromModule;
    }

    private static JsonNode schema() throws Exception {
        return MAPPER.readTree(schemaFile().toFile());
    }

    // ------------------------------------------------ the loader is the reference

    @Test
    @DisplayName("the loader rejects the typos the schema used to accept")
    void loaderRejectsTheTyposTheSchemaAccepted() throws Exception {
        // Every one of these passed the committed schema and is a plausible typo.
        record Typo(String section, String key, String json) {}
        List<Typo> typos = List.of(
            new Typo("decision", "timeoutMilis", "5000"),
            new Typo("routing", "confidencFloor", "0.5"),
            new Typo("tools", "resultByte", "1024"),
            new Typo("trace", "captureTypo", "\"metadata\""),
            new Typo("privacy", "inputClass", "\"unknown\""));

        List<String> wronglyAccepted = new ArrayList<>();
        for (Typo t : typos) {
            String mutated = VALID.replace("\"" + t.section() + "\": {",
                "\"" + t.section() + "\": {\"" + t.key() + "\": " + t.json() + ",");
            assertTrue(!mutated.equals(VALID),
                "could not inject " + t.key() + " into " + t.section());
            Path file = tmp.resolve("typo.json");
            Files.writeString(file, mutated);
            try {
                new ConfigLoader().load(file);
                wronglyAccepted.add(t.section() + "." + t.key());
            } catch (ConfigError expected) {
                assertTrue(expected.getMessage().contains("unknown"),
                    "a typo must be refused as unknown, but it said: "
                        + expected.getMessage());
            }
        }
        // The point of the test: the LOADER is the one that refuses them.
        assertEquals(List.of(), wronglyAccepted,
            "the loader accepted key(s) it does not honour");
    }

    // -------------------------------------------------- schema must agree

    @Test
    @DisplayName("the schema refuses an unknown key in every section")
    void schemaRefusesUnknownKeysInEverySection() throws Exception {
        JsonNode properties = schema().get("properties");
        assertFalse(schema().path("additionalProperties").asBoolean(true),
            "the schema must refuse unknown top-level keys, because the loader does. "
                + "With additionalProperties true an editor vouches for a typo that the "
                + "loader then rejects at run time.");
        for (Iterator<String> it = properties.fieldNames(); it.hasNext();) {
            String section = it.next();
            JsonNode node = properties.get(section);
            if (node == null || !"object".equals(node.path("type").asText())) {
                continue;
            }
            if (node.has("additionalProperties")
                && !node.get("additionalProperties").isBoolean()) {
                // pools is a MAP of named pools: its additionalProperties is the schema
                // for each VALUE, so it must stay a schema rather than become false.
                assertEquals("pools", section,
                    "only the named-pool map may have a schema-valued "
                        + "additionalProperties; " + section + " has one");
                continue;
            }
            assertFalse(node.path("additionalProperties").asBoolean(true),
                section + " must set additionalProperties:false, because the loader "
                    + "rejects any key it does not honour in " + section);
        }
    }

    @Test
    @DisplayName("the schema documents exactly the keys the loader accepts")
    void schemaDocumentsEveryAcceptedKey() throws Exception {
        JsonNode properties = schema().get("properties");
        List<String> missing = new ArrayList<>();
        for (String key : ConfigLoader.acceptedTopLevelKeys()) {
            if (properties.get(key) == null) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing,
            "the loader honours these keys but the published schema does not document "
                + "them, so an editor flags a valid config as invalid");
    }

    @Test
    @DisplayName("every key the schema documents is one the loader honours")
    void schemaDocumentsNoKeyTheLoaderRejects() throws Exception {
        // A key in the schema that the loader refuses is the mirror-image drift: the
        // editor accepts a config that can never load.
        JsonNode properties = schema().get("properties");
        List<String> extra = new ArrayList<>();
        for (Iterator<String> it = properties.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!ConfigLoader.acceptedTopLevelKeys().contains(key)) {
                extra.add(key);
            }
        }
        assertEquals(List.of(), extra,
            "the schema documents key(s) the loader does not accept");
    }

    @Test
    @DisplayName("a config the schema accepts is a config the loader accepts")
    void schemaAcceptedConfigLoads() throws Exception {
        // The end-to-end direction that matters: nothing may validate and then fail.
        // Guard against the fixture drifting into invalidity, which would make the
        // comparison below vacuous.
        Path good = tmp.resolve("good.json");
        Files.writeString(good, VALID);
        assertDoesNotThrowOnLoad(good);

        JsonNode properties = schema().get("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext();) {
            var entry = it.next();
            JsonNode section = entry.getValue();
            if (!"object".equals(section.path("type").asText())) {
                continue;
            }
            JsonNode sectionProps = section.get("properties");
            if (sectionProps == null) {
                continue;
            }
            for (Iterator<String> keys = sectionProps.fieldNames(); keys.hasNext();) {
                String sectionKey = keys.next();
                JsonNode mutatedTree = MAPPER.readTree(VALID);
                JsonNode target = mutatedTree.get(entry.getKey());
                if (target == null) {
                    // A section the schema documents but the fixture omits (they are
                    // optional). Nothing to mutate, so this pair proves nothing here.
                    continue;
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) target)
                    .put(sectionKey, "0.65");
                Path file = tmp.resolve("mutated.json");
                Files.writeString(file, mutatedTree.toString());
                // A wrong TYPE is a legitimate schema error; this test only cares that
                // the key itself is not rejected as unknown, so compare messages.
                try {
                    new ConfigLoader().load(file);
                } catch (ConfigError e) {
                    assertTrue(!e.getMessage().contains("unknown config key"),
                        entry.getKey() + "." + sectionKey
                            + " is in the schema but the loader calls it unknown");
                }
            }
        }
    }

    private void assertDoesNotThrowOnLoad(Path file) {
        RahuConfig cfg = new ConfigLoader().load(file);
        assertEquals("offline", cfg.mode(),
            "fixture must stay valid for this test to mean anything");
    }
}