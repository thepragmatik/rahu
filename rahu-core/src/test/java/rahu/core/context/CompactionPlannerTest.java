package rahu.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;

/**
 * A14/A29: compaction policy via System One; deterministic override when fit
 * is impossible; failed summary retains source; pins never summarised away.
 */
class CompactionPlannerTest {

    private static DecisionResult choice(String label) {
        return new DecisionResult.ValidChoice("compaction", label,
            java.util.Map.of("defer", 0.1, "concise", 0.8, "detailed", 0.1),
            java.util.Optional.empty(), "none");
    }

    private static List<ChatMessage> longHistory(int turns) {
        var out = new java.util.ArrayList<ChatMessage>();
        for (int i = 0; i < turns; i++) {
            out.add(ChatMessage.user("turn " + i + " question about module " + i));
            out.add(ChatMessage.assistant("turn " + i + " answer with fact " + i));
        }
        return List.copyOf(out);
    }

    @Test
    @DisplayName("A14: System One concise policy compacts completed units, keeps recent two")
    void conciseCompaction() {
        var history = longHistory(10);
        var plan = CompactionPlanner.plan(history,
            java.util.Optional.of(choice("concise")), 4000, 2);

        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy());
        assertTrue(plan.summarySourceUnits() <= history.size() - 4,
            "recent two turns (4 messages) pinned out of the summary source");
        assertTrue(plan.preservedRecent().size() >= 4, "recent two turns retained");
    }

    @Test
    @DisplayName("Deterministic override: next request cannot fit forces compaction despite defer")
    void overrideForcesCompaction() {
        var history = longHistory(20);
        var plan = CompactionPlanner.plan(history,
            java.util.Optional.of(choice("defer")), 500, 2);
        assertEquals(CompactionPlanner.Policy.CONCISE, plan.policy(),
            "defer is overridden when the fit check fails");
    }

    @Test
    @DisplayName("A14: summary failure retains the original source; nothing is discarded")
    void failedSummaryRetainsSource() {
        var history = longHistory(10);
        var result = CompactionPlanner.applySummary(history,
            new CompactionPlanner.SummaryOutcome.SummaryFailed("summary model returned empty"));
        assertTrue(result.compacted().isEmpty(),
            "failed compaction must not drop the original");
        assertEquals(history, result.source(),
            "original context retained verbatim on failure");
        assertTrue(!result.success());
    }

    @Test
    @DisplayName("Successful summary produces marked-untrusted summary text + retained pins")
    void successfulSummary() {
        var history = longHistory(10);
        var result = CompactionPlanner.applySummary(history,
            new CompactionPlanner.SummaryOutcome.SummaryText(
                "Summary: user asked about modules 0-7; answers gave facts 0-7.",
                List.of()));
        assertTrue(result.success());
        assertEquals(1, result.compacted().size());
        assertTrue(result.compacted().get(0).content().startsWith("[untrusted summary]"));
    }

    @Test
    @DisplayName("A29: context-only empty candidate set triggers a compaction plan, not immediate death")
    void contextOnlyEmptySetPlansCompaction() {
        // 3200-token prompt vs 3000-token context: pressure is real -> compaction plan.
        CompactionPlanner.ContextOnlyAction decision =
            CompactionPlanner.decideForContextOnlyExclusion(3200, 3000);
        assertEquals(CompactionPlanner.ContextOnlyAction.COMPACTION_PLAN, decision,
            "when routes were excluded solely for context, plan compaction first");
        // No pressure means no context-only exclusion happened in the first place.
        assertEquals(CompactionPlanner.ContextOnlyAction.TERMINATE,
            CompactionPlanner.decideForContextOnlyExclusion(1000, 3000));
    }

    @Test
    @DisplayName("Pins smaller than the allowance skip compaction entirely")
    void noCompactionWhenItFits() {
        var history = longHistory(2);
        var plan = CompactionPlanner.plan(history,
            java.util.Optional.of(choice("defer")), 8000, 2);
        assertEquals(CompactionPlanner.Policy.DEFER, plan.policy());
    }
}
