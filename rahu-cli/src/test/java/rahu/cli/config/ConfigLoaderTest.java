package rahu.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("An absent search block loads as the documented off default")
    void searchDefaultsWhenAbsent() throws Exception {
        var cfg = new ConfigLoader().load(write(VALID_CONFIG));
        assertEquals("off", cfg.search().modeOrDefault());
        assertEquals(20, cfg.search().maxCandidatesOrDefault());
    }

    @Test
    @DisplayName("A search block binds mode and candidate cap")
    void searchBindsExplicitValues() throws Exception {
        var cfg = new ConfigLoader().load(write(VALID_CONFIG.replace(
            "\"privacy\"",
            "\"search\": {\"mode\": \"shadow\", \"maxCandidates\": 8},\n          \"privacy\"")));
        assertEquals("shadow", cfg.search().modeOrDefault());
        assertEquals(8, cfg.search().maxCandidatesOrDefault());
    }

    @Test
    @DisplayName("An unusable search mode or cap is refused at load, not silently defaulted")
    void searchRefusesBadValues() throws Exception {
        assertThrows(ConfigError.class, () -> new ConfigLoader().load(
            write(withSearch("{\"mode\": \"on\"}"))),
            "a typo'd mode must fail loudly rather than quietly disabling the feature");
        assertThrows(ConfigError.class, () -> new ConfigLoader().load(
            write(withSearch("{\"maxCandidates\": 0}"))));
        assertThrows(ConfigError.class, () -> new ConfigLoader().load(
            write(withSearch("{\"maxCandidates\": 100000}"))),
            "an unbounded cap could overflow the 16 KiB state bound");
    }

    /** VALID_CONFIG with a search block inserted; anchored on a key it always has. */
    private static String withSearch(String searchBlock) {
        return VALID_CONFIG.replace("\"privacy\"",
            "\"search\": " + searchBlock + ",\n          \"privacy\"");
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

    /**
     * AUDIT-2026-10-03-h. A trace enum typo must be REFUSED, not accepted-and-ignored.
     *
     * <p>Asserting only that a bad value "does not throw" is the defect this test exists
     * to prevent: it passes whether the loader refuses the value or silently downgrades
     * an observability guarantee. So each case asserts the REFUSAL and the reason, and
     * the positive case asserts the value reached the config.
     */
    @Test
    @DisplayName("a trace.capture typo is refused, not silently downgraded to metadata")
    void traceCaptureTypoIsRefused() throws Exception {
        // "payload" is one character from "payloads" and was accepted verbatim, which
        // turned payload capture OFF while the operator's config said it was ON. The
        // downstream `replay` then reported UNAVAILABLE - honest, and still a silent
        // loss of the guarantee, because nothing said the config was wrong.
        Path p = write(VALID_CONFIG.replace("\"capture\": \"metadata\"",
            "\"capture\": \"payload\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("trace.capture"), e.getMessage());
        // The accepted values must be NAMED, or the operator re-guesses the same
        // near-miss spelling; and the message must say what the typo WOULD have
        // caused, because "rejected" alone does not tell them the config was
        // previously accepted and silently degraded their capture.
        assertTrue(e.getMessage().contains("metadata|payloads"), e.getMessage());
        assertTrue(e.getMessage().contains("silently disable"), e.getMessage());
        assertTrue(e.getMessage().contains("UNAVAILABLE"), e.getMessage());
    }

    @Test
    @DisplayName("trace.capture is metadata by default and payloads when asked")
    void traceCaptureReachesTheConfig() throws Exception {
        assertEquals("metadata", new ConfigLoader().load(write(VALID_CONFIG))
            .trace().capture());
        Path on = write(VALID_CONFIG.replace("\"capture\": \"metadata\"",
            "\"capture\": \"payloads\""));
        assertEquals("payloads", new ConfigLoader().load(on).trace().capture());
    }

    /**
     * observability.md:32 requires a persistence failure to STOP new operations, and
     * A16 forbids presenting completion for a run whose trace is incomplete. So
     * `onFailure: warn` promised a degraded mode the spec does not permit. It is
     * refused with an explanation rather than accepted and ignored, which is what
     * happened: an operator asking for warn got stop, and the schema advertised a
     * capability that no code implemented.
     */
    @Test
    @DisplayName("trace.onFailure=warn is refused: the spec has no continue-on-failure mode")
    void traceOnFailureWarnIsRefused() throws Exception {
        Path p = write(VALID_CONFIG.replace("\"onFailure\": \"stop\"",
            "\"onFailure\": \"warn\""));
        ConfigError e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(p));
        assertTrue(e.getMessage().contains("trace.onFailure"), e.getMessage());
        assertTrue(e.getMessage().contains("TRACE_FAILURE"), e.getMessage());
    }

    @Test
    @DisplayName("the published schema does not advertise a value the loader refuses")
    void schemaDoesNotAdvertiseRefusedValues() throws Exception {
        // A schema that offers `warn` while the loader refuses it makes every editor
        // and validator suggest a config that cannot load. The schema is a promise
        // about what the loader accepts, so the two must agree in that direction.
        Path root = Path.of(System.getProperty("user.dir"));
        if (!root.resolve("docs").toFile().exists()) {
            root = root.getParent();
        }
        String schema = Files.readString(
            root.resolve("docs/generated/config.schema.json"));
        assertFalse(schema.contains("\"warn\""),
            "config.schema.json still advertises trace.onFailure=warn, which "
            + "ConfigLoader refuses; regenerate with SchemaGenerator");
    }
}
