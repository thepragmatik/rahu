package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;
import rahu.core.decision.TaskClass;
import rahu.core.decision.TurnProfile;
import rahu.systemone.DecisionEngine;

/** Maps one batched decision onto a TurnProfile; every failure path degrades, never widens. */
class ProfileDeciderTest {

    private static final List<String> TOOLS =
        List.of("workspace.list", "workspace.read", "workspace.search");

    /** Fake engine: canned answers, records the batch it was given. */
    private static final class FakeEngine implements DecisionEngine {
        final Map<String, DecisionResult> canned;
        State lastState;
        List<Question> lastQuestions;
        RuntimeException throwOnAskAll;

        FakeEngine(Map<String, DecisionResult> canned) {
            this.canned = canned;
        }

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            if (throwOnAskAll != null) {
                throw throwOnAskAll;
            }
            lastState = state;
            lastQuestions = new ArrayList<>(questions);
            return canned;
        }
    }

    private static Map<String, DecisionResult> answers(String taskClass, double confidence,
        Map<String, Boolean> toolRelevance) {
        Map<String, Double> distribution = new LinkedHashMap<>();
        for (String label : List.of("answer", "coding", "analysis", "classification",
            "summarisation", "unknown")) {
            distribution.put(label, label.equals(taskClass) ? confidence : 0.01);
        }
        Map<String, DecisionResult> answers = new LinkedHashMap<>();
        answers.put("taskClass", new DecisionResult.ValidChoice("taskClass", taskClass,
            distribution, Optional.of(confidence), "concentration"));
        for (Map.Entry<String, Boolean> e : toolRelevance.entrySet()) {
            answers.put("tool:" + e.getKey(),
                new DecisionResult.ValidNoul("tool:" + e.getKey(), e.getValue(),
                    Optional.of(e.getValue() ? 0.9 : 0.1)));
        }
        return answers;
    }

    @Test
    @DisplayName("The batch carries classification plus per-tool relevance in ONE askAll call")
    void batchShape() {
        var engine = new FakeEngine(answers("coding", 0.9, Map.of(
            "workspace.list", false, "workspace.read", true, "workspace.search", false)));
        var decider = new ProfileDecider(engine, TOOLS);

        decider.decide("Refactor the ledger module", 0.1);

        assertEquals("TASK_CLASSIFICATION", engine.lastState.operation());
        assertEquals(4, engine.lastQuestions.size(),
            "classification + one noul per permitted tool, one dispatch");
    }

    @Test
    @DisplayName("A confident classification and its relevant tools are carried through")
    void happyPath() {
        var engine = new FakeEngine(answers("coding", 0.9, Map.of(
            "workspace.list", false, "workspace.read", true, "workspace.search", false)));
        var decider = new ProfileDecider(engine, TOOLS);

        TurnProfile profile = decider.decide("Refactor the ledger module", 0.1);

        assertEquals(TaskClass.CODING, profile.taskClass());
        assertTrue(profile.relevantTools().contains("workspace.read"));
        assertTrue(!profile.relevantTools().contains("workspace.search"),
            "a judged-irrelevant tool is narrowed away");
        assertEquals(false, profile.degraded());
    }

    @Test
    @DisplayName("A failed batch degrades to UNKNOWN and exposes the permitted set")
    void transportFailureDegradesClosed() {
        var engine = new FakeEngine(Map.of());
        engine.throwOnAskAll = new IllegalStateException("decision transport down");
        var decider = new ProfileDecider(engine, TOOLS);

        TurnProfile profile = decider.decide("anything", 0.0);

        assertEquals(TaskClass.UNKNOWN, profile.taskClass());
        assertEquals(TOOLS.size(), profile.relevantTools().size(),
            "failed relevance exposes the permitted read-only set, never fewer");
        assertTrue(profile.degraded());
    }

    @Test
    @DisplayName("A failed relevance question keeps that tool (fail closed), not the whole batch")
    void failedRelevanceQuestionKeepsTool() {
        Map<String, DecisionResult> canned = answers("coding", 0.9, Map.of(
            "workspace.list", false, "workspace.search", false));
        canned.put("tool:workspace.read", new DecisionResult.Failure(
            DecisionResult.FailureKind.PROTOCOL_ERROR, "malformed noul"));
        var engine = new FakeEngine(canned);
        var decider = new ProfileDecider(engine, TOOLS);

        TurnProfile profile = decider.decide("Refactor the ledger module", 0.1);

        assertEquals(TaskClass.CODING, profile.taskClass());
        assertTrue(profile.relevantTools().contains("workspace.read"),
            "an unjudgeable tool stays available (fail closed), siblings narrow normally");
        assertTrue(!profile.relevantTools().contains("workspace.search"));
    }

    @Test
    @DisplayName("A missing taskClass answer degrades to UNKNOWN")
    void missingClassificationDegrades() {
        Map<String, DecisionResult> canned = answers("coding", 0.9, Map.of(
            "workspace.list", false, "workspace.read", true, "workspace.search", false));
        canned.remove("taskClass");
        var engine = new FakeEngine(canned);
        var decider = new ProfileDecider(engine, TOOLS);

        TurnProfile profile = decider.decide("anything", 0.0);

        assertEquals(TaskClass.UNKNOWN, profile.taskClass());
        assertTrue(profile.degraded());
    }

    @Test
    @DisplayName("The decision port is handed pressure inside [0,1], whatever the caller measured")
    void portReceivesBoundedPressure() {
        // AUDIT-2026-10-03-r. The estimator was unclamped so a real overrun reports
        // pressure above 1.0, which DecisionEngine.State REJECTS ("contextPressure in
        // [0,1]"). The driver's try/catch swallowed that rejection into
        // "profile: unavailable", so the mutation "hand the port the raw pressure"
        // left the suite green while silently disabling the profile decision on
        // exactly the over-budget turns where it matters most. The bound belongs to
        // the port, so it is asserted here, at the port.
        var engine = new FakeEngine(answers("coding", 0.9, Map.of()));
        new ProfileDecider(engine, TOOLS).decide("a long request", 1.0);

        assertTrue(engine.lastState != null, "the engine was consulted");
        double handed = engine.lastState.contextPressure();
        assertTrue(handed >= 0.0 && handed <= 1.0,
            "DecisionEngine.State requires contextPressure in [0,1] and throws "
                + "otherwise; it was handed " + handed + ", which the driver would "
                + "have swallowed as \"profile: unavailable\"");
    }
}
