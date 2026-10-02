package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S03 configuration v1 validation (configuration.md; A15, A25-partial). */
class ConfigLoaderTest {

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

    @Test
    @DisplayName("Valid example config parses into a typed tree")
    void validConfigParses() throws Exception {
        RahuConfig c = new ConfigLoader().load(write(VALID_CONFIG));
        assertEquals(1, c.schemaVersion());
        assertEquals("offline", c.mode());
        assertEquals("shadow", c.routing().mode());
        assertEquals(2, c.pools().get("demo").size());
        assertEquals(new BigDecimal("3.00"), c.session().maxCostUsd());
        assertEquals("single", c.orchestration().mode());
        assertEquals("strict", c.privacy().mode());
    }

    @Test
    @DisplayName("A15: unknown keys fail with the field path and a next action")
    void unknownKeyFailsWithFieldPath() throws Exception {
        Path p = write(VALID_CONFIG.replace(
            "\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"unknownTop\": true,"));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("unknownTop"));
        assertTrue(e.getMessage().contains("remove"));
    }

    @Test
    @DisplayName("Duplicate JSON keys are rejected")
    void duplicateKeysRejected() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"mode\": \"offline\",",
            "\"mode\": \"offline\", \"mode\": \"live\","));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("duplicate"));
    }

    @Test
    @DisplayName("maxCostUsd parses exactly; exponent/negative forms are rejected")
    void moneyParsing() throws Exception {
        RahuConfig c = new ConfigLoader().load(write(VALID_CONFIG));
        assertEquals(new BigDecimal("3.00"), c.session().maxCostUsd());

        Path bad = write(VALID_CONFIG.replace("\"maxCostUsd\": \"3.00\"",
            "\"maxCostUsd\": \"1e3\""));
        assertThrows(ConfigError.class, () -> new ConfigLoader().load(bad));

        Path neg = write(VALID_CONFIG.replace("\"maxCostUsd\": \"3.00\"",
            "\"maxCostUsd\": \"-1.00\""));
        assertThrows(ConfigError.class, () -> new ConfigLoader().load(neg));
    }

    @Test
    @DisplayName("A25: orchestration.mode other than single is rejected before I/O")
    void orchestrationSingleOnly() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"mode\": \"single\"", "\"mode\": \"multi\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("orchestration.mode"));
        assertTrue(e.getMessage().contains("single"));
    }

    @Test
    @DisplayName("Privacy mode/onUnknown only accept the strict alpha values")
    void privacyStrictOnly() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"mode\": \"strict\"", "\"mode\": \"off\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("privacy.mode"));
    }

    @Test
    @DisplayName("An absent injection block loads as the documented off default")
    void injectionDefaultsWhenAbsent() throws Exception {
        var cfg = new ConfigLoader().load(write(VALID_CONFIG));
        assertEquals("off", cfg.injection().modeOrDefault());
        assertEquals(0.10, cfg.injection().thresholdOrDefault());
    }

    @Test
    @DisplayName("injection.mode only accepts off|shadow|enforce")
    void injectionModeVocabulary() throws Exception {
        Path shadow = write(VALID_CONFIG.replace("\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"injection\": {\"mode\": \"shadow\"},"));
        assertEquals("shadow", new ConfigLoader().load(shadow).injection().modeOrDefault());
        Path bogus = write(VALID_CONFIG.replace("\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"injection\": {\"mode\": \"block\"},"));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(bogus));
        assertTrue(e.getMessage().contains("injection.mode"));
        assertTrue(e.getMessage().contains("off|shadow|enforce"));
    }

    @Test
    @DisplayName("injection.threshold outside [0,1] is refused before any call")
    void injectionThresholdRange() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"injection\": {\"threshold\": 1.5},"));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("injection.threshold"));
    }

    @Test
    @DisplayName("Routing references must resolve within their named pool")
    void routingReferencesResolve() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"baseline\": \"fast@low\"",
            "\"baseline\": \"missing@low\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("baseline"));
        assertTrue(e.getMessage().contains("missing@low"));
    }

    @Test
    @DisplayName("Env interpolation resolves documented fields; missing env is an error")
    void envInterpolation() throws Exception {
        Path withEnv = write(VALID_CONFIG.replace("\"id\": \"demo-fast\"",
            "\"id\": \"${RAHU_TEST_MODEL}\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(withEnv));
        assertTrue(e.getMessage().contains("RAHU_TEST_MODEL"));
    }
}
