package rahu.cli.trace;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Per-turn trace recorder: owns one {@link TraceWriter} and the run metadata the
 * driver knows, so {@code LiveTurnDriver} emits events by describing what it did
 * rather than hand-assembling envelopes.
 *
 * <p>Wiring note (AUDIT-2026-10-03-a). Before this class existed, nothing in
 * {@code src/main} constructed a {@code TraceWriter}: the G06 trace subsystem was
 * built and unit-tested but unreachable from a real turn. This is the seam that
 * makes it reachable.
 *
 * <p>Metadata only (observability.md default). Payloads carry hashes, identifiers
 * and counters - never prompt text, tool content, arguments or reasoning. A trace
 * is therefore safe to retain by default, which is what makes writing one on every
 * turn acceptable rather than a privacy regression.
 *
 * <p>A16: an append failure is not swallowed. It propagates so the caller can stop
 * new work, and {@link #failed()} latches so a later success cannot hide an earlier
 * gap.
 */
public final class RunTracer implements AutoCloseable {

    private final TraceWriter writer;
    private final String runId;
    private final String sessionId;
    private final long startNs;

    private boolean failed;
    private boolean terminated;

    /**
     * @param directory the configured trace root; the run gets its own subdirectory
     * @param runId the driver's run id, so the trace joins the run
     */
    public RunTracer(Path directory, String runId, String sessionId) {
        this.writer = new TraceWriter(directory.resolve(runId).resolve(TraceFiles.EVENTS));
        this.runId = runId;
        this.sessionId = sessionId;
        this.startNs = System.nanoTime();
    }

    public String runId() {
        return runId;
    }

    /**
     * Opens the run. The hashes are of the actual config and catalog bytes, so two
     * runs over different configurations are distinguishable in a trace archive -
     * which is the entire point of recording them (observability.md).
     */
    public void runStarted(int turnIndex, String configHash, String catalogHash) {
        append("RunStarted", """
            {"sessionId":%s,"turnIndex":%d,"configHash":%s,"catalogHash":%s}"""
            .formatted(json(sessionId), turnIndex, json(configHash), json(catalogHash)));
    }

    /** The routing outcome as resolved, including why it degraded if it did. */
    public void routeResolved(String suggested, String executed, String mode, boolean degraded,
        String fallbackCause, int excluded) {
        append("RouteResolved", """
            {"suggested":%s,"executed":%s,"mode":%s,"degraded":%s,"fallbackCause":%s,\
            "excludedCandidates":%d}"""
            .formatted(json(suggested), json(executed), json(mode), degraded,
                fallbackCause == null ? "null" : json(fallbackCause), excluded));
    }

    /** A model dispatch, before transport, so a hung call is still visible. */
    public void modelRequested(String requestedModel, int maxTokens) {
        append("ModelRequested", """
            {"requestedModel":%s,"maxCompletionTokens":%d}"""
            .formatted(json(requestedModel), maxTokens));
    }

    /**
     * The completed generation. Usage is recorded as counters because the numbers
     * are needed to reconcile a run; the model ids are recorded because a provider
     * alias can drift from the model that actually served the request.
     */
    public void modelCompleted(String requestedModel, String observedModel, String finishReason,
        Integer promptTokens, Integer completionTokens, Long costMicros) {
        append("ModelCompleted", """
            {"requestedModel":%s,"observedModel":%s,"finishReason":%s,"usage":\
            {"promptTokens":%s,"completionTokens":%s,"costMicros":%s},"effort":"%s"}"""
            .formatted(json(requestedModel), observedModel == null ? "null" : json(observedModel),
                json(finishReason), promptTokens == null ? "null" : promptTokens,
                completionTokens == null ? "null" : completionTokens,
                costMicros == null ? "null" : costMicros, "provider-default"));
    }

    public void modelFailed(String kind, String safeReason) {
        append("ModelFailed", """
            {"kind":%s,"safeReason":%s}""".formatted(json(kind), json(safeReason)));
    }

    /**
     * Why a run was refused, recorded by CATEGORY only (privacy.md: diagnostics
     * identify category/reason, never the offending data). Recorded before the
     * terminal record so the archive reads in causal order.
     */
    public void refused(String category) {
        append("RunRefused", "{\"category\":" + json(category) + "}");
    }

    /**
     * The terminal record. Always emitted exactly once per run: a trace that ends
     * without one is an incomplete run, which is a different claim from a run that
     * finished.
     */
    public void runTerminated(String reason, long generationSteps) {
        if (terminated) {
            return;
        }
        terminated = true;
        append("RunTerminated", """
            {"reason":%s,"generationSteps":%d,"elapsedMs":%d}"""
            .formatted(json(reason), generationSteps, elapsedMs()));
    }

    /** True once an append has failed; latched, so it never returns to false. */
    public boolean failed() {
        return failed || writer.failed();
    }

    public Path file() {
        return writer.file();
    }

    @Override
    public void close() {
        writer.close();
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    /**
     * Appends one event. The envelope fields the writer assigns (sequence) and the
     * ones it needs (runId, timestamp, elapsed) are supplied here; only the
     * metadata-specific payload is passed in by the caller.
     */
    private void append(String type, String payloadJson) {
        try {
            writer.append(new TraceEvent(TraceEvent.SCHEMA_VERSION, runId, 1,
                Instant.now().toString(), elapsedMs(), type, null, payloadJson));
        } catch (TraceFailureException e) {
            // Latch, then rethrow. Swallowing here would make a broken sink look
            // like a healthy run, which is precisely the A16 failure mode.
            failed = true;
            throw e;
        }
    }

    /** Minimal JSON string encoding: escapes quotes, backslash and control chars. */
    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
