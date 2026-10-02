package rahu.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Capability/pricing evidence for one model (ARCHITECTURE.md ModelProfile).
 * Null optionals mean "unknown", which refuses paid admission by default
 * (routing.md candidate generation step 5).
 */
public record ModelProfile(
    ModelRef model,
    Integer contextTokens,
    Integer maxOutputTokens,
    MoneyAmount inputUsdPerToken,
    MoneyAmount outputUsdPerToken,
    ReasoningPolicy.Effort[] supportedEfforts,
    boolean mandatoryReasoning,
    Instant fetchedAt,
    boolean evidenceFresh,
    boolean toolSupport) {

    public ModelProfile {
        Objects.requireNonNull(model, "model");
        if (contextTokens != null && contextTokens <= 0) {
            throw new IllegalArgumentException("contextTokens must be positive when present");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive when present");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt required");
        }
        supportedEfforts = supportedEfforts == null
            ? new ReasoningPolicy.Effort[0]
            : supportedEfforts.clone();
    }

    public Optional<Integer> contextTokensOpt() {
        return Optional.ofNullable(contextTokens);
    }

    public Optional<MoneyAmount> inputPrice() {
        return Optional.ofNullable(inputUsdPerToken);
    }

    public Optional<MoneyAmount> outputPrice() {
        return Optional.ofNullable(outputUsdPerToken);
    }
}
