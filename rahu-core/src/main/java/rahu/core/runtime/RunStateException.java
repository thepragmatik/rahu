package rahu.core.runtime;

/** Illegal transition attempt; carries both phases for safe diagnostics. */
public final class RunStateException extends IllegalStateException {

    private final RunPhase from;
    private final RunPhase to;

    public RunStateException(RunPhase from, RunPhase to) {
        super("illegal transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public RunPhase from() {
        return from;
    }

    public RunPhase to() {
        return to;
    }
}
