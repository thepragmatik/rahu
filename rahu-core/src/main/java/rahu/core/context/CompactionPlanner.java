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

    public enum Policy { DEFER, CONCISE, DETAILED;

        /**
         * Parses a System One compaction label; anything not in the closed
         * vocabulary becomes CONCISE.
         *
         * <p>systemone.md:7 fixes the vocabulary as "defer, concise, detailed", and
         * systemone.md:40 says a compaction-policy FAILURE defaults to concise. An
         * unrecognised label is a judgement that produced no usable answer, so it
         * takes that failure default rather than the DEFER branch it used to share
         * with a real defer - which made a typo look exactly like an explicit
         * instruction not to compact.
         *
         * <p>Case and surrounding whitespace are ignored, matching
         * {@link rahu.core.decision.TaskClass#fromLabel} on the same wire. The
         * labels reach us as free text from a model, so "CONCISE" is a spelling of
         * the same answer and refusing it would be the same defect at a new scale.
         */
        public static Policy fromLabel(String label) {
            if (label == null) {
                return CONCISE;
            }
            String normalised = label.trim().toLowerCase(java.util.Locale.ROOT);
            for (Policy candidate : values()) {
                if (candidate.name().toLowerCase(java.util.Locale.ROOT).equals(normalised)) {
                    return candidate;
                }
            }
            return CONCISE;
        }
    }

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
     * still override an explicit DEFER when the candidate next-request list
     * (the same content the caller's pressure measurement contains) cannot fit.
     */
    public static Plan plan(List<ChatMessage> history, Optional<DecisionResult> decision,
        int contextAllowanceTokens, int maxRecentTurns) {

        // Every non-answer fails closed to the conservative compact. This used to
        // leave an ABSENT decision at DEFER, which is precisely the outcome the 80%
        // trigger exists to prevent: an unreachable decision engine printed
        // "context fits; nothing compacted" while the caller had already measured
        // the context as over the trigger. The deterministic fit check could not
        // rescue it, because that check promotes DEFER to CONCISE only when the next
        // request CANNOT fit - a generous allowance left it deferring. systemone.md:40
        // requires the concise default; see also TurnProfile, which fails closed the
        // same way when a classification is missing.
        Policy requested = Policy.CONCISE;
        if (decision.isPresent() && decision.get() instanceof DecisionResult.ValidChoice c) {
            requested = Policy.fromLabel(c.chosenLabel());
        }

        // The fit check estimates the candidate NEXT-REQUEST list (stored
        // history plus the pending request) — the same content the 80% pressure
        // measurement contains. Estimating history alone would let a request
        // that cannot fit be "honoured" as defer.
        // No allowance argument: the estimate is a measurement and is not clamped
        // to one (AUDIT-2026-10-03-r). This call used to pass Integer.MAX_VALUE
        // purely to escape a clamp that made a real overrun indistinguishable from
        // a comfortable fit.
        int estimated = PromptAssembler.estimateTokens(
            history.isEmpty() ? List.of(ChatMessage.user("")) : history);
        boolean cannotFit = estimated >= contextAllowanceTokens;

        Policy effective = (requested == Policy.DEFER && cannotFit) ? Policy.CONCISE : requested;
        if (effective == Policy.DEFER || history.size() <= RECENT_TURNS * MESSAGES_PER_TURN) {
            // Nothing to summarise (no completed units beyond the recent window):
            // summarySourceUnits is 0, but the recorded policy is still the
            // effective requirement — a fit-check override must not be reported
            // as "defer / context fits" when the next request cannot fit.
            int keep = Math.min(history.size(), RECENT_TURNS * MESSAGES_PER_TURN);
            return new Plan(effective, 0, history.subList(history.size() - keep,
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
