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

    /**
     * RED, from a real live G09 dogfood turn on 2026-10-03: the model re-issued
     * ONE failing read (`workspace.read bin/AGENT.md`, a path that does not
     * exist) 14 times in a row. Each turn's batch also varied, so the
     * batch-level fingerprint never matched itself and the streak reset every
     * turn. Three IDENTICAL batches never occurred, so the guard could not fire
     * and the loop ran to the generic step cap, spending real tokens on a
     * request that could never succeed.
     *
     * A repeated identical CALL is no progress even when the surrounding batch
     * differs, and it is the shape a stuck model actually produces.
     */
    @Test
    @DisplayName("A repeated identical call terminates even when the batch around it varies")
    void repeatedCallInVaryingBatchTerminates() {
        var detector = new NoProgressDetector();
        // Same stuck call every turn; the other entry differs each turn.
        var batch1 = List.of(
            new ToolCall("c1", "workspace.read", "{\"path\":\"bin/AGENT.md\"}"),
            new ToolCall("c2", "workspace.list", "{\"depth\":0}"));
        var batch2 = List.of(
            new ToolCall("c3", "workspace.read", "{\"path\":\"bin/AGENT.md\"}"),
            new ToolCall("c4", "workspace.search", "{\"q\":\"agent\"}"));
        var batch3 = List.of(
            new ToolCall("c5", "workspace.read", "{\"path\":\"bin/AGENT.md\"}"),
            new ToolCall("c6", "workspace.list", "{\"depth\":1}"));
        // The stuck call keeps the SAME per-call outcome; the batch digest moves.
        java.util.Map<String, String> stuck =
            java.util.Map.of("c1", "ENOENT", "c3", "ENOENT", "c5", "ENOENT");
        var d1 = observation("turn-1");
        var d2 = observation("turn-2");
        var d3 = observation("turn-3");

        assertFalse(detector.recordBatch(batch1, stuck, d1), "first turn is progress");
        assertFalse(detector.recordBatch(batch2, stuck, d2), "second turn is progress");
        assertTrue(detector.recordBatch(batch3, stuck, d3),
            "third turn re-issues the identical failing call - this is NO_PROGRESS "
                + "and must terminate instead of running to the step cap");
    }

    /**
     * RED from the SECOND live attempt: the first fix keyed the per-call streak
     * on the batch observation digest, which covers the whole turn and changes
     * every turn, so a stuck `workspace.list` never accumulated a streak. The
     * loop ran 6 identical calls and only stopped at the generic step cap.
     * The per-call guard must not depend on a digest that always changes.
     */
    @Test
    @DisplayName("A stuck call terminates even when every turn's observation digest differs")
    void repeatedCallWithChangingDigestsTerminates() {
        var detector = new NoProgressDetector();
        for (int turn = 1; turn <= 3; turn++) {
            var batch = List.of(
                new ToolCall("x" + turn, "workspace.list", "{\"depth\":0,\"directory\":\"\"}"),
                new ToolCall("y" + turn, "workspace.search", "{\"q\":\"q" + turn + "\"}"));
            var digests = java.util.Map.of("x" + turn, "LIST-OK");
            boolean fired = detector.recordBatch(batch, digests, observation("turn-" + turn));
            if (turn < 3) {
                assertFalse(fired, "turn " + turn + " is still progress");
            } else {
                assertTrue(fired, "third identical call must terminate regardless of digest");
            }
        }
    }
}
