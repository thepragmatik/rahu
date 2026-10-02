package rahu.cli.trace;

import java.util.Map;
import java.util.Optional;
import rahu.core.decision.DecisionResult;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.RouteResolution;
import rahu.core.routing.RouteResolver;
import rahu.core.routing.RoutingMode;

/**
 * Offline deterministic policy replay (observability.md): recorded decision
 * outcomes drive the pure routing policy through the same RouteResolver used
 * live. Zero network, zero tool effects, zero model calls. Without captured
 * payloads the replay is UNAVAILABLE — never fabricated.
 */
public final class ReplayEngine {

    private ReplayEngine() {
    }

    public static ReplayOutcome replay(CandidateSet candidates, RoutingMode mode,
        Optional<DecisionResult> recordedDecision, String baselineId, String fallbackId) {

        if (recordedDecision.isEmpty()) {
            return ReplayOutcome.unavailable(
                "replay unavailable: no captured decision payloads for this run");
        }
        RouteResolver resolver = new RouteResolver();
        RouteResolver.ResolutionInput input = new RouteResolver.ResolutionInput(
            candidates, Optional.ofNullable(baselineId), Optional.ofNullable(fallbackId),
            "chosen_probability", 0.65);
        RouteResolution resolution = resolver.resolve(mode, recordedDecision, input);

        return ReplayOutcome.replayed(resolution.suggestedId(), resolution.executedId(),
            resolution.degraded(), resolution.fallbackCause(),
            resolution.terminalReason().map(Enum::name));
    }
}
