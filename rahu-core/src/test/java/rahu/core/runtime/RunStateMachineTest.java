package rahu.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S06 runtime transitions (runtime.md; A12 semantics at the state level). */
class RunStateMachineTest {

    @Test
    @DisplayName("Happy path: created through generating to terminal")
    void happyPath() {
        var sm = new RunStateMachine();
        sm.transitionTo(RunPhase.DECIDING);
        sm.transitionTo(RunPhase.ADMITTED);
        sm.transitionTo(RunPhase.GENERATING);
        sm.transitionTo(RunPhase.VALIDATING_TOOLS);
        sm.transitionTo(RunPhase.EXECUTING_TOOLS);
        sm.transitionTo(RunPhase.TERMINAL);
        assertTrue(sm.isTerminal());
        assertEquals(RunPhase.TERMINAL, sm.phase());
    }

    @Test
    @DisplayName("A terminal run cannot start new work")
    void terminalCannotRestart() {
        var sm = new RunStateMachine();
        sm.transitionTo(RunPhase.DECIDING);
        sm.transitionTo(RunPhase.TERMINAL);
        assertThrows(RunStateException.class, () -> sm.transitionTo(RunPhase.DECIDING));
        assertThrows(RunStateException.class, () -> sm.transitionTo(RunPhase.GENERATING));
    }

    @Test
    @DisplayName("Skipping admission is illegal (privacy gate order enforced)")
    void cannotSkipAdmission() {
        var sm = new RunStateMachine();
        sm.transitionTo(RunPhase.DECIDING);
        assertThrows(RunStateException.class, () -> sm.transitionTo(RunPhase.GENERATING));
    }

    @Test
    @DisplayName("Compaction returns to deciding (reroute on compacted view)")
    void compactionLoopsToDeciding() {
        var sm = new RunStateMachine();
        sm.transitionTo(RunPhase.DECIDING);
        sm.transitionTo(RunPhase.ADMITTED);
        sm.transitionTo(RunPhase.GENERATING);
        sm.transitionTo(RunPhase.COMPACTING);
        sm.transitionTo(RunPhase.DECIDING);
        assertEquals(RunPhase.DECIDING, sm.phase());
    }
}
