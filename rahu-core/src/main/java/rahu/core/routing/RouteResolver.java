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
            // Positive form, so NaN is rejected. With the old guard a NaN floor made
            // `chosenProb >= floor` permanently false, rejecting every decision and
            // degrading the router forever with no error anywhere.
            if (!(confidenceFloor >= 0.0 && confidenceFloor <= 1.0)) {
                throw new IllegalArgumentException("confidenceFloor in [0,1]");
            }
            // AUDIT-e / F-11: the field is NAMED by the operator, so an unknown name
            // is a configuration error. It was accepted, traced, replayed and then
            // never read - the floor always ran on chosen_probability - which is
            // worse than rejecting it: `confidenceField` looked like it did something.
            if (!SUPPORTED_CONFIDENCE_FIELDS.contains(confidenceField)) {
                throw new IllegalArgumentException("confidenceField must be one of "
                    + SUPPORTED_CONFIDENCE_FIELDS + " (got: " + confidenceField + ")");
            }
        }
    }

    /**
     * The confidence fields a threshold may be applied to (routing.md:26).
     *
     * <p>{@code chosen_probability} is the default. {@code raw_confidence} is the
     * provider's own score, which routing.md:26 requires be kept SEPARATE from the
     * chosen probability rather than conflated with it - so supporting both is what
     * makes "keep them separate" a reachable configuration instead of a note.
     */
    public static final java.util.Set<String> SUPPORTED_CONFIDENCE_FIELDS =
        java.util.Set.of("chosen_probability", "raw_confidence");

    /**
     * The value of {@code field} for this decision, or empty when it carries none.
     *
     * <p>{@code chosen_probability} reads the chosen label's probability;
     * {@code raw_confidence} reads the provider score. The max-probability check
     * below stays on the probability in both cases: it is a property of the
     * decision's own distribution, not of the confidence field.
     */
    private static Optional<Double> confidenceOf(DecisionResult.ValidChoice c, String field) {
        if ("raw_confidence".equals(field)) {
            return c.rawConfidence().filter(Double::isFinite);
        }
        return Optional.ofNullable(c.probabilities().get(c.chosenLabel()));
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
                // AUDIT-e: evaluate the floor against the field the operator NAMED.
                // Before this, `confidenceField` was carried from config through the
                // trace and into replay and never consulted here, so every value of
                // it behaved identically to the default.
                Optional<Double> confidence = confidenceOf(c, in.confidenceField());
                // A named field the decision does not carry has no number to compare,
                // so the gate cannot pass. Substituting the default - or zero, or
                // 1.0 - would manufacture evidence the decision never produced.
                boolean valid = covers
                    && Math.abs(sum - 1.0) <= EPSILON
                    && confidence.isPresent()
                    && confidence.get() >= in.confidenceFloor()
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
