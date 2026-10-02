package rahu.core.tools;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Applies an advisory relevance judgment (safety.md line 7): it may narrow the permitted
 * read-only set and can never widen it. A decision is not a permission.
 */
public final class ToolRelevanceGate {

    private ToolRelevanceGate() {
    }

    public static Set<String> apply(Set<String> permitted, Set<String> judgedRelevant) {
        for (String tool : judgedRelevant) {
            if (!permitted.contains(tool)) {
                throw new IllegalArgumentException(
                    "relevance judgment named a tool outside the permitted set: " + tool);
            }
        }
        if (judgedRelevant.isEmpty()) {
            return Set.copyOf(permitted);
        }
        return Set.copyOf(new LinkedHashSet<>(judgedRelevant));
    }
}
