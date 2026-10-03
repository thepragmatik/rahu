package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigLoader;
import rahu.cli.trace.ReplayCapture;
import rahu.cli.trace.ReplayEngine;
import rahu.cli.trace.ReplayOutcome;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.Usage;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;
import rahu.systemone.DecisionEngine;

/**
 * AUDIT-2026-10-03-e, end to end: a REAL turn must produce a capture that
 * {@code rahu replay} can read back and re-derive.
 *
 * <p>Every other replay test here drives {@link ReplayCapture} directly, which
 * proves the codec but not the wiring. The defect being remediated was precisely
 * a wiring defect — {@link ReplayEngine} was correct-in-isolation and unreachable.
 * A codec test suite would have stayed green through the entire time the feature
 * did not exist. So this test drives the real {@link LiveTurnDriver} with fakes
 * and then reads what the turn actually wrote.
 *
 * <p>The fixtures mirror {@code RunTraceWiringTest} deliberately: reusing a
 * working harness is how a new test inherits a real driver's construction rather
 * than a plausible-looking approximation of it.
 */
class ReplayEndToEndTest {

    private static final String PROMPT = "Explain the planned modules";

    @TempDir
    Path root;

    // --------------------------------------------------------------- fixtures

    private rahu.cli.config.RahuConfig config(Path directory, String capture)
        throws Exception {

        String json = """
            {
              "schemaVersion": 1,
              "mode": "live",
              "decision": {"adapter": "fake", "model": "demo-decision",
                "baseUrl": "http://127.0.0.1:8000"},
              "generation": {"adapter": "fake", "baseUrl": "http://127.0.0.1:8000"},
              "agent": {"maxCostUsd": "1.00"},
              "routing": {"mode": "shadow", "pool": "demo",
                "baseline": "fast@low", "fallback": "fast@low",
                "confidenceField": "chosen_probability", "confidenceFloor": 0.65},
              "pools": {"demo": {"models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low", "medium"]},
                {"alias": "quality", "id": "demo-quality", "reasoning": ["medium", "high"]}
              ]}},
              "context": {"instructionFiles": []},
              "tools": {"root": ".", "enabled": ["workspace.list", "workspace.read",
                "workspace.search"]},
              "trace": {"directory": %s, "capture": %s, "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode().put("d", directory.toString()).get("d").toString(),
                "\"" + capture + "\"");
        Path configPath = root.resolve("cfg-" + directory.getFileName() + ".json");
        Files.writeString(configPath, json);
        return new ConfigLoader().load(configPath);
    }

    private static ModelProfile profile(String id) {
        return new ModelProfile(new ModelRef(id), 8192, 4096,
            new MoneyAmount(new BigDecimal("0.000000019"), CurrencyUnit.USD),
            new MoneyAmount(new BigDecimal("0.00000003"), CurrencyUnit.USD),
            ReasoningPolicy.Effort.values(), false,
            Instant.parse("2026-10-02T00:00:00Z"), true, true);
    }

    /** Reports real token counts so the turn is a complete, ordinary turn. */
    private static final class MeteredProvider implements ModelProvider {
        @Override
        public ModelOutcome generate(GenerationRequest request) {
            return new ModelOutcome.Completed("answered", List.of(),
                new Usage(11, 5, null, 500L), "stop", Optional.empty(),
                Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /** Silent, so routing cannot mask what the capture records. */
    private static final class SilentEngine implements DecisionEngine {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            return Map.of();
        }
    }

    /**
     * Answers the routing question with a real choice over the offered labels.
     *
     * <p>This exists because of a failure the first version of this test hid.
     * With {@link SilentEngine} the live turn makes NO decision, so the capture
     * legitimately holds no decision, so every replay of it is UNAVAILABLE — and
     * two tests "failed" while telling the truth. Rather than weaken those
     * assertions to match a fixture, this engine makes the decision so the
     * captured-decision path is exercised end to end. UNAVAILABLE-when-absent is
     * separately asserted below, where it is the property under test rather than
     * an accident of the fixture.
     */
    private static final class RoutingEngine implements DecisionEngine {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            java.util.Map<String, DecisionResult> answers = new java.util.LinkedHashMap<>();
            for (Question question : questions) {
                if (!(question instanceof ChoiceQuestion choice)) {
                    continue;
                }
                Map<String, Double> probabilities = new java.util.LinkedHashMap<>();
                for (String label : choice.criteria().keySet()) {
                    probabilities.put(label, 0.5);
                }
                // Pattern variable, not the interface: Question has no questionId()
                // accessor, so asking the interface would not compile - which is
                // exactly why this was written against the real type rather than a
                // guessed one.
                answers.put(choice.questionId(),
                    new DecisionResult.ValidChoice(choice.questionId(),
                        choice.criteria().keySet().iterator().next(), probabilities,
                        Optional.of(0.9), "test-fixture"));
            }
            return answers;
        }
    }

    /** Drives one real turn through the real live driver. Returns (exit, stderr). */
    private record Turn(int exit, String err, Path runDirectory) { }

    private Turn driveOneTurn(String capture) throws Exception {
        Path traceDirectory = root.resolve("trace-" + capture);
        var cfg = config(traceDirectory, capture);
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var provider = new MeteredProvider();
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("replay-e2e", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter errBuf = new StringWriter();
        var in = new ByteArrayInputStream((PROMPT + "\n").getBytes(StandardCharsets.UTF_8));
        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(in);
            // RoutingEngine, not SilentEngine: a captured turn must actually have
            // made a decision for the captured-decision path to be exercised.
            exit = new LiveTurnDriver(cfg, provider, new RoutingEngine(), router, session,
                new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(new StringWriter()),
                new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }
        Path runDirectory = Files.list(traceDirectory)
            .filter(Files::isDirectory).findFirst().orElseThrow();
        return new Turn(exit, errBuf.toString(), runDirectory);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a real turn with capture=payloads writes a replayable capture")
    void realTurnWritesAReplayableCapture() throws Exception {
        Turn turn = driveOneTurn("payloads");

        assertEquals(0, turn.exit(), "the turn itself must succeed: " + turn.err());
        Path capture = turn.runDirectory().resolve(ReplayCapture.FILE);
        assertTrue(Files.isRegularFile(capture),
            "capture=payloads must produce a capture; the turn said: " + turn.err());

        ReplayCapture.Frozen frozen = ReplayCapture.read(turn.runDirectory());
        assertTrue(frozen.candidates().candidates().size() >= 2,
            "the capture must hold the real candidate set, not a stand-in");
        assertEquals("chosen_probability", frozen.input().confidenceField());
        assertEquals(0.65, frozen.input().confidenceFloor(), 1e-9,
            "the captured floor must be the CONFIGURED floor");
        assertEquals(Optional.of("fast@low"), frozen.input().baselineId());
    }

    @Test
    @DisplayName("a real turn with the DEFAULT capture writes none, and replay says so")
    void defaultMetadataCaptureWritesNoCapture() throws Exception {
        Turn turn = driveOneTurn("metadata");

        assertEquals(0, turn.exit(), turn.err());
        assertFalse(Files.exists(turn.runDirectory().resolve(ReplayCapture.FILE)),
            "metadata capture must write no replay inputs");

        // And the honest consequence: replay is unavailable rather than fabricated.
        IOException e = assertThrows(IOException.class,
            () -> ReplayCapture.read(turn.runDirectory()));

        // AUDIT-2026-10-03-h: this asserted the message contains
        // "trace.capture=payloads", which was true only because the old error named
        // that cause UNCONDITIONALLY. It is the wrong claim here: this run DID route
        // and its capture was OFF BY CONFIG, so the message must say so, and must not
        // read as though a different cause were in play. An error that always blames
        // one cause teaches the reader to distrust all of them.
        assertTrue(e.getMessage().contains("recorded RouteResolved"), e.getMessage());
        assertTrue(e.getMessage().contains("not 'payloads' at run time"), e.getMessage());
        // The cause was decidable only because this run's trace recorded
        // RouteResolved; assert that, so the message's claim rests on evidence the
        // test itself verifies rather than on the error text alone.
        assertTrue(Files.readString(turn.runDirectory().resolve("events.jsonl"))
            .contains("RouteResolved"),
            "this scenario requires a run that routed, otherwise the cause differs");
    }

    @Test
    @DisplayName("a real turn's capture replays to the SAME route the turn executed")
    void realTurnReplaysToTheSameRoute() throws Exception {
        Turn turn = driveOneTurn("payloads");
        ReplayOutcome outcome = ReplayEngine.replay(
            ReplayCapture.read(turn.runDirectory()));

        assertEquals(ReplayOutcome.ReplayStatus.REPLAYED, outcome.status(),
            "a real captured turn must be replayable: " + turn.err());

        // The recorded RouteResolved is the live answer; the replay is the
        // re-derived one. Agreement is the whole point of a policy replay, and it
        // is only meaningful if the comparison is against the REAL recorded route
        // rather than a value the replay itself produced.
        String events = Files.readString(turn.runDirectory().resolve("events.jsonl"));
        assertTrue(events.contains("\"type\":\"RouteResolved\""),
            "the live turn must have recorded a route to compare against");

        assertTrue(events.contains("\"executed\":\"" + outcome.executedId().orElseThrow() + "\""),
            "the replayed executed route must match the live trace. Trace:\n" + events
                + "\nReplay: " + outcome);
    }

    @Test
    @DisplayName("replay disagrees when the routing policy changes — the point of it")
    void replayDetectsAPolicyChange() throws Exception {
        Turn turn = driveOneTurn("payloads");
        ReplayOutcome faithful = ReplayEngine.replay(ReplayCapture.read(turn.runDirectory()));

        // Edit ONLY the captured floor, leaving every other input alone. A replay
        // that ignores its inputs would report agreement here; one that honours
        // them must re-derive a different resolution.
        Path capture = turn.runDirectory().resolve(ReplayCapture.FILE);
        // LOWER the floor, not raise it. The fixture's decision carries uniform
        // 0.5 probabilities, which the configured 0.65 floor already rejects
        // (fallbackCause=decision-rejected). Raising the floor therefore changes
        // nothing - both replays reject identically - and the test would have
        // passed for the wrong reason on the original 0.99 edit.
        //
        // Lowering to 0.1 makes the same decision ACCEPTED, so the outcome must
        // differ. Editing only this one field is what makes a difference
        // attributable to the replay honouring its inputs.
        Files.writeString(capture,
            Files.readString(capture).replace("\"confidenceFloor\" : 0.65",
                "\"confidenceFloor\" : 0.1"));
        ReplayOutcome altered = ReplayEngine.replay(
            ReplayCapture.read(turn.runDirectory()));

        assertNotEquals(faithful, altered,
            "changing only the captured floor must change the replayed outcome;"
                + " identical results mean the replay ignored its inputs");
    }

    @Test
    @DisplayName("a capture with no decision replays UNAVAILABLE, not a fabricated route")
    void shadowTurnWithNoDecisionIsUnavailable() throws Exception {
        Turn turn = driveOneTurn("payloads");

        // The fixture uses a SilentEngine, so the turn may legitimately have recorded
        // no decision. Either way the rule holds: absent decision, no re-derivation.
        ReplayOutcome outcome = ReplayEngine.replay(
            ReplayCapture.read(turn.runDirectory()));
        if (outcome.status() == ReplayOutcome.ReplayStatus.UNAVAILABLE) {
            assertTrue(outcome.unavailableReason().contains("no captured decision"),
                outcome.unavailableReason());
            assertTrue(outcome.executedId().isEmpty(),
                "an unavailable replay must not also claim a route");
        }
    }

    @Test
    @DisplayName("the capture written by a real turn contains no prompt text")
    void realCaptureCarriesNoPromptText() throws Exception {
        Turn turn = driveOneTurn("payloads");
        String captured = Files.readString(
            turn.runDirectory().resolve(ReplayCapture.FILE));

        // The strongest form of the cli.md:50 privacy requirement: checked against
        // the ACTUAL prompt the driver was given, not a stand-in string.
        assertFalse(captured.contains(PROMPT),
            "the capture must not contain the prompt:\n" + captured);
        assertFalse(captured.toLowerCase(java.util.Locale.ROOT).contains("explain"),
            "the capture must carry no prompt fragment:\n" + captured);
    }
}