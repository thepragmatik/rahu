package rahu.cli.eval;

/** Suite/report validation error carrying the field path (artifacts.md). */
public final class EvalError extends IllegalArgumentException {

    public EvalError(String message) {
        super(message);
    }
}
