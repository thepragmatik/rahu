package rahu.cli.trace;

import java.nio.file.Path;

import rahu.core.context.SessionState;
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

    public TurnTrace(Path root, Object cfg, Object catalog, String sessionId) {
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
        return new TurnTrace(root, cfg, cfg.catalog(), session.sessionId());
    }
}
