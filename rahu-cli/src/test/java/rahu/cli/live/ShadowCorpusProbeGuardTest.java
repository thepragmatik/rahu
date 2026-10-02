package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The container guard on the injection corpus probe is a SAFETY control, not a
 * convenience, so it gets the same treatment as any other security boundary here: a test
 * that fails if the control is removed.
 *
 * <p>Operator guardrail: adversarial samples are consolidated in a fixture and executed
 * only inside a disposable container, never on a developer or production host.
 *
 * <p>The guard is asserted through {@link ShadowCorpusProbe#mayRunHere()} rather than
 * {@code main}, because main's refusal path calls System.exit and would kill this JVM
 * instead of failing an assertion.
 */
class ShadowCorpusProbeGuardTest {

    @Test
    @DisplayName("The probe is blocked when there is no evidence of a container")
    void blockedOutsideAContainer() {
        assertFalse(ShadowCorpusProbe.runningInAContainer(),
            "the test JVM must not be containerised for this assertion to mean anything");
        assertFalse(ShadowCorpusProbe.mayRunHere(),
            "the corpus must not be runnable on a host with no container evidence");
    }

    @Test
    @DisplayName("The refusal names the container batch and says why it is blocked")
    void refusalIsActionable() {
        String refusal = ShadowCorpusProbe.refusalMessage();
        assertTrue(refusal.contains("REFUSING TO RUN"),
            "the guard must state the refusal explicitly, got: " + refusal);
        assertTrue(refusal.contains("run-injection-corpus.sh"),
            "the refusal must point at the supported container batch, got: " + refusal);
        assertTrue(refusal.contains("adversarial"),
            "the refusal must say what it is protecting against, got: " + refusal);
    }
}