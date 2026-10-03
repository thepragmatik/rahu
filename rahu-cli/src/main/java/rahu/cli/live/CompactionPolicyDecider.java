package rahu.cli.live;

import java.util.List;
import rahu.core.context.CompactionPlanner;
import rahu.core.model.ChatMessage;
import rahu.systemone.DecisionEngine;

/**
 * COMPACTION_POLICY consult (context.md): at the pressure trigger, System One
 * chooses defer/concise/detailed and the deterministic fit check keeps the
 * final say.
 *
 * <p>A transport failure means no ANSWER arrived, and the planner then fails
 * closed to the conservative compact. An earlier version of this javadoc said
 * the opposite — "nothing is compacted" — which was true when the planner
 * defaulted a missing answer to DEFER and false once it failed closed
 * (AUDIT-2026-10-03-q). systemone.md:40 requires the concise default, and this
 * class executes no summary, so no context is ever dropped here regardless.
 */
public final class CompactionPolicyDecider {

    /** One consult outcome: the effective policy and whether Jev answered. */
    public record Consult(CompactionPlanner.Policy policy, boolean answered,
        String safeNote) {
    }

    private final DecisionEngine engine;

    public CompactionPolicyDecider(DecisionEngine engine) {
        this.engine = engine;
    }

    /**
     * Consults COMPACTION_POLICY for the candidate next-request message list
     * (stored history plus the pending user request) and returns the plan's
     * effective policy. Never throws and never drops context: any failure
     * degrades to the deterministic planner run with no decision attached.
     */
    public Consult consult(List<ChatMessage> nextRequestMessages, int contextAllowanceTokens,
        double contextPressure) {
        java.util.Optional<rahu.core.decision.DecisionResult> answer = java.util.Optional.empty();
        String note = null;
        try {
            // The REAL pressure, not 0.0. This consult only runs when the caller
            // measured pressure >= 0.80, yet it handed the decision port 0.0 while
            // the request view told the model "pressure is above the 80% trigger".
            // The typed field is what a model weights most, so the engine was being
            // told the context was empty at the moment it was asked to compact it,
            // and any reasoning conditioned on that field argued for defer. Found
            // by a mutation test (AUDIT-2026-10-03-r), not by reading.
            var state = new DecisionEngine.State("COMPACTION_POLICY",
                requestView(nextRequestMessages),
                Math.min(1.0, Math.max(0.0, contextPressure)));
            var answers = engine.askAll(state,
                List.of(rahu.systemone.DecisionQuestions.compactionPolicy()));
            answer = java.util.Optional.ofNullable(answers.get("compaction"));
        } catch (RuntimeException e) {
            answer = java.util.Optional.empty();
            note = "unavailable (" + e.getClass().getSimpleName() + ")";
        }
        var plan = CompactionPlanner.plan(nextRequestMessages, answer, contextAllowanceTokens, 2);
        boolean answered = answer.isPresent();
        if (answered) {
            note = policyNote(plan.policy());
        }
        return new Consult(plan.policy(), answered, note);
    }

    /** Bounded request view: the last few units, including the pending request. */
    private static String requestView(List<ChatMessage> nextRequestMessages) {
        int from = Math.max(0, nextRequestMessages.size() - 4);
        var view = new StringBuilder("estimated context pressure is above the 80% trigger;"
            + " recent units and the pending request:");
        for (ChatMessage m : nextRequestMessages.subList(from, nextRequestMessages.size())) {
            String content = m.content();
            view.append(' ').append(content, 0, Math.min(80, content.length()));
        }
        return view.toString();
    }

    private static String policyNote(CompactionPlanner.Policy policy) {
        return switch (policy) {
            case DEFER -> "defer: context fits; nothing compacted";
            case CONCISE -> "concise target (<=512 est. output tokens)";
            case DETAILED -> "detailed target (<=1024 est. output tokens)";
        };
    }
}
