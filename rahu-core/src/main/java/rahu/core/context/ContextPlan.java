package rahu.core.context;

import java.util.List;
import java.util.Objects;
import rahu.core.model.ChatMessage;

/**
 * Deterministic prompt/projection plan (ARCHITECTURE.md ContextPlan): ordered
 * messages, pinned units, instruction hashes, template version, conservative
 * token estimate against the allowance.
 *
 * <p>{@code estimatedTokens} is the raw estimate and is deliberately NOT clamped
 * to {@code contextAllowanceTokens}, so an input over budget reports an estimate
 * above the allowance rather than exactly the allowance (AUDIT-2026-10-03-r).
 * That is what lets a caller distinguish a comfortable fit from a real overrun.
 * Use {@link #pressure()} for the ratio, and clamp to [0,1] only where a port
 * states that bound.
 */
public record ContextPlan(
    List<ChatMessage> messages,
    List<String> instructionHashes,
    int templateVersion,
    int estimatedTokens,
    int contextAllowanceTokens) {

    public ContextPlan {
        Objects.requireNonNull(messages, "messages");
        if (contextAllowanceTokens <= 0) {
            throw new IllegalArgumentException("contextAllowanceTokens must be positive");
        }
        messages = List.copyOf(messages);
        instructionHashes = instructionHashes == null ? List.of() : List.copyOf(instructionHashes);
    }

    /**
     * Fraction of the allowance this prompt occupies.
     *
     * <p>May exceed 1.0, and that is the point: a ratio pinned at 1.0 reports an
     * unbounded overrun as a full context. {@link rahu.systemone.DecisionEngine.State}
     * requires {@code contextPressure} in [0,1], so a caller passing this across
     * that port must clamp there — the bound belongs to the port, not to the
     * measurement.
     */
    public double pressure() {
        return estimatedTokens / (double) contextAllowanceTokens;
    }

    /** Pressure as the decision port requires it: in [0,1], saturating at 1.0. */
    public double boundedPressure() {
        return Math.min(1.0, pressure());
    }
}
