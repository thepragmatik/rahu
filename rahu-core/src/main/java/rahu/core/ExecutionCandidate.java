package rahu.core;

import java.util.Map;
import java.util.Objects;

/**
 * One legal execution unit: model + reasoning policy + provider constraints
 * (ARCHITECTURE.md ExecutionCandidate). Candidate IDs are stable alias@policy
 * strings and never depend on list order.
 */
public record ExecutionCandidate(
    String id,
    ModelRef model,
    ReasoningPolicy reasoningPolicy,
    Map<String, String> providerConstraints,
    String catalogHash,
    String configHash) {

    public ExecutionCandidate {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || !id.contains("@")) {
            throw new IllegalArgumentException("candidate id must be alias@policy, got " + id);
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(reasoningPolicy, "reasoningPolicy");
        providerConstraints = Map.copyOf(providerConstraints == null ? Map.of() : providerConstraints);
        Objects.requireNonNull(catalogHash, "catalogHash");
        Objects.requireNonNull(configHash, "configHash");
    }
}
