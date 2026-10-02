package rahu.core.decision;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One batched decision per turn (systemone.md: classification and relevance may be batched
 * because their questions are independent; route is deliberately NOT part of this).
 * Every failure path widens nothing: unknown class, permitted tool set.
 */
public record TurnProfile(TaskClass taskClass, Set<String> relevantTools, boolean degraded) {

    public TurnProfile {
        relevantTools = Set.copyOf(relevantTools);
    }

    public static TurnProfile from(Optional<DecisionResult> classification,
        Set<String> includedTools, List<String> permittedTools, Optional<DecisionResult> unused) {

        boolean degraded = false;

        TaskClass taskClass = TaskClass.UNKNOWN;
        if (classification.isPresent()) {
            DecisionResult result = classification.get();
            if (result instanceof DecisionResult.ValidChoice choice) {
                taskClass = TaskClass.fromLabel(choice.chosenLabel());
                if (!taskClass.isConfident()) {
                    degraded = true;
                }
            } else {
                degraded = true;
            }
        } else {
            degraded = true;
        }

        // A missing or failed relevance judgment exposes the permitted read-only set.
        Set<String> tools = includedTools == null || includedTools.isEmpty()
            ? new LinkedHashSet<>(permittedTools)
            : new LinkedHashSet<>(includedTools);
        if (tools.isEmpty()) {
            tools = new LinkedHashSet<>(permittedTools);
        }

        return new TurnProfile(taskClass, tools, degraded);
    }
}
