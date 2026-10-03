package rahu.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;

/**
 * System One chooses the compaction policy (context.md); a failed decision
 * defaults to CONCISE independently of the fit check, while an explicit defer
 * is honoured whenever the deterministic fit check says the next request fits.
 */
class CompactionPolicyDecisionTest {

    private static List<ChatMessage> history() {
        return List.of(ChatMessage.user("a"), ChatMessage.assistant("b"),
            ChatMessage.user("c"), ChatMessage.assistant("d"),
            ChatMessage.user("e"), ChatMessage.assistant("f"));
    }

    @Test
    @DisplayName("A valid 'detailed' choice selects the detailed policy")
    void detailedHonoured() {
        var decision = new DecisionResult.ValidChoice("compaction", "detailed",
            java.util.Map.of("defer", 0.1, "concise", 0.2, "detailed", 0.7),
            java.util.Optional.of(0.7), "concentration");
        var plan = CompactionPlanner.plan(history(), java.util.Optional.of(decision), 1000, 2);
        assertEquals(CompactionPlanner.Policy.DETAILED, plan.policy());
    }

    @Test
    @DisplayName("A failed decision defaults to concise when compaction is feasible")
    void failureDefaultsConcise() {
        var plan = CompactionPlanner.plan(history(),
            java.util.Optional.of(
                new DecisionResult.Failure(DecisionResult.FailureKind.TIMEOUT, "x")),
            100, 2);
        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy());
    }

    @Test
    @DisplayName("A failed decision defaults to concise even when the fit check would pass")
    void failureConciseIndependentOfFit() {
        // Generous allowance: the conservative fit check would NOT fire, so the
        // concise default must come from the failed decision itself.
        var plan = CompactionPlanner.plan(history(),
            java.util.Optional.of(
                new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR, "x")),
            100000, 2);
        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy(),
            "decision failure fails closed to the conservative compact policy");
    }

    @Test
    @DisplayName("An ABSENT decision (engine unreachable) defaults to concise, not defer")
    void absentDecisionDefaultsConcise() {
        // The shape a transport failure actually produces: CompactionPolicyDecider
        // catches the RuntimeException and passes Optional.empty(). At the 80%
        // trigger that meant DEFER - "context fits, nothing compacted" - which is
        // the one outcome the trigger exists to prevent, and systemone.md:40 says
        // failure defaults to CONCISE "if compaction is feasible/required". With a
        // generous allowance the deterministic fit check cannot rescue it: DEFER
        // only becomes CONCISE when the next request CANNOT fit.
        var plan = CompactionPlanner.plan(history(), java.util.Optional.empty(), 100000, 2);
        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy(),
            "an unreachable decision engine must fail closed to compacting, not to deferring");
    }

    @Test
    @DisplayName("A typo'd or future label defaults to concise, not defer")
    void unrecognisedLabelDefaultsConcise() {
        // The vocabulary is CLOSED: systemone.md:7 "Compaction chooses defer,
        // concise, detailed". Anything else is a judgement that did not produce a
        // usable answer, so it is a failure and takes the failure default. "CONCISE"
        // (upper case) and "verbos" both used to land in the same DEFER branch as a
        // real defer, so a typo looked exactly like an explicit instruction not to
        // compact.
        for (String typo : new String[] {"CONCISE", "verbos", "deffered", "", "  "}) {
            var decision = new DecisionResult.ValidChoice("compaction", typo,
                java.util.Map.of("defer", 0.1, "concise", 0.8, "detailed", 0.1),
                java.util.Optional.of(0.8), "concentration");
            var plan = CompactionPlanner.plan(history(),
                java.util.Optional.of(decision), 100000, 2);
            assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy(),
                "label " + typo + " is not in the closed vocabulary, so it is a "
                    + "failed judgement and must default to concise");
        }
    }

    @Test
    @DisplayName("Label matching is case-insensitive and trims, so real labels still work")
    void realLabelsStillHonoured() {
        // Expected values are the enum constants themselves, never valueOf on the
        // input: valueOf is case-sensitive, so asserting through it would have
        // failed on "Concise" for a reason that has nothing to do with the parser.
        for (Object[] pair : new Object[][] {{"defer", CompactionPlanner.Policy.DEFER},
            {"concise", CompactionPlanner.Policy.CONCISE},
            {" DETAILED ", CompactionPlanner.Policy.DETAILED}}) {
            var decision = new DecisionResult.ValidChoice("compaction", (String) pair[0],
                java.util.Map.of("defer", 0.1, "concise", 0.2, "detailed", 0.7),
                java.util.Optional.of(0.7), "concentration");
            var plan = CompactionPlanner.plan(history(),
                java.util.Optional.of(decision), 100000, 2);
            assertEquals(pair[1], plan.policy(), "label " + pair[0] + " must be honoured");
        }
    }

    @Test
    @DisplayName("A real 'defer' is still honoured - the fix must not compact everything")
    void explicitDeferStillHonoured() {
        // The counterweight to the two tests above. If the failure default leaked
        // into the explicit-defer path, every turn at the trigger would compact,
        // and the fix would trade one silent failure for a silent waste of context.
        var decision = new DecisionResult.ValidChoice("compaction", "defer",
            java.util.Map.of("defer", 0.8, "concise", 0.1, "detailed", 0.1),
            java.util.Optional.of(0.8), "concentration");
        var plan = CompactionPlanner.plan(history(), java.util.Optional.of(decision), 100000, 2);
        assertEquals(CompactionPlanner.Policy.DEFER, plan.policy(),
            "an explicit defer that passes the fit check must stay DEFER");
    }

    @Test
    @DisplayName("A null chosenLabel defaults to concise")
    void nullLabelDefaultsConcise() {
        // Every production constructor avoids null today - the adapter reads
        // answer.path("choice").asText("") and ReplayCapture reads asText("") - so a
        // mutation flipping the null branch to DEFER survives on its own. That does
        // not make the branch unreachable: ValidChoice is a PUBLIC record on the
        // decision port, and `new ValidChoice("q", null, ...)` compiles for any
        // future second implementor or test fake. A null that silently means "do not
        // compact" is the worse default of the two, so it is pinned.
        var decision = new DecisionResult.ValidChoice("compaction", null,
            java.util.Map.of("defer", 0.1, "concise", 0.8, "detailed", 0.1),
            java.util.Optional.of(0.8), "concentration");
        var plan = CompactionPlanner.plan(history(),
            java.util.Optional.of(decision), 100000, 2);
        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy(),
            "a null label is a failed judgement and must not read as an explicit defer");
    }
}
