package rahu.cli.live;

import java.util.Optional;

/**
 * One turn's observable result, as data rather than as an exit code.
 *
 * <p>Audit finding AUDIT-2026-10-03-g. {@code LiveTurnDriver.oneTurn} returned a bare
 * {@code int}, so every caller learned only whether the turn succeeded. That is enough
 * for {@code chat}, which prints a human footer, and not enough for {@code rahu run
 * --format json}: cli.md:15 requires "one final structured result to stdout". Emitting
 * that JSON by scraping the human stderr trail would make the machine surface a
 * function of human phrasing - the same reason the trace writer is not reconstructed
 * from log lines.
 *
 * <p>The fields are the ones a caller cannot otherwise recover:
 *
 * <ul>
 *   <li>{@code terminalReason} - why the run ended, which {@code int} cannot express.
 *       Two different failures can share exit 3 and mean opposite things.
 *   <li>{@code answer} - the answer text, so JSON mode never has to re-print or
 *       re-capture what already went to stdout in text mode.
 *   <li>{@code routing} - the resolution, so the caller does not re-resolve. The
 *       AUDIT-e capture rule applies here too: re-deriving the resolution would
 *       report a route that differs from the one actually executed.
 *   <li>{@code usage} - reported cost/tokens, including their ABSENCE. A caller must
 *       be able to distinguish "cost 0" from "cost unreported", which are different
 *       claims about money (A10).
 * </ul>
 *
 * <p>{@code answer} and {@code usage} are optional because a refused turn has neither.
 * A privacy block still produces an outcome: it is a real, reportable result, and
 * omitting it would make "refused" indistinguishable from "never attempted".
 */
public record TurnOutcome(
    int exitCode,
    String terminalReason,
    Optional<String> answer,
    Optional<String> runId,
    Optional<RouteInfo> routing,
    Optional<rahu.core.model.Usage> usage,
    int generationSteps,
    long elapsedMs) {

    /**
     * The routing decision as observed, mirroring the fields {@code RouteResolved}
     * records so the footer, the trace and the JSON cannot disagree about which
     * candidate ran.
     */
    public record RouteInfo(
        String suggestedId,
        String executedId,
        String mode,
        boolean degraded,
        String fallbackCause,
        int excludedCandidates) {
    }

    /** True when the turn produced an answer the caller may present. */
    public boolean answered() {
        return exitCode == ExitCode.OK && answer.isPresent();
    }

    /**
     * Whether a cost was reported at all.
     *
     * <p>Separate from a zero cost on purpose: an unreported cost on a dispatched
     * request is UNCERTAIN (A10), not free, and a caller that renders this as
     * "$0.00" is asserting the provider billed nothing.
     */
    public boolean costUnobserved() {
        return usage.isEmpty() || usage.get().totalCostMicrosOpt().isEmpty();
    }
}
