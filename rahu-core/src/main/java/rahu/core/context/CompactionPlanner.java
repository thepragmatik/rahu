package rahu.core.context;

import java.util.List;
import java.util.Optional;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;

/**
 * Minimal context compaction (runtime.md): System One chooses defer/concise/
 * detailed; code deterministically overrides when fit fails. The recent two
 * complete turns stay out of the summary source; a failed summary retains the
 * original verbatim; successful summaries are marked untrusted in the prompt.
 */
public final class CompactionPlanner {

    public enum Policy { DEFER, CONCISE, DETAILED }

    /** What to do when candidates were excluded solely for context (A29). */
    public enum ContextOnlyAction { COMPACTION_PLAN, TERMINATE }

    /** Planner output: chosen policy + the unit split it implies. */
    public record Plan(Policy policy, int summarySourceUnits, List<ChatMessage> preservedRecent) {

        public Plan {
            preservedRecent = List.copyOf(preservedRecent);
        }
    }

    /** Sealed summary outcome. */
    public sealed interface SummaryOutcome {
        record SummaryText(String text, List<String> sourceItemIds) implements SummaryOutcome {
            public SummaryText {
                sourceItemIds = List.copyOf(sourceItemIds);
            }
        }

        record SummaryFailed(String safeReason) implements SummaryOutcome {
        }
    }

    public record CompactionResult(boolean success, List<ChatMessage> compacted,
        List<ChatMessage> source) {

        public CompactionResult {
            compacted = List.copyOf(compacted);
            source = List.copyOf(source);
        }
    }

    private static final int RECENT_TURNS = 2;
    private static final int MESSAGES_PER_TURN = 2;

    private CompactionPlanner() {
    }

    /**
     * Chooses the policy: System One's answer; a failed or absent answer
     * defaults to CONCISE (fail closed) and the deterministic fit check can
     * still override an explicit DEFER when the next request cannot fit.
     */
    public static Plan plan(List<ChatMessage> history, Optional<DecisionResult> decision,
        int contextAllowanceTokens, int maxRecentTurns) {

        Policy requested = Policy.DEFER;
        if (decision.isPresent() && decision.get() instanceof DecisionResult.ValidChoice c) {
            requested = switch (c.chosenLabel()) {
                case "concise" -> Policy.CONCISE;
                case "detailed" -> Policy.DETAILED;
                default -> Policy.DEFER;
            };
        } else if (decision.isPresent()) {
            // Consulted but unreachable: fail closed to the conservative compact.
            requested = Policy.CONCISE;
        }

        int estimated = PromptAssembler.estimateTokens(
            history.isEmpty() ? List.of(ChatMessage.user("")) : history, Integer.MAX_VALUE);
        boolean cannotFit = estimated >= contextAllowanceTokens;

        Policy effective = (requested == Policy.DEFER && cannotFit) ? Policy.CONCISE : requested;
        if (effective == Policy.DEFER || history.size() <= RECENT_TURNS * MESSAGES_PER_TURN) {
            int keep = Math.min(history.size(), RECENT_TURNS * MESSAGES_PER_TURN);
            return new Plan(Policy.DEFER, 0, history.subList(history.size() - keep,
                history.size()));
        }

        int keepUnits = Math.min(RECENT_TURNS, maxRecentTurns) * MESSAGES_PER_TURN;
        int sourceUnits = history.size() - keepUnits;
        return new Plan(effective, sourceUnits,
            history.subList(history.size() - keepUnits, history.size()));
    }

    /** Applies a summary outcome; failure keeps the original context verbatim. */
    public static CompactionResult applySummary(List<ChatMessage> history,
        SummaryOutcome outcome) {

        if (outcome instanceof SummaryOutcome.SummaryFailed) {
            return new CompactionResult(false, List.of(), history);
        }
        if (outcome instanceof SummaryOutcome.SummaryText s) {
            String marked = "[untrusted summary]\n" + s.text();
            return new CompactionResult(true,
                List.of(ChatMessage.user(marked)), history);
        }
        return new CompactionResult(false, List.of(), history);
    }

    /** A29: context-only exclusion -> plan compaction before terminal failure. */
    public static ContextOnlyAction decideForContextOnlyExclusion(int estimatedPromptTokens,
        int candidateContext) {

        return estimatedPromptTokens > candidateContext
            ? ContextOnlyAction.COMPACTION_PLAN
            : ContextOnlyAction.TERMINATE;
    }
}
