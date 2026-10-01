package rahu.core;

import java.util.Objects;

/** Provider-neutral model reference (ARCHITECTURE.md ModelRef); adapter resolves identity. */
public record ModelRef(String providerNeutralId) {

    public ModelRef {
        Objects.requireNonNull(providerNeutralId, "providerNeutralId");
        if (providerNeutralId.isBlank()) {
            throw new IllegalArgumentException("providerNeutralId must not be blank");
        }
    }
}
