package rahu.cli.trace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.RouteResolver.ResolutionInput;
import rahu.core.routing.RoutingMode;
import rahu.core.privacy.SafeView;

/**
 * The trace configuration every chat turn needs, computed once and shared by the
 * live and offline loops.
 *
 * <p>Audit finding AUDIT-2026-10-03-b. The live driver grew its own
 * {@code configHash}/{@code catalogHash}/{@code recordRefusal} helpers while
 * {@code runOffline} had no trace wiring at all. Had I simply copied the live
 * pattern into the offline loop, the two would have started as identical and
 * drifted the first time one side changed - which is how a fix becomes two
 * half-fixes. The shared parts therefore live here and both loops call them.
 *
 * <p>Hashes are of the loaded records rather than the source files, so two runs
 * are distinguishable exactly when their effective configuration differs and not
 * merely when a file was reformatted. Recorded as hashes because a trace is
 * retained by default and must carry no endpoint credentials.
 */
public final class TurnTrace {

    private final Path root;
    private final String configHash;
    private final String catalogHash;
    private final String sessionId;
    private final boolean payloadsEnabled;

    public TurnTrace(Path root, Object cfg, Object catalog, String sessionId) {
        this(root, cfg, catalog, sessionId, "metadata");
    }

    /**
     * @param capture the configured {@code trace.capture}; "payloads" is the only
     *     value that enables routing-input capture, anything else is metadata-only
     */
    public TurnTrace(Path root, Object cfg, Object catalog, String sessionId, String capture) {
        this.payloadsEnabled = "payloads".equals(capture);
        this.root = root;
        this.configHash = SafeView.sha256Of(String.valueOf(cfg));
        // The catalog decides which candidates are admissible, so two traces
        // sharing a config but not a catalog are not comparable. Hashing the
        // configured reference distinguishes them without recording the models.
        this.catalogHash = catalog == null ? "absent" : SafeView.sha256Of(String.valueOf(catalog));
        this.sessionId = sessionId;
    }

    /** Opens a tracer for a real turn that has a run handle. */
    public RunTracer begin(String runId, int turnIndex) {
        var tracer = new RunTracer(root, runId, sessionId);
        tracer.runStarted(turnIndex, configHash, catalogHash);
        return tracer;
    }

    /**
     * Records a refusal, which happens BEFORE a run handle exists.
     *
     * <p>The id is marked {@code refused-} so it can never be confused with a real
     * run, and it still gets a full three-event trace: a privacy refusal that
     * leaves no record is indistinguishable from a turn that never ran, which is
     * the specific gap this wiring exists to close.
     *
     * <p>A sink failure here must not turn a refusal into an admission, so the
     * exception is caught, reported by the caller, and the input stays blocked.
     *
     * @return true when the refusal was recorded
     */
    /**
     * Writes the frozen routing inputs for this run, when capture is enabled.
     *
     * <p>AUDIT-2026-10-03-e: {@code trace.capture} was parsed into config and read
     * by nothing, so "payloads" and "metadata" were indistinguishable and replay had
     * no input to read. This is the read.
     *
     * <p>Returns the file written, or empty when capture is off (the default). An
     * empty return is not a failure: metadata capture deliberately writes no
     * replay input, and a replay of such a run correctly reports UNAVAILABLE.
     */
    public Optional<Path> captureReplayInputs(Path runDirectory,
        CandidateSet candidates, RoutingMode mode, ResolutionInput input,
        Optional<DecisionResult> decision) throws IOException {

        if (!payloadsEnabled) {
            return Optional.empty();
        }
        return Optional.of(ReplayCapture.write(runDirectory, candidates, mode, input, decision));
    }

    /**
     * Whether payload capture is on.
     *
     * <p>Only the exact value "payloads" enables it. An unrecognised value is not
     * treated as off: a typo must not silently downgrade a run to metadata-only,
     * because the operator then believes a replayable trace exists. Unknown values
     * are refused at config load; this is the second line for a config built in
     * code rather than parsed from JSON.
     */
    public boolean payloadsEnabled() {
        return payloadsEnabled;
    }

    public boolean recordRefusal(int turnIndex, String blockedCategory) {
        try (var tracer = new RunTracer(root, "refused-" + java.util.UUID.randomUUID(), sessionId)) {
            tracer.runStarted(turnIndex, configHash, catalogHash);
            tracer.refused(blockedCategory);
            tracer.runTerminated("PRIVACY_BLOCKED", 0);
            return true;
        } catch (TraceFailureException e) {
            return false;
        }
    }

    public String configHash() {
        return configHash;
    }

    public String catalogHash() {
        return catalogHash;
    }

    public Path root() {
        return root;
    }

    /** Convenience factory for a session. */
    public static TurnTrace forSession(Path root, rahu.cli.config.RahuConfig cfg,
        SessionState session) {
        return new TurnTrace(root, cfg, cfg.catalog(), session.sessionId(),
            cfg.trace() == null ? "metadata" : cfg.trace().capture());
    }
}
