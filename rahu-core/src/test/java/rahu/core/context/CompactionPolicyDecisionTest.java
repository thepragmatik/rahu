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
}
