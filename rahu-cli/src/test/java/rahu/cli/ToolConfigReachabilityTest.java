package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.core.tools.PathBoundary;
import rahu.systemone.DecisionEngine;

/**
 * Are the tool configuration keys actually reachable?
 *
 * <p>Audit finding F-4. {@code tools.enabled} and {@code tools.exclusions} were both
 * parsed into the typed config and then never read by any production path. An operator
 * who disabled a tool, or excluded a path, got the default set anyway. Both keys are
 * documented in configuration.md line 24 and both appear in the committed schema, so
 * this is the "documented capability that is unreachable" shape, twice over.
 *
 * <p>Configs are built through the real {@link ConfigLoader} from JSON rather than
 * assembled by hand, so the test cannot pass against a shape the loader never produces.
 */
class ToolConfigReachabilityTest {

    @TempDir
    Path tmp;

    @TempDir
    Path root;

    private Path writeConfig(String toolsBlock) throws Exception {
        Path p = tmp.resolve("config.json");
        Files.writeString(p, """
            {
              "schemaVersion": 1,
              "mode": "live",
              "decision": {"adapter": "openrouter-decisions", "model": "m",
                           "baseUrl": "https://example.invalid/decisions"},
              "generation": {"adapter": "openrouter", "baseUrl": "https://example.invalid/v1",
                             "apiKeyEnv": "TEST_KEY"},
              "routing": {"mode": "shadow", "pool": "demo",
                          "baseline": "fast@low", "fallback": "fast@low",
                          "confidenceField": "chosen_probability", "confidenceFloor": 0.65},
              "pools": {"demo": {"models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low"]}]}},
              "context": {"instructionFiles": []},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "3.00"},
              "orchestration": {"mode": "single"},
              "tools": %s,
              "trace": {"directory": ".rahu/runs", "capture": "metadata", "onFailure": "stop"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                          "inputClassification": "unknown"}
            }
            """.formatted(toolsBlock));
        return p;
    }

    @Test
    @DisplayName("tools.enabled narrows what the model is offered")
    void enabledListNarrowsTheRegistry() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [\"workspace.read\"]}"));
        var registry = ChatCommand.workspaceRegistry(cfg, new PathBoundary(root));

        assertEquals(List.of("workspace.read"),
            registry.all().stream().map(t -> t.name()).toList(),
            "an operator who enabled only workspace.read must not have the other two "
                + "advertised to the model");
    }

    @Test
    @DisplayName("an empty tools.enabled keeps all three, so a sparse config still loads")
    void emptyEnabledKeepsEverything() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig("{\"root\": \".\", \"enabled\": []}"));
        var registry = ChatCommand.workspaceRegistry(cfg, new PathBoundary(root));

        assertEquals(3, registry.all().size(),
            "an empty list must not silently disable every tool");
    }

    @Test
    @DisplayName("tools.enabled naming an unknown tool fails loudly at wiring")
    void unknownEnabledToolIsRefused() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [\"workspace.read\", \"shell.exec\"]}"));

        assertThrows(IllegalArgumentException.class,
            () -> ChatCommand.workspaceRegistry(cfg, new PathBoundary(root)),
            "a key naming a tool that does not exist must fail loudly, or the operator "
                + "believes they restricted something they did not");
    }

    @Test
    @DisplayName("tools.exclusions reaches the path boundary, case-insensitively")
    void exclusionsReachTheBoundary() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [], \"exclusions\": [\"SecretNotes\"]}"));
        var boundary = new PathBoundary(root, cfg.tools().exclusions());

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve("SecretNotes/plan.md"),
            "an operator-excluded name must actually be excluded");
        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve("secretnotes/plan.md"),
            "exclusion matching must be case-insensitive");
        assertTrue(boundary.resolve("src/Main.java").toString().endsWith("Main.java"),
            "an ordinary path must still resolve");
    }

    @Test
    @DisplayName("directory exclusions match case-insensitively")
    void directoryExclusionsAreCaseInsensitive() {
        var boundary = new PathBoundary(root);

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve(".GIT/config"),
            ".GIT must be excluded as surely as .git; a case-sensitive exclusion list is "
                + "a bypass on a case-preserving filesystem");
    }

    @Test
    @DisplayName("credential-like defaults still apply when exclusions are configured")
    void defaultsSurviveOperatorExclusions() throws Exception {
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \".\", \"enabled\": [], \"exclusions\": [\"SecretNotes\"]}"));
        var boundary = new PathBoundary(root, cfg.tools().exclusions());

        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> boundary.resolve(".env"),
            "supplying operator exclusions must not displace the credential-name defaults");
    }

    @Test
    @DisplayName("tools.resultBytes reaches the executor: a cut in the wiring is caught")
    void resultBytesReachesTheExecutor() throws Exception {
        // The third key in this file's remit, and the one that was parsed, exposed in
        // the schema, documented in configuration.md:24 - and never applied. The cap
        // existed as a hardcoded 64 KiB constant, so an operator setting
        // resultBytes: 1500 got 64 KiB and no warning.
        //
        // Executor-level tests CANNOT catch a cut in the wiring: they construct
        // WorkspaceTools directly. This test goes through the real ConfigLoader and
        // asks for the value the way assembly does, which is the only place a
        // disconnect between the config and the executor is visible.
        var cfg = new ConfigLoader().load(writeConfig(
            "{\"root\": \"%s\", \"enabled\": [\"workspace.read\"],"
                + " \"exclusions\": [], \"resultBytes\": 1500}".formatted(root)));
        assertEquals(1500, cfg.tools().resultBytes(),
            "the loader must carry the configured value");

        int effective = rahu.cli.live.LiveAssembly.resultBytes(cfg);
        assertEquals(1500, effective,
            "assembly must pass the CONFIGURED cap, not the built-in default - a cut "
                + "here silently returns 64 KiB while the config insists on 1500");

        // And absent config falls back to the documented default.
        var bare = new ConfigLoader().load(writeConfig(
            "{\"root\": \"%s\", \"enabled\": [], \"exclusions\": []}".formatted(root)));
        assertEquals(rahu.core.tools.WorkspaceTools.DEFAULT_RESULT_BYTES,
            rahu.cli.live.LiveAssembly.resultBytes(bare),
            "an absent resultBytes must fall back to 64 KiB, not to zero or unbounded");
        // THE CALL SITE, not the helper. A helper-only assertion proved nothing about
        // what assembly actually hands the loop: a mutation replacing
        // `resultBytes(cfg)` with the default at the original call site passed every
        // test. So build the loop through LiveAssembly's own factory - the same method
        // build() calls - with no credential and no network, and read the cap back.
        var provider = new rahu.core.model.ModelProvider() {
            @Override
            public rahu.core.model.ModelOutcome generate(
                rahu.core.model.GenerationRequest request) {
                return new rahu.core.model.ModelOutcome.Completed("x", java.util.List.of(),
                    new rahu.core.model.Usage(1, 1, null, 0L), "stop",
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    rahu.core.model.ContinuationEnvelope.empty("t"));
            }
        };
        var engine = new DecisionEngine() {
            @Override
            public java.util.Map<String, rahu.core.decision.DecisionResult> askAll(
                State state, java.util.List<Question> questions) {
                java.util.Map<String, rahu.core.decision.DecisionResult> out =
                    new java.util.LinkedHashMap<>();
                for (Question q : questions) {
                    out.put(DecisionEngine.questionId(q),
                        new rahu.core.decision.DecisionResult.Failure(
                            rahu.core.decision.DecisionResult.FailureKind.UNSUPPORTED, "n/a"));
                }
                return out;
            }
        };
        var assembled = rahu.cli.live.LiveAssembly.toolLoop(cfg,
            new PathBoundary(root), provider, engine,
            new rahu.core.privacy.Provenance.Synthetic("test-fixture"));
        assertEquals(1500, assembled.resultBytes(),
            "the loop ASSEMBLY builds must carry the CONFIGURED cap - this exercises "
                + "assembly's own factory, so a cut between the config and the "
                + "executor cannot pass");

        // Same, through the DEFAULT cap, so the fallback is wired too.
        var assembledBare = rahu.cli.live.LiveAssembly.toolLoop(bare,
            new PathBoundary(root), provider, engine,
            new rahu.core.privacy.Provenance.Synthetic("test-fixture"));
        assertEquals(rahu.core.tools.WorkspaceTools.DEFAULT_RESULT_BYTES,
            assembledBare.resultBytes(),
            "an absent resultBytes must reach the loop as 64 KiB");
        // POSITIVITY of the ASSEMBLED loop, not of the helper. A mutation making the
        // helper return 0 for an absent key produced a loop whose cap is ZERO: every
        // tool result would be truncated to nothing while still reporting SUCCESS -
        // the unobserved-as-empty defect. Asserting the helper's return value caught
        // nothing here, because the helper's value was still positive in the branch
        // the test exercised; only the loop that USES it shows the damage.
        assertTrue(assembledBare.resultBytes() > 0,
            "an absent resultBytes must not become a ZERO cap on the assembled loop: "
                + "every result would truncate to nothing and still report success");

        assertTrue(rahu.cli.live.LiveAssembly.resultBytes(bare) > 0,
            "a fallback of ZERO would silently truncate every tool result to nothing "
                + "and report success - the absence-of-evidence-as-evidence-of-absence "
                + "shape, so the fallback must be positive, not merely non-null");
    }

    @Test
    @DisplayName("a nonsensical tools.resultBytes is refused at load, with the offending value")
    void invalidResultBytesIsRefusedAtLoad() throws Exception {
        // Found by running the PACKAGED BINARY, not by reading tests: `rahu run`
        // accepted `resultBytes: 0` and exited 0. ConfigLoader binds with
        // n.path("resultBytes").asInt(65536), and asInt performs no validation - so a
        // zero cap would reach the executor and truncate EVERY tool result to nothing
        // while still reporting success. The committed schema already said
        // "minimum": 1; the loader just never enforced it. A schema an operator
        // cannot be stopped by is documentation, not a constraint.
        for (String bad : new String[] {"0", "-1"}) {
            var e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(
                // One format string: applying .formatted to only the second literal
                // leaves the first %s unfilled and leaks a stray brace into the JSON.
                writeConfig(("{\"root\": \"%s\", \"enabled\": [], \"exclusions\": [],"
                    + " \"resultBytes\": %s}").formatted(root, bad))),
                "tools.resultBytes=" + bad + " must be refused, not clamped");
            assertTrue(e.getMessage().contains(bad),
                "the error must name the offending value, got: " + e.getMessage());
        }

        // maxCallsPerStep is the same shape and was bound with the same unvalidated
        // asInt. Asserting it here rather than only in the loader's javadoc, because
        // an unbacked claim in a comment is exactly how the next reader is misled.
        for (String bad : new String[] {"0", "-3"}) {
            var e = assertThrows(ConfigError.class, () -> new ConfigLoader().load(
                writeConfig(("{\"root\": \"%s\", \"enabled\": [], \"exclusions\": [],"
                    + " \"maxCallsPerStep\": %s}").formatted(root, bad))),
                "tools.maxCallsPerStep=" + bad + " must be refused, not clamped");
            assertTrue(e.getMessage().contains(bad),
                "the error must name the offending value, got: " + e.getMessage());
        }

        // And the legal edge values must still load: validation that also rejects a
        // working config is not a fix, it is a second outage.
        var one = new ConfigLoader().load(writeConfig(
            ("{\"root\": \"%s\", \"enabled\": [], \"exclusions\": [],"
                + " \"resultBytes\": 1, \"maxCallsPerStep\": 1}").formatted(root)));
        assertEquals(1, one.tools().resultBytes(), "resultBytes=1 is legal and must load");
        assertEquals(1, one.tools().maxCallsPerStep(), "maxCallsPerStep=1 is legal too");
    }
}
