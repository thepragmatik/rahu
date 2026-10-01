package rahu.core.routing;

/**
 * Terminal run reasons (runtime.md state and termination). Exactly one terminal
 * outcome per run; a terminal state cannot start new work.
 */
public enum TerminalReason {
    ANSWER_COMPLETE,
    NO_FEASIBLE_ROUTE,
    STEP_LIMIT,
    TIME_LIMIT,
    COST_ADMISSION_DENIED,
    CONTEXT_LIMIT,
    NO_PROGRESS,
    PRIVACY_BLOCKED,
    PROVIDER_FAILURE,
    DECISION_FAILURE,
    TOOL_FAILURE,
    CANCELLED,
    TRACE_FAILURE,
    INDETERMINATE
}
