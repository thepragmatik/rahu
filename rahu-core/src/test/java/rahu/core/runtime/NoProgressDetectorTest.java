package rahu.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.model.ToolCall;

/** A27: exact repeated-batch NO_PROGRESS termination (orchestration.md). */
class NoProgressDetectorTest {

    private static List<ToolCall> batch(String args) {
        return List.of(new ToolCall("call-" + args, "workspace.read", "{\"path\":\"" + args + "\"}"));
    }

    private static String observation(String outcome) {
        return "obs:" + outcome;
    }

    @Test
    @DisplayName("Three identical completed batches terminate; the third triggers")
    void threeIdenticalBatchesTerminate() {
        var detector = new NoProgressDetector();
        assertFalse(detector.recordBatch(batch("a.md"), observation("contents")));
        assertFalse(detector.recordBatch(batch("a.md"), observation("contents")));
        assertTrue(detector.recordBatch(batch("a.md"), observation("contents")),
            "third identical batch must trigger NO_PROGRESS");
    }

    @Test
    @DisplayName("A different observation resets the streak")
    void differentObservationResets() {
        var detector = new NoProgressDetector();
        detector.recordBatch(batch("a.md"), observation("v1"));
        detector.recordBatch(batch("a.md"), observation("v2"));
        assertFalse(detector.recordBatch(batch("a.md"), observation("v3")));
    }

    @Test
    @DisplayName("Different arguments are a different fingerprint")
    void differentArgumentsAreDifferent() {
        var detector = new NoProgressDetector();
        detector.recordBatch(batch("a.md"), observation("x"));
        assertFalse(detector.recordBatch(batch("b.md"), observation("x")));
    }

    @Test
    @DisplayName("New call IDs do not evade the check (call IDs excluded from fingerprint)")
    void newCallIdsDoNotEvade() {
        var detector = new NoProgressDetector();
        assertFalse(detector.recordBatch(
            List.of(new ToolCall("c1", "workspace.read", "{\"p\":1}")), observation("x")));
        assertFalse(detector.recordBatch(
            List.of(new ToolCall("c2", "workspace.read", "{\"p\":1}")), observation("x")));
        assertTrue(detector.recordBatch(
            List.of(new ToolCall("c3", "workspace.read", "{\"p\":1}")), observation("x")),
            "third identical batch fires even with fresh call IDs");
    }

    @Test
    @DisplayName("A new user turn resets the detector")
    void newUserTurnResets() {
        var detector = new NoProgressDetector();
        detector.recordBatch(batch("a.md"), observation("x"));
        detector.recordBatch(batch("a.md"), observation("x"));
        detector.newTurn();
        assertFalse(detector.recordBatch(batch("a.md"), observation("x")));
    }
}
