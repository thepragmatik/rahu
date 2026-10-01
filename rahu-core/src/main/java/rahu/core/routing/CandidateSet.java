package rahu.core.routing;

import java.util.List;
import java.util.Objects;

/**
 * The exact feasible candidate set plus exclusion records (routing.md). Everything
 * downstream selects from here and only here.
 */
public record CandidateSet(List<rahu.core.ExecutionCandidate> candidates,
    List<Exclusion> exclusions) {

    public CandidateSet {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(exclusions, "exclusions");
        candidates = List.copyOf(candidates);
        exclusions = List.copyOf(exclusions);
    }

    /** Why a pool model/policy combination was excluded, with its stable ref. */
    public record Exclusion(String ref, String reason) {

        public Exclusion {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
