package rahu.core.routing;

import java.util.Objects;
import java.util.Set;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/**
 * One configured pool entry (configuration.md pools): alias, model ID, allowed
 * reasoning policies. Efforts cannot be an empty list.
 */
public record PoolModel(String alias, ModelRef model, Set<ReasoningPolicy> allowedReasoning) {

    public PoolModel {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(allowedReasoning, "allowedReasoning");
        if (allowedReasoning.isEmpty()) {
            throw new IllegalArgumentException("efforts cannot be an empty list (configuration.md)");
        }
    }
}
