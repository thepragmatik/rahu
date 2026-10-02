package rahu.core.runtime;

import rahu.core.tools.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import rahu.core.model.ToolCall;

/**
 * Exact repeated-batch NO_PROGRESS detector (orchestration.md). Fingerprint =
 * ordered tool names/versions + canonical arguments (call IDs excluded) +
 * observation outcome hash. Three identical completed batches in one turn
 * terminate; a different observation, different arguments or a new user turn
 * resets. Compaction cannot erase this state (it lives outside context).
 */
public final class NoProgressDetector {

    private static final int TRIGGER_AFTER = 3;
    private String lastFingerprint;
    private int streak;

    /**
     * Per-call repetition, tracked SEPARATELY from the batch streak. Found by a
     * live dogfood turn: a model re-issued one failing read 14 times while the
     * surrounding batch varied, so the batch fingerprint never matched itself
     * and the streak reset every turn. A single call repeated with the same
     * outcome is no progress regardless of what it is batched with, and that
     * is the shape a stuck model actually produces.
     */
    private final java.util.Map<String, Integer> callRepeats = new java.util.HashMap<>();

    /**
     * Records a completed batch; returns true when NO_PROGRESS must fire.
     *
     * <p>Callers that can attribute outcomes per call SHOULD use
     * {@link #recordBatch(List, java.util.Map)}: the batch digest covers the
     * whole turn, so a single stuck call hidden inside a varying batch never
     * repeats a batch fingerprint and the loop runs to the step cap.
     */
    public boolean recordBatch(List<ToolCall> batch, String observationDigest) {
        return recordBatch(batch, java.util.Map.of(), observationDigest);
    }

    /**
     * Records a completed batch with a per-call outcome digest keyed by call ID.
     * A call with no entry falls back to the batch digest.
     */
    public boolean recordBatch(List<ToolCall> batch,
            java.util.Map<String, String> callDigests, String observationDigest) {
        this.perCallDigests = callDigests == null ? java.util.Map.of() : callDigests;
        this.batchDigest = observationDigest;
        String fingerprint = fingerprint(batch, observationDigest);
        if (fingerprint.equals(lastFingerprint)) {
            streak++;
        } else {
            lastFingerprint = fingerprint;
            streak = 1;
        }
        if (streak >= TRIGGER_AFTER) {
            return true;
        }
        // Per-call guard: any single call repeated across this many turns is a
        // stuck loop, whatever else was batched with it. A false positive stops
        // the loop early and costs one turn; a false negative spends real tokens
        // to the step cap, so this errs toward terminating.
        int repeats = 0;
        for (ToolCall call : batch) {
            // Keyed on call identity PLUS that call's OWN outcome. Keying on the
            // whole-batch digest defeated the guard (the digest changes every
            // turn, so a stuck call never accumulated a streak); keying on
            // identity alone broke the "a changed observation is real progress"
            // rule. Only a per-call outcome satisfies both.
            String key = call.name() + ':' + canonical(call.argumentsJson())
                + '@' + callDigest(call);
            int n = callRepeats.merge(key, 1, Integer::sum);
            repeats = Math.max(repeats, n);
        }
        // Drop keys not seen this batch so a long turn cannot accumulate forever.
        java.util.Set<String> current = new java.util.HashSet<>();
        for (ToolCall call : batch) {
            current.add(call.name() + ':' + canonical(call.argumentsJson())
                + '@' + callDigest(call));
        }
        callRepeats.keySet().retainAll(current);
        return repeats >= TRIGGER_AFTER;
    }

    /** A new user turn resets the streak (different new observation does too). */
    public void newTurn() {
        lastFingerprint = null;
        streak = 0;
        callRepeats.clear();
    }

    public int currentStreak() {
        return streak;
    }

    /**
     * A call with no attributed outcome falls back to the BATCH digest, which is
     * what the legacy 2-arg path supplies. Falling back to "" instead would key
     * on identity alone and break the rule that a changed observation is real
     * progress.
     */
    private String callDigest(ToolCall call) {
        String d = perCallDigests == null ? null : perCallDigests.get(call.id());
        return d == null ? (batchDigest == null ? "" : batchDigest) : d;
    }

    private java.util.Map<String, String> perCallDigests = java.util.Map.of();
    private String batchDigest = "";

    private static String fingerprint(List<ToolCall> batch, String observationDigest) {
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : batch) {
            sb.append(call.name()).append(':').append(canonical(call.argumentsJson()))
                .append(';');
        }
        sb.append('|').append(observationDigest == null ? "" : observationDigest);
        return sha256(sb.toString());
    }

    /** Canonical argument JSON: sorted keys, no whitespace, call IDs excluded. */
    private static String canonical(String argumentsJson) {
        return CanonicalJson.canonicalize(argumentsJson);
    }

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
