package rahu.cli.trace;

/**
 * The run-directory file names, defined once.
 *
 * <p>AUDIT-2026-10-03-f. {@code "events.jsonl"} was spelled out as a literal in
 * five places across {@code src/main} — the writer ({@link RunTracer}) and two
 * independent readers. They agreed by luck.
 *
 * <p>That agreement is load-bearing and invisible. A writer that appends to
 * {@code events.jsonl} while a reader looks for {@code events.jsonl} does not
 * fail loudly: the reader reports "no run trace", which is the same message an
 * operator gets when they simply pointed at the wrong directory. The failure
 * would be attributed to the operator rather than to the rename, and the
 * integrity gate would be reported as working — because on the only inputs anyone
 * tested, it was.
 *
 * <p>This class also exists so {@link RunLocator} has one definition of "a run
 * directory" for both {@code replay} and {@code trace inspect}.
 */
public final class TraceFiles {

    /** The JSONL event log written by {@link TraceWriter}. */
    public static final String EVENTS = "events.jsonl";

    /** The routing-input capture written by {@link ReplayCapture}. */
    public static final String CAPTURE = ReplayCapture.FILE;

    private TraceFiles() {
    }
}