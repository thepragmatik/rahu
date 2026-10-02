package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;
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
 * The session cost allowance must refuse a turn BEFORE the provider is called.
 *
 * <p>Regression for the finding recorded in {@code docs/plans/active/build-status.md}:
 * {@code LiveTurnDriver.account()} called {@code Ledger.tryReserve} only AFTER the provider
 * dispatch, and returned silently when the reservation was refused. The provider had
 * already been billed, so a refused reservation left the spend unrecorded instead of
 * stopping the turn. These tests drive the real driver and assert on the observable
 * consequence: whether the provider was called at all.
 */
class CostGateTest {

    @TempDir
    Path root;

    /** Counts dispatches, so a test can prove the provider was never called. */
    private static final class CountingProvider implements ModelProvider {
        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            calls.incrementAndGet();
            return new ModelOutcome.Completed("answered", List.of(),
                new Usage(100, 20, null, 500L), "stop", java.util.Optional.empty(),
                java.util.Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /** A decision engine that answers nothing, so routing cannot mask the cost gate. */
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

    private record Fixture(CountingProvider provider, StringWriter err, int exit) { }

    /** Drives one real turn through the driver with the given session allowance. */
    private Fixture drive(BigDecimal sessionAllowance) throws Exception {
        // agent.maxCostUsd is the per-run cap the driver reserves; session.maxCostUsd is
        // the ledger allowance it reserves FROM. A small allowance therefore cannot cover
        // the per-run cap, which is the exact real-world misconfiguration under test.
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
              "trace": {"directory": ".rahu/runs", "capture": "metadata",
                "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "%s"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "approved-nonsensitive"}
            }
            """.formatted(sessionAllowance.toPlainString());
        Path configPath = root.resolve("costgate-" + sessionAllowance.toPlainString() + ".json");
        Files.writeString(configPath, json);

        var cfg = new rahu.cli.config.ConfigLoader().load(configPath);
        var ref = new ModelRef("demo-fast");
        var router = new ActiveRouter(cfg, Map.of(ref, profile("demo-fast")));
        var provider = new CountingProvider();
        var boundary = new PathBoundary(root);
        var toolLoop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 4,
            new InjectionGate(new SilentEngine(), InjectionGate.Mode.SHADOW, 0.10));
        var session = new SessionState("cost-gate", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));

        StringWriter errBuf = new StringWriter();
        StringWriter outBuf = new StringWriter();
        ByteArrayInputStream in = new ByteArrayInputStream(
            "hello\n".getBytes(StandardCharsets.UTF_8));

        java.io.InputStream original = System.in;
        int exit;
        try {
            System.setIn(in);
            exit = new LiveTurnDriver(cfg, provider, new SilentEngine(), router, session,
                new Provenance.ApprovedNonSensitive("test"), toolLoop,
                line -> null, new PrintWriter(outBuf), new PrintWriter(errBuf)).run();
        } finally {
            System.setIn(original);
        }
        return new Fixture(provider, errBuf, exit);
    }

    @Test
    @DisplayName("An allowance too small for the per-run cap refuses the turn before dispatch")
    void spentAllowanceRefusesBeforeDispatch() throws Exception {
        Fixture result = drive(new BigDecimal("0.00"));

        assertEquals(0, result.provider().calls(),
            "the provider must NOT be called when the allowance cannot cover the per-run cap"
                + " — stderr was: " + result.err());
        assertEquals(3, result.exit(),
            "an exhausted allowance is a typed refusal (exit 3), like the turn limit");
        assertTrue(result.err().toString().contains("cost")
                || result.err().toString().contains("allowance"),
            "the refusal must be reported on stderr, never silent; stderr was: " + result.err());
    }

    @Test
    @DisplayName("A funded allowance still dispatches and settles normally")
    void fundedAllowanceStillRuns() throws Exception {
        Fixture result = drive(new BigDecimal("3.00"));

        assertEquals(1, result.provider().calls(),
            "a funded session must still dispatch exactly one turn; stderr was: "
                + result.err());
        assertEquals(0, result.exit());
        assertTrue(result.err().toString().contains("model="),
            "the usual turn footer must still print; stderr was: " + result.err());
    }
}