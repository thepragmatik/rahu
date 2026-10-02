package rahu.core.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import rahu.core.ExecutionCandidate;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/**
 * Constructs the legal candidate set (routing.md candidate generation). IDs are
 * alias@policy, stable and order-independent; oversized sets are an explicit
 * error, never a silent truncation.
 */
public final class CandidateFactory {

    /** configuration.md default; oversized sets are an error, not a truncation. */
    public static final int MAX_CANDIDATES = 32;

    public CandidateSet generate(ModelPool pool, Map<ModelRef, ModelProfile> profiles,
        OperationRequirements requirements, String catalogHash, String configHash) {

        var candidates = new ArrayList<ExecutionCandidate>();
        var exclusions = new ArrayList<CandidateSet.Exclusion>();

        for (PoolModel pm : pool.models()) {
            ModelProfile profile = profiles.get(pm.model());
            for (ReasoningPolicy policy : pm.allowedReasoning()) {
                String ref = pm.alias() + "@" + policyLabel(policy);
                String exclusion = check(policy, profile, requirements);
                if (exclusion != null) {
                    exclusions.add(new CandidateSet.Exclusion(ref, exclusion));
                } else {
                    candidates.add(new ExecutionCandidate(ref, pm.model(), policy, Map.of(),
                        catalogHash, configHash));
                }
            }
        }

        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                "candidate set would be " + candidates.size() + " (max " + MAX_CANDIDATES
                    + "); narrow the pool");
        }
        return new CandidateSet(List.copyOf(candidates), List.copyOf(exclusions));
    }

    private static String policyLabel(ReasoningPolicy policy) {
        if (policy instanceof ReasoningPolicy.ProviderDefault) {
            return "default";
        }
        if (policy instanceof ReasoningPolicy.Disabled) {
            return "none";
        }
        if (policy instanceof ReasoningPolicy.ExplicitEffort e) {
            return e.effort().name().toLowerCase();
        }
        throw new IllegalStateException("unknown policy: " + policy);
    }

    /**
     * Returns null when admissible, else the exclusion reason. Implements
     * routing.md steps 3-5: trusted requirements, evidence freshness, context,
     * tool support, effort intersection.
     */
    private static String check(ReasoningPolicy policy, ModelProfile profile,
        OperationRequirements req) {

        if (isNoReasoning(policy) && profile != null && profile.mandatoryReasoning()) {
            return "mandatory-reasoning"; // A04: none/disabled cannot override evidence
        }
        if (profile == null || !profile.evidenceFresh()
            || profile.inputPrice().isEmpty() || profile.outputPrice().isEmpty()) {
            return "unknown-evidence";
        }
        if (profile.contextTokens() == null
            || profile.contextTokens() < req.contextAllowanceTokens()) {
            return "context-exceeded";
        }
        if (req.toolsExposed() && !profile.toolSupport()) {
            return "no-tool-support";
        }
        if (policy instanceof ReasoningPolicy.ExplicitEffort e
            && !Arrays.asList(profile.supportedEfforts()).contains(e.effort())) {
            return "effort-unsupported";
        }
        return null;
    }

    private static boolean isNoReasoning(ReasoningPolicy policy) {
        return policy instanceof ReasoningPolicy.Disabled
            || (policy instanceof ReasoningPolicy.ExplicitEffort e
                && e.effort() == ReasoningPolicy.Effort.NONE);
    }
}
