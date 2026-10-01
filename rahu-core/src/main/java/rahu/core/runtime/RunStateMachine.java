package rahu.core.runtime;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Legal phase transitions (runtime.md). One owner mutates run state; a terminal
 * phase cannot start new work. Not thread-safe by design (single driver).
 */
public final class RunStateMachine {

    private static final Map<RunPhase, Set<RunPhase>> LEGAL = Map.of(
        RunPhase.CREATED, EnumSet.of(RunPhase.DECIDING, RunPhase.TERMINAL),
        RunPhase.DECIDING, EnumSet.of(RunPhase.ADMITTED, RunPhase.TERMINAL),
        RunPhase.ADMITTED, EnumSet.of(RunPhase.GENERATING, RunPhase.TERMINAL),
        RunPhase.GENERATING, EnumSet.of(RunPhase.VALIDATING_TOOLS, RunPhase.COMPACTING,
            RunPhase.TERMINAL),
        RunPhase.VALIDATING_TOOLS, EnumSet.of(RunPhase.EXECUTING_TOOLS, RunPhase.GENERATING,
            RunPhase.TERMINAL),
        RunPhase.EXECUTING_TOOLS, EnumSet.of(RunPhase.GENERATING, RunPhase.COMPACTING,
            RunPhase.TERMINAL),
        RunPhase.COMPACTING, EnumSet.of(RunPhase.DECIDING, RunPhase.TERMINAL),
        RunPhase.TERMINAL, EnumSet.noneOf(RunPhase.class));

    private RunPhase phase = RunPhase.CREATED;

    public RunPhase phase() {
        return phase;
    }

    public boolean isTerminal() {
        return phase == RunPhase.TERMINAL;
    }

    /** Transitions to the target phase or throws; terminal target is always legal. */
    public void transitionTo(RunPhase target) {
        Set<RunPhase> allowed = LEGAL.get(phase);
        if (allowed == null || !allowed.contains(target)) {
            throw new RunStateException(phase, target);
        }
        phase = target;
    }
}
