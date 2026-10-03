package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("A transport failure fails closed to concise, like any other failure")
    void transportFailureFailsClosedToConcise() {
        var engine = new FakeEngine(choice("defer", 0.9)) {
            @Override
            public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
                throw new IllegalStateException("consultation unreachable");
            }
        };
        var consult = new CompactionPolicyDecider(engine).consult(history(10), 8000);

        // CORRECTED (AUDIT-2026-10-03-q). This asserted DEFER, with the message
        // "without a consultation nothing is compacted". That is the defect, not the
        // contract. Two reasons, in order of authority:
        //
        // 1. systemone.md:40 - "Compaction-policy FAILURE defaults to concise if
        //    compaction is feasible/required". A transport failure is a failure.
        //    There is no clause carving out "unreachable" as a distinct outcome.
        // 2. The sibling test above already asserts CONCISE for a failed ANSWER. Two
        //    tests in one class disagreed about the same rule, which is only possible
        //    when one of them was pinned to the implementation rather than the spec.
        //
        // The old behaviour was also self-defeating: this consult only happens at the
        // 80% pressure trigger, so a defer here printed "context fits; nothing
        // compacted" at exactly the moment the caller had measured the context as
        // over the trigger. The deterministic fit check could not rescue it either -
        // that check promotes DEFER to CONCISE only when the next request CANNOT fit,
        // and 8000 tokens of history fits comfortably.
        assertEquals(CompactionPlanner.Policy.CONCISE, consult.policy(),
            "an unreachable engine is a failed compaction-policy judgement, so it "
                + "defaults to concise; deferring here would leave a pressured "
                + "context uncompacted for exactly the reason the trigger fired");
        assertFalse(consult.answered(),
            "still reported as unanswered, so the trace records the failure rather "
                + "than claiming System One chose this");
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

    @Test
    @DisplayName("The DEFER note claims the context fits, and it now always does")
    void deferNoteIsAccurate() {
        // The note is printed to the operator at the 80% trigger, and "defer: context
        // fits; nothing compacted" is a CLAIM about the context. This increment made
        // it true: DEFER is now reachable only from an explicit label that passed the
        // deterministic fit check, instead of also being the fallback for an
        // unreachable engine. Asserting the text pins that relationship - a mutation
        // re-wording or deleting the note is invisible otherwise, and a note that
        // misreports the fit is how the old defect reached an operator as a
        // confident sentence.
        var engine = new FakeEngine(choice("defer", 0.9));
        var consult = new CompactionPolicyDecider(engine).consult(history(10), 8000);

        assertEquals(CompactionPlanner.Policy.DEFER, consult.policy());
        assertEquals("defer: context fits; nothing compacted", consult.safeNote(),
            "an honoured defer must say plainly that nothing was compacted");
    }

    @Test
    @DisplayName("Every policy carries a distinct, non-empty operator note")
    void everyPolicyHasItsOwnNote() {
        // Closing the delete-the-note survivor. Deleting the DEFER arm compiles -
        // the switch is exhaustive over an enum - and the decider still runs, still
        // compacts correctly, and prints "compaction: policy=defer pressure=0.85 -
        // null". The behaviour was fixed; the explanation vanished, and nothing else
        // in the suite could see it. Asserting the KEY SET rather than one string is
        // what makes the removal impossible to reintroduce silently.
        var notes = new java.util.LinkedHashMap<String, String>();
        for (String label : new String[] {"defer", "concise", "detailed"}) {
            var consult = new CompactionPolicyDecider(new FakeEngine(choice(label, 0.9)))
                .consult(history(10), 8000);
            assertNotNull(consult.safeNote(),
                "policy " + label + " must carry a note; null means the operator "
                    + "sees a literal null where the explanation should be");
            assertFalse(consult.safeNote().isBlank(),
                "policy " + label + " must carry a non-blank note");
            notes.put(label, consult.safeNote());
        }
        assertEquals(3, notes.size(), "each policy must say something different");
        assertEquals(3, new java.util.HashSet<>(notes.values()).size(),
            "the three notes must be distinct, otherwise two policies are "
                + "indistinguishable to an operator reading the log: " + notes);
        assertTrue(notes.get("concise").contains("512")
                && notes.get("detailed").contains("1024"),
            "the notes must carry the spec's output caps (context.md:36), because "
                + "'concise' alone does not tell an operator what it costs: " + notes);
    }
}
