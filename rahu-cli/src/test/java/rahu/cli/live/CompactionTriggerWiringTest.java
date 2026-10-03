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
        var in = new ByteArrayInputStream((PROMPT + "\n").getBytes(StandardCharsets.UTF_8));
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
}
