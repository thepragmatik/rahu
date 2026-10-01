package rahu.cli.trace;

import java.util.Map;
import java.util.Optional;
import rahu.core.decision.DecisionResult;

/** Local decision fixtures for CLI-layer replay tests (synthetic). */
public final class DecisionResultFixtures {

    public DecisionResult.ValidChoice validChoice(String label, Map<String, Double> probs) {
        return new DecisionResult.ValidChoice("q", label, probs, Optional.empty(), "none");
    }
}
