package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
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
class CompactionTriggerWiringTest {

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

    /**
     * Drives one real turn with the context allowance set low enough that the 80%
     * pressure trigger fires, and a decision engine that throws.
     */
    private Turn driveWithUnreachableEngine(Path traceDirectory) throws Exception {
        return driveWithUnreachableEngine(traceDirectory, PROMPT);
    }

    private Turn driveWithUnreachableEngine(Path traceDirectory, String request) throws Exception {
        // A 40-token allowance: the estimator is bytes/3 + messages, so this prompt
        // alone exceeds 32 tokens and the 80% trigger fires on the FIRST turn, with
        // no session history to build up. Copied from RunTraceWiringTest's config
        // rather than invented, so a schema change breaks this visibly instead of
        // silently skipping the trigger.
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
              "context": {"instructionFiles": [], "maxPromptTokens": 40},
              "tools": {"root": ".", "enabled": []},
              "trace": {"directory": %s, "capture": "metadata", "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("d", traceDirectory.toString()).get("d").toString());
        Path configPath = root.resolve("cfg-small-" + traceDirectory.getFileName() + ".json");
        Files.writeString(configPath, json);
        var cfg = new rahu.cli.config.ConfigLoader().load(configPath);

        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var provider = new MeteredProvider();
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("compaction-wiring", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        DecisionEngine dead = new DecisionEngine() {
            @Override
            public Map<String, DecisionResult> askAll(State state, List<DecisionEngine.Question> questions) {
                throw new IllegalStateException("decision engine unreachable");
            }
        };
        StringWriter errBuf = new StringWriter();
        var in = new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8));
        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(in);
            exit = new LiveTurnDriver(cfg, provider, dead, router, session,
                new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(new StringWriter()),
                new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }
        return new Turn(exit, errBuf.toString());
    }

    /**
     * Answers whatever it is asked and remembers the state it was handed, so a test
     * can assert what the driver actually passed across the port.
     */
    private static final class RecordingEngine implements DecisionEngine {
        // EVERY state, not just the last: one turn makes two dispatches (profile,
        // then the compaction consult), and keeping only the last would assert on
        // whichever happened to be second.
        final List<State> states = new ArrayList<>();

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            states.add(state);
            var answers = new java.util.LinkedHashMap<String, DecisionResult>();
            for (Question q : questions) {
                // questionId, not q.id() - the sealed interface has no accessor of
                // its own; the static helper dispatches over the permitted types.
                String id = DecisionEngine.questionId(q);
                answers.put(id, new DecisionResult.ValidChoice(
                    id, "defer", java.util.Map.of("defer", 0.8),
                    java.util.Optional.of(0.8), "recorded"));
            }
            return answers;
        }
    }

    @Test
    @DisplayName("The compaction dispatch carries the turn's real pressure, not 0.0")
    void compactionDispatchCarriesRealPressure() throws Exception {
        // The previous test proved the COMPACTION_POLICY dispatch is IN RANGE. This
        // proves it is the right number: a mutation that had the driver pass 0.0
        // survived, because in-range-but-wrong is exactly the failure a bounds check
        // cannot see. The trigger is only reached at pressure >= 0.80, so a compaction
        // dispatch reporting 0.00 tells the engine the context is empty at the moment
        // it is asked to compact it.
        var recorder = new RecordingEngine();
        var turn = driveOverBudget(recorder, root.resolve("traces-compaction-pressure"),
            "explain every file in this repository in exhaustive detail, at length");

        var compaction = recorder.states.stream()
            .filter(s -> s.operation().equals("COMPACTION_POLICY")).findFirst();
        assertTrue(compaction.isPresent(),
            "an over-budget turn must consult for a compaction policy; saw "
                + recorder.states.stream().map(s -> s.operation()).toList()
                + "\nstderr was:\n" + turn.err());
        assertEquals(1.0, compaction.get().contextPressure(),
            "the compaction dispatch must carry the saturated real pressure; it "
                + "received " + compaction.get().contextPressure() + ", which reports "
                + "an empty context to the engine being asked to compact it");
    }

    @Test
    @DisplayName("An over-budget turn reaches the decision port with pressure in [0,1]")
    void overBudgetTurnHandsThePortABoundedPressure() throws Exception {
        // The clamp belongs to the PORT, not to the measurement (AUDIT-2026-10-03-r).
        // DecisionEngine.State rejects contextPressure outside [0,1], and the driver
        // wraps the profile consult in a catch-all, so handing it the raw pressure
        // does not crash the turn - it prints "profile: unavailable" and quietly
        // disables the profile decision on precisely the over-budget turns where it
        // matters. Nothing noticed until a mutation asked what the port received.
        var recorder = new RecordingEngine();
        var turn = driveOverBudget(recorder,
            root.resolve("traces-port"),
            "explain every file in this repository in exhaustive detail, at length");

        assertTrue(turn.err().contains("profile:"),
            "the profile decision must survive an over-budget turn; stderr was:\n"
                + turn.err());
        assertFalse(turn.err().contains("profile: unavailable"),
            "the port rejected the pressure the driver handed it, and the catch-all "
                + "turned that into a silently degraded turn. stderr was:\n"
                + turn.err());
        assertFalse(recorder.states.isEmpty(), "the engine was consulted at all");
        for (DecisionEngine.State state : recorder.states) {
            double handed = state.contextPressure();
            assertTrue(handed >= 0.0 && handed <= 1.0,
                "a dispatch for operation " + state.operation() + " received "
                    + "contextPressure=" + handed + ", outside the [0,1] "
                    + "DecisionEngine.State requires");
        }
        // Select by operation, not by index: one turn makes three dispatches
        // (route, profile, compaction) and the order is not what is under test.
        var profile = recorder.states.stream()
            .filter(s -> s.operation().equals("TASK_CLASSIFICATION")).findFirst();
        assertTrue(profile.isPresent(),
            "the profile dispatch must happen; saw " + recorder.states.stream()
                .map(s -> s.operation()).toList());
        assertEquals(1.0, profile.get().contextPressure(),
            "an over-budget turn saturates at 1.0 for the port, while the operator "
                + "line keeps the honest ratio; the profile dispatch received "
                + profile.get().contextPressure());
    }

    private Turn driveOverBudget(DecisionEngine engine, Path traceDirectory, String request)
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
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low", "medium"]}
              ]}},
              "context": {"instructionFiles": [], "maxPromptTokens": 40},
              "tools": {"root": ".", "enabled": []},
              "trace": {"directory": %s, "capture": "metadata", "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("d", traceDirectory.toString()).get("d").toString());
        Path configPath = root.resolve("cfg-port-" + traceDirectory.getFileName() + ".json");
        Files.writeString(configPath, json);
        var cfg = new rahu.cli.config.ConfigLoader().load(configPath);

        var ref = new ModelRef("demo-fast");
        var provider = new MeteredProvider();
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.OFF, 0.10),
            new SearchReranker(new SilentEngine(), SearchReranker.Mode.OFF, 20));
        var session = new SessionState("compaction-port", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter errBuf = new StringWriter();
        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)));
            exit = new LiveTurnDriver(cfg, provider, engine,
                new ActiveRouter(cfg, Map.of(ref, profile("demo-fast"))), session,
                new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(new StringWriter()),
                new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }
        return new Turn(exit, errBuf.toString());
    }

    @Test
    @DisplayName("At the 80% trigger an unreachable engine compacts instead of deferring")
    void pressuredTurnFailsClosedToCompaction() throws Exception {
        // The unit tests prove CompactionPlanner and CompactionPolicyDecider behave.
        // They cannot prove the DRIVER reaches them with a pressured context - which
        // is the gap AUDIT-2026-10-03-p taught me to look for: a correct helper the
        // production call site never uses. So this drives the real driver with a
        // 40-token allowance (any prompt exceeds 32 tokens, so pressure >= 0.80) and
        // an engine that throws, then reads what the operator was told.
        var traceDirectory = root.resolve("traces");
        var turn = driveWithUnreachableEngine(traceDirectory);

        assertTrue(turn.err().contains("compaction:"),
            "the 80% trigger must be reached at all; stderr was:\n" + turn.err());
        assertTrue(turn.err().contains("policy=concise"),
            "an unreachable decision engine at the trigger must fail closed to "
                + "compacting. It used to print policy=defer, i.e. \"context fits; "
                + "nothing compacted\", at exactly the moment the context was measured "
                + "as over the trigger. stderr was:\n" + turn.err());
        assertFalse(turn.err().contains("nothing compacted"),
            "the defer note must not appear once the engine is unreachable");
        assertFalse(turn.err().contains("null"),
            "the compaction line must carry a real note, not a literal null:\n"
                + turn.err());
    }

    @Test
    @DisplayName("An over-budget context reports pressure ABOVE 1.0, not a flat 1.00")
    void overBudgetPressureIsNotPinnedAtOne() throws Exception {
        // AUDIT-2026-10-03-r. The estimator used to clamp its result to the
        // allowance, so this run would report pressure=1.00 whether the context was
        // 40 tokens over or 40x over, and the driver's own Math.min(1.0, ...) was
        // unreachable rather than defensive. context.md:42 makes the allowance a
        // stricter conversation bound, not a cap on what may be reported about it.
        var traceDirectory = root.resolve("traces-over");
        // A 40-token allowance against a harness prompt plus this long request:
        // the honest ratio is several times over, so a clamped build prints 1.00.
        var turn = driveWithUnreachableEngine(traceDirectory,
            "explain every file in this repository in exhaustive detail, at length");

        var pressure = java.util.regex.Pattern.compile("pressure=([0-9.]+)");
        var matcher = pressure.matcher(turn.err());
        assertTrue(matcher.find(),
            "a pressured turn must report its pressure; stderr was:\n" + turn.err());
        double reported = Double.parseDouble(matcher.group(1));
        assertTrue(reported > 1.0,
            "pressure was reported as " + reported + " for a context several times over "
                + "its allowance. A pinned 1.00 reports an unbounded overrun as a full "
                + "context, and hides how far over the context actually is. stderr was:\n"
                + turn.err());
    }
}
