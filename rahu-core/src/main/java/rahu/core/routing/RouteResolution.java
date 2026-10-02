package rahu.core.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of route resolution (ARCHITECTURE.md RouteResolution): suggested vs
 * executed route, mode, fallback/escalation cause, constraints and exclusions.
 */
public record RouteResolution(
    Optional<String> suggestedId,
    Optional<String> executedId,
    RoutingMode mode,
    Optional<String> fallbackCause,
    boolean degraded,
    Optional<TerminalReason> terminalReason,
    List<CandidateSet.Exclusion> exclusions) {

    public RouteResolution {
        Objects.requireNonNull(suggestedId, "suggestedId");
        Objects.requireNonNull(executedId, "executedId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(fallbackCause, "fallbackCause");
        Objects.requireNonNull(terminalReason, "terminalReason");
        Objects.requireNonNull(exclusions, "exclusions");
        suggestedId = suggestedId.map(String::intern);
        executedId = executedId.map(String::intern);
        fallbackCause = fallbackCause.map(String::intern);
        exclusions = List.copyOf(exclusions);
    }
}
