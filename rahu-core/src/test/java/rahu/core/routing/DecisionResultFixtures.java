package rahu.core.routing;

import java.util.Map;
import rahu.core.decision.DecisionResult;

/** Shared decision fixtures for routing tests (synthetic, no network). */
public final class DecisionResultFixtures {

    private DecisionResultFixtures() {
    }

    public static DecisionResult.ValidChoice validChoice(String label,
        Map<String, Double> probabilities) {
        return new DecisionResult.ValidChoice("q", label, probabilities,
            java.util.Optional.empty(), "none");
    }
}
