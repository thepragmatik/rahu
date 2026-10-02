package rahu.core.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import rahu.core.ExecutionCandidate;
import rahu.core.decision.DecisionResult;

/**
 * Shadow/active route resolution (routing.md decision table). Selection comes
 * only from the feasible set; malformed or out-of-set decisions degrade to the
 * configured fallback; no feasible fallback terminates before any call.
 */
public final class RouteResolver {

    /** Tolerance for probability sums and maximal-probability checks (routing.md). */
    private static final double EPSILON = 0.0001;

    public record ResolutionInput(
        CandidateSet candidateSet,
        Optional<String> baselineId,
        Optional<String> fallbackId,
        String confidenceField,
        double confidenceFloor) {

        public ResolutionInput {
            if (confidenceFloor < 0.0 || confidenceFloor > 1.0) {
                throw new IllegalArgumentException("confidenceFloor in [0,1]");
            }
        }
    }

    public RouteResolution resolve(RoutingMode mode,
        Optional<DecisionResult> decision, ResolutionInput in) {

        var candidateIds = in.candidateSet().candidates().stream()
            .map(ExecutionCandidate::id).collect(Collectors.toSet());

        Optional<String> suggested = Optional.empty();
        boolean degradedDecision = false;
        String fallbackCause = null;

        if (decision.isPresent()) {
            DecisionResult d = decision.get();
            if (d instanceof DecisionResult.Failure f) {
                degradedDecision = true;
                fallbackCause = "decision-failed";
            } else if (d instanceof DecisionResult.ValidChoice c) {
                Set<String> probs = c.probabilities().keySet();
                boolean covers = probs.equals(candidateIds);
                double sum = probs.stream().mapToDouble(c.probabilities()::get).sum();
                double chosenProb = Optional.ofNullable(c.probabilities().get(c.chosenLabel()))
                    .orElse(Double.NaN);
                double maxProb = probs.stream().mapToDouble(c.probabilities()::get).max()
                    .orElse(Double.NaN);
                boolean valid = covers
                    && Math.abs(sum - 1.0) <= EPSILON
                    && chosenProb >= in.confidenceFloor()
                    && chosenProb >= maxProb - EPSILON;
                if (valid && candidateIds.contains(c.chosenLabel())) {
                    suggested = Optional.of(c.chosenLabel());
                } else {
                    degradedDecision = true;
                    fallbackCause = "decision-rejected";
                }
            } else {
                degradedDecision = true;
                fallbackCause = "decision-rejected";
            }
        } else {
            degradedDecision = true;
            fallbackCause = "decision-missing";
        }

        if (mode == RoutingMode.ACTIVE && suggested.isPresent()) {
            return new RouteResolution(suggested, suggested, mode, Optional.empty(), false,
                Optional.empty(), in.candidateSet().exclusions());
        }

        // Shadow executes the feasible baseline (records the suggestion); active
        // mode with a degraded/rejected decision uses the configured fallback
        // (routing.md decision table). Neither feasible terminates before calls.
        Optional<String> target = mode == RoutingMode.SHADOW
            ? in.baselineId().filter(candidateIds::contains)
                .or(() -> in.fallbackId().filter(candidateIds::contains))
            : in.fallbackId().filter(candidateIds::contains);
        String executed = target.orElse(null);

        if (executed == null) {
            return new RouteResolution(suggested, Optional.empty(), mode,
                Optional.ofNullable(fallbackCause), true,
                Optional.of(TerminalReason.NO_FEASIBLE_ROUTE),
                in.candidateSet().exclusions());
        }

        return new RouteResolution(suggested, Optional.of(executed), mode,
            Optional.ofNullable(fallbackCause), degradedDecision,
            Optional.empty(), in.candidateSet().exclusions());
    }
}
