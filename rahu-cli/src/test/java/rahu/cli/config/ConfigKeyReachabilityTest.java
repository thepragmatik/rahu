package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.SchemaGenerator;

/**
 * AUDIT-2026-10-03-s: an unknown key inside a section loaded clean.
 *
 * <p>{@link ConfigLoader#bind} validated the root object against {@code TOP_KEYS} and
 * then handed each section to a {@code bind*} method that read the keys it knew and
 * ignored everything else. So a typo one level down was accepted silently:
 *
 * <pre>{@code
 * "privacy": {"mode": "strict", "onUnkown": "block"}   // loads, and privacy is on
 * }</pre>
 *
 * <p>This is the register's central pattern — <em>a capability that was configured but
 * which the code did not implement</em> — in its worst form, because it does not even
 * need a real key to go wrong. Any invented name works. The root check made this look
 * covered: A15 proves {@code unknownTop} is refused, and a reader reasonably concludes
 * unknown keys are refused.
 *
 * <p>configuration.md requires it explicitly: "reject other values and unknown privacy
 * keys". Nothing rejected them.
 */
class ConfigKeyReachabilityTest {

    private static final String VALID_CONFIG = """
        {
          "schemaVersion": 1,
          "mode": "offline",
          "decision": {"adapter": "fake", "model": "demo-decision"},
          "generation": {"adapter": "fake"},
          "routing": {
            "mode": "shadow",
            "pool": "demo",
            "baseline": "fast@low",
            "fallback": "quality@medium",
            "confidenceField": "chosen_probability",
            "confidenceFloor": 0.65
          },
          "pools": {
            "demo": {
              "models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low", "medium"]},
                {"alias": "quality", "id": "demo-quality", "reasoning": ["medium", "high"]}
              ]
            }
          },
          "context": {"instructionFiles": []},
          "agent": {"maxGenerationAttempts": 3, "maxCompactions": 2, "maxCostUsd": "1.00"},
          "catalog": {"cacheTtlSeconds": 86400, "allowStale": false},
          "summarisation": {"pool": "demo", "baseline": "fast@low", "fallback": "quality@medium"},
          "search": {"mode": "off", "maxCandidates": 20},
          "injection": {"mode": "off", "threshold": 0.10},
          "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "3.00"},
          "orchestration": {"mode": "single"},
          "tools": {"root": ".", "enabled": ["workspace.list", "workspace.read", "workspace.search"]},
          "trace": {"directory": ".rahu/runs", "capture": "metadata", "onFailure": "stop"},
          "privacy": {"mode": "strict", "onUnknown": "block", "inputClassification": "unknown"}
        }
        """;

    @TempDir
    Path tmp;

    private Path write(String content) throws Exception {
        Path p = tmp.resolve("config.json");
        Files.writeString(p, content);
        return p;
    }

    /** Inserts {@code key} into the JSON object at {@code dottedPath}. */
    private static String withKey(String json, String dottedPath, String key, String literal) {
        String[] parts = dottedPath.split("\\.");
        StringBuilder needle = new StringBuilder();
        for (String p : parts) {
            needle.append("\"").append(p).append("\"");
        }
        int at = json.indexOf(needle.toString());
        if (at < 0) {
            throw new IllegalArgumentException("no such section in fixture: " + dottedPath);
        }
        int brace = json.indexOf('{', at);
        return json.substring(0, brace + 1)
            + "\n \"" + key + "\": " + literal + ","
            + json.substring(brace + 1);
    }

    /** Every section that accepts keys, and a plausible misspelling of a real one. */
    private static Map<String, String> sectionTypos() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("decision", "confidenceSemantics");
        m.put("generation", "apiKeyEnvv");
        m.put("routing", "confidenceFloorr");
        m.put("session", "maxTurnss");
        m.put("orchestration", "modee");
        m.put("tools", "maxCallsPerStepp");
        m.put("trace", "onFailuree");
        m.put("privacy", "onUnkown");
        m.put("context", "maxPromptTokenss");
        m.put("agent", "maxCompactionss");
        m.put("catalog", "allowStalee");
        m.put("summarisation", "fallbak");
        m.put("search", "maxCandidatess");
        m.put("injection", "threshol");
        return m;
    }

    @Test
    @DisplayName("A15's root check does not extend to sections: this is the defect")
    void rootIsCheckedButSectionsWereNot() throws Exception {
        // The control. This one has always worked, and it is why the gap went unnoticed.
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader()
            .load(write(VALID_CONFIG.replace("\"mode\": \"offline\",",
                "\"mode\": \"offline\", \"unknownTop\": true,"))));
        assertTrue(e.getMessage().contains("unknownTop"), e.getMessage());
    }

    @Test
    @DisplayName("An unknown key in ANY section is refused with its full field path")
    void unknownKeyInEverySectionIsRefused() throws Exception {
        var failures = new java.util.ArrayList<String>();
        for (var entry : sectionTypos().entrySet()) {
            String section = entry.getKey();
            String bad = entry.getValue();
            String json = withKey(VALID_CONFIG, section, bad, "true");
            try {
                new ConfigLoader().load(write(json));
                failures.add(section + "." + bad + " was ACCEPTED");
            } catch (ConfigError e) {
                // The message must name the full path, or an operator with a 40-line
                // config still cannot find the typo.
                if (!e.getMessage().contains(section + "." + bad)) {
                    failures.add(section + "." + bad + " refused without its path: "
                        + e.getMessage());
                }
            }
        }
        assertEquals(List.of(), failures,
            "unknown keys must be refused wherever they appear, and named by path");
    }

    @Test
    @DisplayName("A misspelled key that changes SAFETY behaviour is refused, not defaulted")
    void misspelledSafetyKeyIsRefusedRatherThanDefaulted() throws Exception {
        // The sharpest version: "onUnknown" misspelled leaves privacy.onUnknown at its
        // built-in default. Today that default happens to be "block", so the run is
        // safe by luck rather than by configuration, and the operator's file says
        // something the system never read.
        String json = VALID_CONFIG.replace("\"onUnknown\": \"block\"", "\"onUnkown\": \"block\"");
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(write(json)));
        assertTrue(e.getMessage().contains("privacy.onUnkown"), e.getMessage());
    }

    /**
     * Mutates the parsed tree rather than the text.
     *
     * <p>The first version rewrote the fixture with String.replace, which is how it
     * managed to pass a pool-level typo while silently not applying the model-level
     * one at all: a replacement that does not match leaves the config untouched, and
     * an untouched config correctly loads. A test that cannot fail is worse than none.
     */
    private Path writeMutated(java.util.function.Consumer<JsonNode> mutate)
        throws Exception {
        var mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(VALID_CONFIG);
        mutate.accept(root);
        return write(mapper.writeValueAsString(root));
    }

    @Test
    @DisplayName("An unknown key inside a pool and inside a pool model is refused")
    void unknownKeyInsidePoolsIsRefused() throws Exception {
        ConfigError poolLevel = assertThrows(ConfigError.class, () -> new ConfigLoader()
            .load(writeMutated(root ->
                ((ObjectNode) root.path("pools").path("demo"))
                    .put("modelz", "[]"))));
        assertTrue(poolLevel.getMessage().contains("pools.demo.modelz"), poolLevel.getMessage());

        ConfigError modelLevel = assertThrows(ConfigError.class, () -> new ConfigLoader()
            .load(writeMutated(root -> {
                var model = (ObjectNode)
                    root.path("pools").path("demo").path("models").get(0);
                model.remove("reasoning");
                model.put("reasoninge", "low");
            })));
        assertTrue(modelLevel.getMessage().contains("pools.demo.fast.reasoninge"),
            modelLevel.getMessage());
    }

    @Test
    @DisplayName("Every key the loader accepts is documented, and every documented key loads")
    void loaderAndSchemaAgreeOnEveryKey() throws Exception {
        // The reason the drift was possible: three places name the key set - this
        // loader, SchemaGenerator's hardcoded schema string, and configuration.md.
        // Nothing compared them, so a key could be read but undocumented, documented
        // but unread, or refused by the loader and offered by the schema.
        var mapper = new ObjectMapper();
        JsonNode documented = mapper.readTree(SchemaGenerator.configSchemaJson()).get("properties");
        var fields = documented.fieldNames();
        var problems = new java.util.ArrayList<String>();
        while (fields.hasNext()) {
            String section = fields.next();
            var node = documented.get(section);
            if (node.path("type").asText().equals("object")) {
                var props = node.path("properties").fieldNames();
                while (props.hasNext()) {
                    String key = props.next();
                    // A null literal is legal for every key regardless of its own
                    // validation, so the only failure mode left is the loader not
                    // RECOGNISING the name.
                    try {
                        new ConfigLoader().load(write(withKey(VALID_CONFIG, section, key,
                            "null")));
                    } catch (ConfigError e) {
                        if (e.getMessage().contains("unknown config key")) {
                            problems.add(section + "." + key
                                + " is in the schema but the loader refuses it as unknown");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), problems,
            "the generated schema must not offer a key the loader refuses");
    }
}
