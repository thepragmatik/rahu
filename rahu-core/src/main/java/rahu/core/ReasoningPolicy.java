package rahu.core;

import java.util.Objects;

/**
 * Reasoning policy v1 (routing.md): provider default (omit controls — distinct from
 * none), an explicit supported effort, or disabled reasoning when supported.
 */
public sealed interface ReasoningPolicy permits ReasoningPolicy.ProviderDefault,
    ReasoningPolicy.ExplicitEffort, ReasoningPolicy.Disabled {

    /** Omit effort controls and accept the documented provider default. */
    record ProviderDefault() implements ReasoningPolicy {

        public static final ProviderDefault INSTANCE = new ProviderDefault();
    }

    /** An explicit effort from the supported vocabulary. */
    record ExplicitEffort(Effort effort) implements ReasoningPolicy {
        public ExplicitEffort {
            Objects.requireNonNull(effort, "effort");
        }

        /** Named factory for readable construction in configs/tests. */
        public static ExplicitEffort of(Effort effort) {
            return new ExplicitEffort(effort);
        }
    }

    /** Request disabled reasoning (distinct from provider default). */
    record Disabled() implements ReasoningPolicy {

        public static final Disabled INSTANCE = new Disabled();
    }

    /** Effort vocabulary v1 (routing.md). */
    enum Effort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }
}
