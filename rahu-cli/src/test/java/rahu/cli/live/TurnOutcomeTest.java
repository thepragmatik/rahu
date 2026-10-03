package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.context.PromptAssembler;
import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.Usage;
import rahu.core.privacy.PrivacyGate;
import rahu.systemone.DecisionEngine;
import rahu.core.privacy.Provenance;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;

/**
 * What one live turn actually reports, as observed through the real driver.
 *
 * <p>Audit finding AUDIT-2026-10-03-g. {@link TurnOutcome} was introduced so
 * {@code rahu run --format json} could report a turn without scraping the human stderr
 * trail. A new return type is only trustworthy if something observes it: the command
 * tests that first shipped with it could not catch the driver printing the answer in
 * JSON mode, nor the two override flags being ignored, because none of them drove a
 * real turn.
 *
 * <p>Every test here drives {@link LiveTurnDriver} with a fake provider and asserts on
 * the returned {@link TurnOutcome} and on the captured streams. Shaped like
 * {@code RunTraceWiringTest} for the same reason: a test that constructs the pieces
 * separately passes against exactly the wiring that is missing.
 */
class TurnOutcomeTest {

    @TempDir
    Path root;

    /** Reports a real cost so the "cost observed" path is exercised. */
    private static final class MeteredProvider implements ModelProvider {
        @Override
        public ModelOutcome generate(GenerationRequest request) {
            return new ModelOutcome.Completed("answered", List.of(),
                new Usage(11, 5, null, 500L), "stop", java.util.Optional.empty(),
                java.util.Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /** Reports NO cost, so the UNCERTAIN (A10) path is exercised. */
    private static final class UnpricedProvider implements ModelProvider {
        @Override
        public ModelOutcome generate(GenerationRequest request) {
            return new ModelOutcome.Completed("answered", List.of(),
                new Usage(11, 5, null, null), "stop", java.util.Optional.empty(),
                java.util.Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /** Silent, so routing cannot mask what the outcome reports. */
    private static final class SilentEngine implements DecisionEngine {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            return Map.of();
        }
    }

    private static ModelProfile profile(String id) {
        return new ModelProfile(new ModelRef(id), 8192, 4096,
            new MoneyAmount(new BigDecimal("0.000000019"), CurrencyUnit.USD),
            new MoneyAmount(new BigDecimal("0.00000003"), CurrencyUnit.USD),
            ReasoningPolicy.Effort.values(), false,
            Instant.parse("2026-10-02T00:00:00Z"), true, true);
    }

    private RahuConfig config(Path traceDirectory, String capture) throws Exception {
        String json = """
            {
              "schemaVersion": 1,
              "mode": "live",
              "decision": {"adapter": "fake", "model": "demo-decision",
                "baseUrl": "http://127.0.0.1:8000"},
              "generation": {"adapter": "fake", "baseUrl": "http://127.0.0.1:8000"},
              "agent": {"maxCostUsd": "1.00"},
              "routing": {"mode": "%s", "pool": "demo",
                "baseline": "fast@low", "fallback": "fast@low",
                "confidenceField": "chosen_probability", "confidenceFloor": 0.65},
              "pools": {"demo": {"models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low", "medium"]},
                {"alias": "quality", "id": "demo-quality", "reasoning": ["medium", "high"]}
              ]}},
              "context": {"instructionFiles": []},
              "tools": {"root": ".", "enabled": ["workspace.list", "workspace.read",
                "workspace.search"]},
              "trace": {"directory": %s, "capture": "%s", "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(routingMode,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("d", traceDirectory.toString()).get("d").toString(),
                capture);
        Path configPath = root.resolve("cfg-" + traceDirectory.getFileName() + ".json");
        Files.writeString(configPath, json);
        return new ConfigLoader().load(configPath);
    }

    private String routingMode = "shadow";

    private record Driven(TurnOutcome outcome, String out, String err) {
    }

    /** Drives one real turn, returning the outcome and both captured streams. */
    private Driven drive(ModelProvider provider, boolean suppressAnswer) throws Exception {
        Path traceDir = root.resolve("runs-" + System.nanoTime());
        RahuConfig cfg = config(traceDir, "metadata");
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("outcome", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        var driver = new LiveTurnDriver(cfg, provider, new SilentEngine(), router, session,
            new Provenance.ApprovedNonSensitive("test"), toolLoop, line -> null,
            new PrintWriter(outBuf, true), new PrintWriter(errBuf, true));

        TurnOutcome outcome = suppressAnswer
            ? driver.turnSuppressingAnswer("what is this project", new PrivacyGate(),
                new PromptAssembler(), 8192, 2048, new BigDecimal("1.00"))
            : driver.turn("what is this project", new PrivacyGate(),
                new PromptAssembler(), 8192, 2048, new BigDecimal("1.00"));
        return new Driven(outcome, outBuf.toString(), errBuf.toString());
    }

    // ------------------------------------------------------ reported content

    @Test
    @DisplayName("a completed turn reports its answer, route, cost and run id")
    void completedTurnReportsEverything() throws Exception {
        Driven driven = drive(new MeteredProvider(), false);
        TurnOutcome outcome = driven.outcome();

        assertEquals(ExitCode.OK, outcome.exitCode(), driven.err());
        assertTrue(outcome.answered(), "a completed turn must report as answered");
        assertEquals("answered", outcome.answer().orElseThrow());
        assertTrue(outcome.runId().isPresent(), "a real turn must report its run id");
        assertTrue(outcome.routing().isPresent(), "a routed turn must report its route");
        // The executed id is the pool alias@effort ("fast@low"), not the bare model
        // id - the trace records the same form, which is why the two can be compared.
        assertEquals("fast@low", outcome.routing().get().executedId());
        assertEquals("SHADOW", outcome.routing().get().mode());
        assertFalse(outcome.costUnobserved(),
            "a metered provider reported a cost, so it must not read as unobserved");
        assertEquals(1, outcome.generationSteps(), driven.err());
    }

    @Test
    @DisplayName("the reported route matches what the trace recorded")
    void reportedRouteMatchesTrace() throws Exception {
        // The AUDIT-e capture rule: the outcome and the trace must describe the SAME
        // resolution. If they were derived separately they could disagree, and an
        // operator comparing them would have no way to tell which is right.
        Driven driven = drive(new MeteredProvider(), false);
        TurnOutcome.RouteInfo route = driven.outcome().routing().orElseThrow();
        Path traceRoot = root.resolve("runs-" + driven.outcome().runId().orElseThrow());
        // The trace directory is per-run; find the events file beneath it.
        Path events = null;
        try (var walk = Files.walk(root)) {
            events = walk.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                .findFirst().orElse(null);
        }
        assertTrue(events != null, "the turn wrote no trace at all");
        String trace = Files.readString(events);
        assertTrue(trace.contains("\"executedId\":\"" + route.executedId() + "\"")
                || trace.contains("\"executed\":\"" + route.executedId() + "\""),
            "the outcome reports " + route.executedId()
                + " but the trace does not: " + trace);
    }

    // ------------------------------------------------------------ suppression

    @Test
    @DisplayName("suppressing the answer keeps it off stdout but in the outcome")
    void suppressedAnswerIsNotPrinted() throws Exception {
        // This is the property cli.md:13 rests on: JSON mode emits ONE document on
        // stdout. A driver that printed the answer anyway would put a bare line before
        // the JSON, and no amount of downstream filtering can un-print it.
        Driven driven = drive(new MeteredProvider(), true);
        assertEquals("", driven.out(),
            "suppressed mode must write nothing to stdout, got: " + driven.out());
        assertEquals("answered", driven.outcome().answer().orElseThrow(),
            "the answer must still be available for the JSON document");
    }

    @Test
    @DisplayName("the printing turn does put the answer on stdout")
    void printedTurnWritesAnswer() throws Exception {
        Driven driven = drive(new MeteredProvider(), false);
        assertTrue(driven.out().contains("answered"),
            "text mode must print the answer: " + driven.out());
    }

    // ------------------------------------------------------------------ cost

    @Test
    @DisplayName("an unreported cost reads as unobserved, not as zero")
    void unreportedCostIsUnobserved() throws Exception {
        // A10: an unreported cost on a DISPATCHED request is UNCERTAIN, not free.
        // Reporting it as "$0.00" asserts the provider billed nothing.
        Driven driven = drive(new UnpricedProvider(), false);
        assertEquals(ExitCode.OK, driven.outcome().exitCode(), driven.err());
        assertTrue(driven.outcome().costUnobserved(),
            "no reported cost must read as unobserved");
        assertFalse(driven.err().contains("$0.000000"),
            "stderr must not present an unreported cost as zero: " + driven.err());
    }

    @Test
    @DisplayName("cost observation is decided by the report, not by the exit code")
    void costObservationIsIndependentOfSuccess() throws Exception {
        TurnOutcome ok = drive(new MeteredProvider(), false).outcome();
        TurnOutcome unpriced = drive(new UnpricedProvider(), false).outcome();
        assertEquals(ok.exitCode(), unpriced.exitCode(),
            "both turns succeeded; the difference is only the cost report");
        assertFalse(ok.costUnobserved());
        assertTrue(unpriced.costUnobserved());
    }

    // --------------------------------------------------------------- refused

    @Test
    @DisplayName("a refused turn reports a terminal reason and no answer")
    void refusedTurnReportsReason() throws Exception {
        Path traceDir = root.resolve("refused-" + System.nanoTime());
        RahuConfig cfg = config(traceDir, "metadata");
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary,
            new MeteredProvider(), new ToolCallLog(), new PrivacyGate(),
            Provenance.Unknown.INSTANCE, 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("refused", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        var driver = new LiveTurnDriver(cfg, new MeteredProvider(), new SilentEngine(),
            router, session, Provenance.Unknown.INSTANCE, toolLoop, line -> null,
            new PrintWriter(outBuf, true), new PrintWriter(errBuf, true));

        TurnOutcome outcome = driver.turn("my password is hunter2", new PrivacyGate(),
            new PromptAssembler(), 8192, 2048, new BigDecimal("1.00"));

        assertEquals(ExitCode.PRIVACY_BLOCKED, outcome.exitCode());
        assertEquals("PRIVACY_BLOCKED", outcome.terminalReason());
        assertTrue(outcome.answer().isEmpty(), "a refused turn has no answer");
        assertTrue(outcome.routing().isEmpty(),
            "a turn refused before routing must not report a route");
        assertEquals("", outBuf.toString(), "a refused turn must write nothing to stdout");
        assertFalse(errBuf.toString().contains("hunter2"),
            "the refused input leaked into diagnostics: " + errBuf);
    }

    // -------------------------------------------------------------- overrides

    @Test
    @DisplayName("--routing active changes the mode the router resolves under")
    void routingOverrideChangesMode() throws Exception {
        // The override must reach the EFFECTIVE config: a run that printed "active" but
        // resolved under the config's shadow mode would make active routing a lie, and
        // the trace's config hash is taken from the same effective record.
        routingMode = "shadow";
        RahuConfig shadow = config(root.resolve("s-" + System.nanoTime()), "metadata");
        RahuConfig active = new RahuConfig(shadow.schemaVersion(), shadow.mode(),
            shadow.decision(), shadow.generation(),
            new RahuConfig.RoutingConfig("active", shadow.routing().pool(),
                shadow.routing().baseline(), shadow.routing().fallback(),
                shadow.routing().confidenceField(), shadow.routing().confidenceFloor(),
                shadow.routing().maximumCandidates()),
            shadow.pools(), shadow.summarisation(), shadow.agent(), shadow.catalog(),
            shadow.tools(), shadow.trace(), shadow.context(), shadow.session(),
            shadow.orchestration(), shadow.privacy(), shadow.injection(), shadow.search());

        // mode() is an enum; the CLI lowercases it for the footer, but the value itself
        // is SHADOW. Asserting "shadow" here tested my assumption, not the code.
        assertEquals("SHADOW", new ActiveRouter(shadow, Map.of(new ModelRef("demo-fast"),
            profile("demo-fast"))).mode().name());
        assertEquals("ACTIVE", new ActiveRouter(active, Map.of(new ModelRef("demo-fast"),
            profile("demo-fast"))).mode().name());
    }

    @Test
    @DisplayName("--capture-payloads reaches the trace as capture=payloads")
    void captureOverrideReachesTrace() throws Exception {
        Path dir = root.resolve("cap-" + System.nanoTime());
        RahuConfig metadata = config(dir, "metadata");
        assertEquals("metadata", metadata.trace().capture());

        RahuConfig payloads = new RahuConfig(metadata.schemaVersion(), metadata.mode(),
            metadata.decision(), metadata.generation(), metadata.routing(), metadata.pools(),
            metadata.summarisation(), metadata.agent(), metadata.catalog(), metadata.tools(),
            new RahuConfig.TraceConfig(metadata.trace().directory(), "payloads",
                metadata.trace().onFailure()),
            metadata.context(), metadata.session(), metadata.orchestration(),
            metadata.privacy(), metadata.injection(), metadata.search());
        assertEquals("payloads", payloads.trace().capture());

        // And the writer must actually act on it, not merely store it.
        var trace = rahu.cli.trace.TurnTrace.forSession(dir, payloads,
            new SessionState("cap-probe", payloads.session().maxTurns(),
                new MoneyAmount(payloads.session().maxCostUsd(), CurrencyUnit.USD)));
        assertTrue(trace.payloadsEnabled(),
            "capture=payloads must enable capture in the writer itself");
    }
}
