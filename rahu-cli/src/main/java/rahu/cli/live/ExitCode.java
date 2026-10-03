package rahu.cli.live;

/**
 * The process exit codes, defined once.
 *
 * <p>Audit finding AUDIT-2026-10-03-d. cli.md:42 fixes the vocabulary:
 *
 * <blockquote>0 complete answer/deterministic demo, 2 invalid input/configuration,
 * 3 no feasible route/limit reached/privacy blocked, 4 provider/decision/tool
 * failure, 5 trace/replay integrity failure, 130 interrupted.</blockquote>
 *
 * <p>Before this class the drivers returned bare integer literals. Three privacy
 * blocks returned {@code 4}, which cli.md assigns to <em>provider/decision/tool
 * failure</em> - a different claim. The mismatch was invisible because both
 * drivers were consistent with each other: agreeing on the wrong code looks
 * exactly like agreeing on the right one, and my own test asserted "exit 4, as
 * the live driver does", which made the defect load-bearing.
 *
 * <p>Named constants rather than an enum because these cross the process
 * boundary as {@code int}s and a caller compares them against shell exit codes.
 * An enum would add a conversion at every boundary and still permit the same
 * swap. The names carry the spec's meaning, so a reader can check the code
 * against cli.md:42 without leaving the file.
 *
 * <p>{@link #PRIVACY_BLOCKED} is the one most easily confused: it shares its code
 * with cost/limit exhaustion and routing terminals because cli.md groups them
 * ("no feasible route/limit reached/privacy blocked"). That grouping is
 * deliberate in the spec - all three mean "nothing was sent, and retrying
 * unchanged will not help" - so the shared code is a feature for a supervisor
 * deciding whether to retry, and the constant name prevents it being read as a
 * provider outage.
 */
public final class ExitCode {

    /** A complete answer, or a deterministic demo run. */
    public static final int OK = 0;

    /** Invalid input or configuration. */
    public static final int INVALID_INPUT = 2;

    /**
     * Nothing was sent and retrying unchanged will not help: no feasible route, a
     * session/limit ceiling reached, or a privacy block.
     */
    public static final int NO_ROUTE_OR_LIMIT_OR_PRIVACY = 3;

    /** A provider, decision or tool failed. */
    public static final int PROVIDER_DECISION_OR_TOOL_FAILURE = 4;

    /**
     * Trace or replay integrity failure (A16). Distinct from the above on purpose:
     * the run may have produced a correct answer, but its evidence does not exist,
     * so a caller must not treat the answer as auditable.
     */
    public static final int TRACE_INTEGRITY_FAILURE = 5;

    /** Interrupted (SIGINT). */
    public static final int INTERRUPTED = 130;

    /**
     * The exit code for a privacy block, named for the case rather than the number
     * so the call site reads as the reason it is.
     */
    public static final int PRIVACY_BLOCKED = NO_ROUTE_OR_LIMIT_OR_PRIVACY;

    private ExitCode() {
    }
}