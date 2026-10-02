package rahu.core.runtime;

/**
 * Run phases (runtime.md): created -> deciding -> admitted -> generating ->
 * validating-tools -> executing-tools -> compacting -> terminal. A terminal
 * phase cannot start new work.
 */
public enum RunPhase {
    CREATED,
    DECIDING,
    ADMITTED,
    GENERATING,
    VALIDATING_TOOLS,
    EXECUTING_TOOLS,
    COMPACTING,
    TERMINAL
}
