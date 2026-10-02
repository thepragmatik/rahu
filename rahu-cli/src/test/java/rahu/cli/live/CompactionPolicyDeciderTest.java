package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.context.CompactionPlanner;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;
import rahu.systemone.DecisionEngine;

/**
 * The live consult: System One answers COMPACTION_POLICY, the planner applies
 * it with the deterministic fit override, and every failure keeps all context
 * (a consultation that never happened defers; a failed answer fails closed).
 */
class CompactionPolicyDeciderTest {

    private static List<ChatMessage> history(int turns) {
        var out = new java.util.ArrayList<ChatMessage>();
        for (int i = 0; i < turns; i++) {
            out.add(ChatMessage.user("turn " + i + " question about module " + i));
            out.add(ChatMessage.assistant("turn " + i + " answer with fact " + i));
        }
        return List.copyOf(out);
    }

    /** Fake engine: canned answer for "compaction", records what it was asked. */
    private static class FakeEngine implements DecisionEngine {
        final DecisionResult canned;
        State lastState;
        List<Question> lastQuestions;

        FakeEngine(DecisionResult canned) {
            this.canned = canned;
        }

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            lastState = state;
            lastQuestions = new ArrayList<>(questions);
            Map<String, DecisionResult> answers = new LinkedHashMap<>();
            answers.put("compaction", canned);
            return answers;
        }
    }

    private static DecisionResult.ValidChoice choice(String label, double confidence) {
        Map<String, Double> distribution = new LinkedHashMap<>();
        for (String other : List.of("defer", "concise", "detailed")) {
            distribution.put(other, other.equals(label) ? confidence : 0.05);
        }
        return new DecisionResult.ValidChoice("compaction", label, distribution,
            Optional.of(confidence), "concentration");
    }

    @Test
    @DisplayName("A valid 'detailed' answer reaches the planner and selects DETAILED")
    void detailedAnswerHonoured() {
        var engine = new FakeEngine(choice("detailed", 0.7));
        var consult = new CompactionPolicyDecider(engine).consult(history(10), 8000);

        assertEquals(CompactionPlanner.Policy.DETAILED, consult.policy());
        assertTrue(consult.answered());
        assertEquals("COMPACTION_POLICY", engine.lastState.operation());
        assertEquals("compaction",
            DecisionEngine.questionId(engine.lastQuestions.get(0)));
    }

    @Test
    @DisplayName("A failed answer defaults concise even when the fit check would pass")
    void failedAnswerFailsClosed() {
        var engine = new FakeEngine(
            new DecisionResult.Failure(DecisionResult.FailureKind.TIMEOUT, "down"));
        var consult = new CompactionPolicyDecider(engine).consult(history(10), 1_000_000);

        assertEquals(CompactionPlanner.Policy.CONCISE, consult.policy());
        assertTrue(consult.answered(), "the engine answered; the answer was a failure");
    }

    @Test
    @DisplayName("A transport failure means no consultation: deterministic override only")
    void transportFailureDelegatesToDeterministic() {
        var engine = new FakeEngine(choice("defer", 0.9)) {
            @Override
            public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
                throw new IllegalStateException("consultation unreachable");
            }
        };
        var consult = new CompactionPolicyDecider(engine).consult(history(10), 8000);

        assertEquals(CompactionPlanner.Policy.DEFER, consult.policy(),
            "without a consultation nothing is compacted");
        assertFalse(consult.answered());
    }

    @Test
    @DisplayName("An explicit defer with a failing fit check is overridden to CONCISE")
    void deterministicOverrideFlowsThrough() {
        var engine = new FakeEngine(choice("defer", 0.9));
        var consult = new CompactionPolicyDecider(engine).consult(history(30), 200);

        assertEquals(CompactionPlanner.Policy.CONCISE, consult.policy(),
            "the fit check wins over an honoured defer");
        assertTrue(consult.answered());
    }

    @Test
    @DisplayName("A pending request that alone busts the allowance forces CONCISE (live regression)")
    void pendingRequestCountsTowardTheFitCheck() {
        // Live probe regression: history alone fits, but history + the pending
        // request does not. The consult sees the candidate next-request list,
        // so defer must be overridden even on the first turn.
        var engine = new FakeEngine(choice("defer", 0.9));
        // 800 chars + 8 framing bytes ≈ 270 estimated tokens > 220 allowance.
        var candidate = List.of(ChatMessage.user("x".repeat(800)));

        var consult = new CompactionPolicyDecider(engine).consult(candidate, 220);

        assertEquals(CompactionPlanner.Policy.CONCISE, consult.policy(),
            "the next request cannot fit; defer must not be honoured");
    }
}
