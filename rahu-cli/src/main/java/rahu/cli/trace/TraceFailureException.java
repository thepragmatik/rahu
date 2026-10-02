package rahu.cli.trace;

/** Trace persistence failed (A16): the run must stop new work, never fake success. */
public final class TraceFailureException extends IllegalStateException {

    public TraceFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
