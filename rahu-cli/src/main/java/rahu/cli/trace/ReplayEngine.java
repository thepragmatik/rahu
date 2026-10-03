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

    /**
     * Replays through the live resolver using frozen inputs read back from a
     * capture ({@link ReplayCapture}).
     *
     * <p>AUDIT-2026-10-03-e. This method used to take baseline/fallback as bare
     * strings and hardcode {@code "chosen_probability", 0.65} for the confidence
     * field and floor. Those are the CONFIG DEFAULTS, not the captured values, so
     * every replay of a run configured with any other confidenceField or
     * confidenceFloor resolved against inputs the live turn never used — and then
     * reported agreement or disagreement about a routing question that had not been
     * asked. It was worse than dead code, because it would have produced confident
     * wrong answers the moment it was wired up. The replayed inputs must be the
     * recorded ones.
     */
    @Deprecated(forRemoval = true)
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

    /**
     * The replay entry point that production uses: every resolver input comes from
     * the capture, never from a default in this file.
     */
    public static ReplayOutcome replay(ReplayCapture.Frozen frozen) {
        // Straight to the captured-input overload. Routing through the String form
        // would re-apply the hardcoded confidence field and floor, which is the
        // exact defect this increment removes.
        return replay(frozen.input().candidateSet(), frozen.mode(), frozen.decision(),
            frozen.input());
    }

    /**
     * The captured-input overload.
     *
     * <p>Kept separate from the {@link String} form above so the defaults that
     * caused AUDIT-2026-10-03-e cannot be reached by accident from a command: the
     * only production caller passes frozen inputs.
     */
    public static ReplayOutcome replay(CandidateSet candidates, RoutingMode mode,
        Optional<DecisionResult> recordedDecision, RouteResolver.ResolutionInput input) {

        if (recordedDecision.isEmpty()) {
            return ReplayOutcome.unavailable(
                "replay unavailable: no captured decision payloads for this run");
        }
        RouteResolution resolution = new RouteResolver().resolve(mode, recordedDecision, input);
        return ReplayOutcome.replayed(resolution.suggestedId(), resolution.executedId(),
            resolution.degraded(), resolution.fallbackCause(),
            resolution.terminalReason().map(Enum::name));
    }
}
