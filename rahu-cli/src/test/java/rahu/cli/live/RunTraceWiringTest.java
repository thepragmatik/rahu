package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.trace.TraceReader;
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
 * G06: a real turn must write a trace file (observability.md; A16).
 *
 * <p>Written on 2026-10-03 after AUDIT-2026-10-03-a found G06 recorded as
 * PASSED while no {@code src/main} file constructed a {@code TraceWriter} at
 * all. The two tests behind the PASS claim ({@code TraceWriterTest},
 * {@code ReplayEngineTest}) are true about the SUBSYSTEM and were never
 * evidence about the PRODUCT.
 *
 * <p>So this file is shaped deliberately differently. Each test drives a real
 * turn through the real {@code LiveTurnDriver} and then asserts on what landed
 * on disk. That is the only test shape that can tell a wired trace from a
 * built one, because wiring is a property of the driver, not of the writer. A
 * test that constructed a {@code TraceWriter} directly would pass against
 * exactly the state the audit found wrong.
 *
 * <p>Each assertion is on a value the driver genuinely held. Counting events, or
 * only checking the file parses, would pass against a hardcoded trace and would
 * repeat the very mistake being fixed.
 *
 * <p>Wiring is proven here, not assumed. Where a claim cannot be made honestly
 * the test says so instead of asserting something weaker and calling it fixed.
 */
class RunTraceWiringTest {

    @TempDir
    Path root;

    private static final String PROMPT = "what is this project";

    /** Reports 11 prompt / 5 completion tokens so the trace has a real figure. */
    private static final class MeteredProvider implements ModelProvider {
        @Override
        public ModelOutcome generate(GenerationRequest request) {
            return new ModelOutcome.Completed("answered", List.of(),
                new Usage(11, 5, null, 500L), "stop", java.util.Optional.empty(),
                java.util.Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /** Silent, so routing cannot mask what the trace records. */
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

    /** A config whose only variable is where the trace directory points. */
    private rahu.cli.config.RahuConfig configTracingTo(Path directory) throws Exception {
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
              "trace": {"directory": %s, "capture": "metadata", "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("d", directory.toString()).get("d").toString());
        Path configPath = root.resolve("cfg-" + directory.getFileName() + ".json");
        Files.writeString(configPath, json);
        return new rahu.cli.config.ConfigLoader().load(configPath);
    }

    private record Turn(int exit, String err) { }

    /** Drives one real turn through the real driver. */
    private Turn driveOneTurn(Path traceDirectory) throws Exception {
        var cfg = configTracingTo(traceDirectory);
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var provider = new MeteredProvider();
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("trace-wiring", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter errBuf = new StringWriter();
        var in = new ByteArrayInputStream((PROMPT + "\n").getBytes(StandardCharsets.UTF_8));
        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(in);
            exit = new LiveTurnDriver(cfg, provider, new SilentEngine(), router, session,
                new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(new StringWriter()),
                new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }
        return new Turn(exit, errBuf.toString());
    }

    /**
     * The trace files this configuration produced.
     *
     * <p>Walks the whole tree rather than only the top level: a run gets its own
     * subdirectory named after the run id, so one directory per turn is the layout
     * that makes several turns coexist without clobbering each other.
     */
    private List<Path> traceFiles(Path traceDirectory) throws Exception {
        if (!Files.isDirectory(traceDirectory)) {
            return List.of();
        }
        try (var s = Files.walk(traceDirectory)) {
            return s.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                .sorted()
                .toList();
        }
    }

    /** How many trace files exist under a root; used for before/after comparison. */
    private static long countTraces(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals("events.jsonl")).count();
        }
    }

    /** The single trace this run should have written, or an explicit failure. */
    private Path theOneTrace(Path traceDirectory) throws Exception {
        assertTrue(Files.isDirectory(traceDirectory),
            "the configured trace directory was never created: " + traceDirectory);
        List<Path> files = traceFiles(traceDirectory);
        assertEquals(1, files.size(),
            "expected exactly one trace file under " + traceDirectory
                + ", found " + files.size() + ". A directory with no events.jsonl is "
                + "the exact state the audit found: the subsystem is built, never called.");
        return files.get(0);
    }

    @Test
    @DisplayName("A completed turn writes a trace file with that run's real events")
    void aCompletedTurnWritesATrace() throws Exception {
        Path dir = root.resolve("runs-a");

        Turn turn = driveOneTurn(dir);

        assertEquals(0, turn.exit(), "the turn itself should complete; stderr was:\n"
            + turn.err());
        Path trace = theOneTrace(dir);

        var reader = TraceReader.read(trace);
        assertEquals(TraceReader.Completeness.COMPLETE, reader.completeness(),
            "the trace was truncated or corrupt: " + reader.corruptionReason());

        List<String> types = reader.events().stream().map(e -> e.type()).toList();
        assertTrue(types.contains("RunStarted"), "expected RunStarted in " + types);
        assertTrue(types.contains("RouteResolved"), "expected RouteResolved in " + types);
        assertTrue(types.contains("ModelCompleted"), "expected ModelCompleted in " + types);
        assertTrue(types.contains("RunTerminated"), "expected RunTerminated in " + types);
        assertEquals(types.indexOf("RunStarted"), 0, "RunStarted must open the trace");
        assertEquals("RunTerminated", types.get(types.size() - 1),
            "RunTerminated must close the trace; got " + types);
    }

    @Test
    @DisplayName("The trace records the run's real values, not placeholders")
    void traceCarriesRealValuesNotPlaceholders() throws Exception {
        Path dir = root.resolve("runs-b");
        driveOneTurn(dir);

        var events = TraceReader.read(theOneTrace(dir)).events();
        var started = events.stream().filter(e -> e.type().equals("RunStarted"))
            .findFirst().orElseThrow();
        var completed = events.stream().filter(e -> e.type().equals("ModelCompleted"))
            .findFirst().orElseThrow();

        // The four traces on disk before this fix were all demo-shaped and all
        // carried this literal. A wired trace cannot produce it.
        assertFalse(started.payloadJson().contains("synthetic"),
            "RunStarted still carries a placeholder hash: " + started.payloadJson());
        assertFalse(completed.payloadJson().contains("synthetic"),
            "ModelCompleted still carries a placeholder payload: " + completed.payloadJson());

        // The provider really reported 11 prompt tokens. If the trace cannot carry
        // that figure, the trace is not observing this run.
        assertTrue(completed.payloadJson().contains("11"),
            "ModelCompleted did not record the real prompt token count: "
                + completed.payloadJson());
    }

    @Test
    @DisplayName("The trace goes where the config says, not to a hardcoded path")
    void traceHonoursTheConfiguredDirectory() throws Exception {
        Path elsewhere = root.resolve("not-the-default");
        long defaultTracesBefore = countTraces(Path.of(".rahu"));

        driveOneTurn(elsewhere);

        // Reaching theOneTrace is the assertion: it fails unless the file is under the
        // configured directory. A hardcoded .rahu/runs would fail it.
        theOneTrace(elsewhere);
        // And the default must not have gained a file from THIS test. Asserting that
        // .rahu/runs does not exist would be wrong: a developer checkout legitimately
        // has traces there from earlier demo runs, so absence is not the claim. What
        // must hold is that this turn did not write there.
        long defaultTracesNow = countTraces(Path.of(".rahu"));
        assertEquals(defaultTracesBefore, defaultTracesNow,
            "this turn wrote into the hardcoded default .rahu tree despite the config "
                + "naming a different directory; trace.directory is inert");
    }

    @Test
    @DisplayName("Each turn writes its own run, and the runId is not shared")
    void twoTurnsProduceTwoTraces() throws Exception {
        Path dir = root.resolve("runs-c");

        // Two separate drivers, two separate processes' worth of state.
        var firstRunIds = runIdsFor(dir, "first");
        var secondRunIds = runIdsFor(dir, "second");

        assertFalse(firstRunIds.isEmpty(), "first turn wrote no events");
        assertFalse(secondRunIds.isEmpty(), "second turn wrote no events");
        for (var id : firstRunIds) {
            assertFalse(secondRunIds.contains(id),
                "run id " + id + " appeared in both turns; a run must be distinct");
        }
    }

    /**
     * Drives a turn and returns the runIds from ONLY the files that turn wrote.
     *
     * <p>Re-reading the whole directory would make the second turn appear to reuse
     * the first turn's run id, because the first turn's file is still on disk. Taking
     * the before/after file set difference is what makes the assertion about THIS
     * turn rather than about accumulated state.
     */
    private List<String> runIdsFor(Path dir, String label) throws Exception {
        var before = new java.util.LinkedHashSet<>(traceFiles(dir));
        driveOneTurn(dir);
        List<String> ids = new java.util.ArrayList<>();
        var fresh = new java.util.ArrayList<Path>();
        for (Path p : traceFiles(dir)) {
            if (!before.contains(p)) {
                fresh.add(p);
            }
        }
        assertFalse(fresh.isEmpty(),
            label + " turn wrote no NEW trace file; before=" + before.size()
                + " after=" + traceFiles(dir).size());
        for (Path p : fresh) {
            for (var e : TraceReader.read(p).events()) {
                ids.add(e.runId());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("A refused turn is traced as a termination, not silently dropped")
    void aRefusedTurnStillLeavesATrace() throws Exception {
        // The privacy gate refuses protected content BEFORE anything is sent
        // (PrivacyGate.admitForDecision, PrivacyScanner detectors). That turn does
        // no work, so a trace written only on success would leave the refusal with
        // no evidence at all. An email address is the first detector, so this
        // exercises the real gate rather than a fixture I invented.
        Path dir = root.resolve("runs-d");
        var cfg = configTracingTo(dir);
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary,
            new MeteredProvider(), new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("trace-refusal", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter errBuf = new StringWriter();
        var in = new ByteArrayInputStream(
            ("email me at someone@example.com\n").getBytes(StandardCharsets.UTF_8));
        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(in);
            exit = new LiveTurnDriver(cfg, new MeteredProvider(), new SilentEngine(), router,
                session, new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(new StringWriter()),
                new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }

        // AUDIT-2026-10-03-d: this asserted 4, matching the live driver, and both
        // were wrong. cli.md:42 puts privacy blocked in the same class as
        // no-feasible-route/limit. Asserting against the sibling's behaviour rather
        // than against the spec is how two consistent bugs pass review.
        assertEquals(rahu.cli.live.ExitCode.PRIVACY_BLOCKED, exit,
            "the privacy gate should refuse; stderr was:\n"
            + errBuf.toString());
        // Whatever the design decides here, a refusal that leaves no record cannot
        // be distinguished from a turn that never happened. Asserted as a real
        // requirement, not as a formality.
        theOneTrace(dir);
    }
}
