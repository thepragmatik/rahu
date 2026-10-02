package rahu.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Provider usage (openrouter.md): reported token categories; absent fields are
 * unknown, never zero (A10).
 */
public record Usage(
    Integer promptTokens,
    Integer completionTokens,
    Integer reasoningTokens,
    Long totalCostMicros) {

    public Optional<Integer> promptTokensOpt() {
        return Optional.ofNullable(promptTokens);
    }

    public Optional<Integer> completionTokensOpt() {
        return Optional.ofNullable(completionTokens);
    }

    public Optional<Integer> reasoningTokensOpt() {
        return Optional.ofNullable(reasoningTokens);
    }

    public Optional<Long> totalCostMicrosOpt() {
        return Optional.ofNullable(totalCostMicros);
    }
}
