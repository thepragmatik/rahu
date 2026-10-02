package rahu.core.decision;

import java.util.Locale;

/**
 * Classification labels v1 (systemone.md). UNKNOWN is the fail-closed default: a failed or
 * unparseable classification must never lower trusted capability requirements.
 */
public enum TaskClass {
    ANSWER, CODING, ANALYSIS, CLASSIFICATION, SUMMARISATION, UNKNOWN;

    /** Parses a provider label; anything unrecognised becomes UNKNOWN. */
    public static TaskClass fromLabel(String label) {
        if (label == null) {
            return UNKNOWN;
        }
        String normalised = label.trim().toLowerCase(Locale.ROOT);
        for (TaskClass candidate : values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(normalised)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /** False only for UNKNOWN. Callers use this to decide whether to trust narrowing. */
    public boolean isConfident() {
        return this != UNKNOWN;
    }
}
