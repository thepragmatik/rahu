package rahu.cli.trace;

import java.util.Objects;
import java.util.Optional;

/** Replay result (observability.md): REPLAYED with the re-derived policy outcome, or UNAVAILABLE. */
public record ReplayOutcome(
    ReplayStatus status,
    Optional<String> suggestedId,
    Optional<String> executedId,
    boolean degraded,
    Optional<String> fallbackCause,
    Optional<String> terminalReason,
    String unavailableReason) {

    public ReplayOutcome {
        Objects.requireNonNull(status, "status");
        suggestedId = suggestedId == null ? Optional.empty() : suggestedId;
        executedId = executedId == null ? Optional.empty() : executedId;
        fallbackCause = fallbackCause == null ? Optional.empty() : fallbackCause;
        terminalReason = terminalReason == null ? Optional.empty() : terminalReason;
    }

    public enum ReplayStatus { REPLAYED, UNAVAILABLE }

    public static ReplayOutcome replayed(Optional<String> suggested, Optional<String> executed,
        boolean degraded, Optional<String> fallbackCause, Optional<String> terminalReason) {
        return new ReplayOutcome(ReplayStatus.REPLAYED, suggested, executed, degraded,
            fallbackCause, terminalReason, null);
    }

    public static ReplayOutcome unavailable(String reason) {
        return new ReplayOutcome(ReplayStatus.UNAVAILABLE, Optional.empty(), Optional.empty(),
            false, Optional.empty(), Optional.empty(), reason);
    }
}
