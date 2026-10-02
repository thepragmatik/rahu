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

    /** Records a completed batch; returns true when NO_PROGRESS must fire. */
    public boolean recordBatch(List<ToolCall> batch, String observationDigest) {
        String fingerprint = fingerprint(batch, observationDigest);
        if (fingerprint.equals(lastFingerprint)) {
            streak++;
        } else {
            lastFingerprint = fingerprint;
            streak = 1;
        }
        return streak >= TRIGGER_AFTER;
    }

    /** A new user turn resets the streak (different new observation does too). */
    public void newTurn() {
        lastFingerprint = null;
        streak = 0;
    }

    public int currentStreak() {
        return streak;
    }

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
