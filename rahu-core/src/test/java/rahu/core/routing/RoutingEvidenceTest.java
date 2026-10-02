package rahu.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.CurrencyUnit;
import rahu.core.ExecutionCandidate;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;

/**
 * Paid admission is evidence-gated (routing.md step 5): a candidate without
 * price evidence (or with no profile at all) is excluded, never executed;
 * priced, fresh evidence admits the candidate.
 */
class RoutingEvidenceTest {

    private static final ModelRef NEMO = new ModelRef("mistralai/mistral-nemo");

    private static ModelProfile profile(Integer priceSentinel) {
        // priceSentinel != null -> priced evidence; null -> unknown prices.
        MoneyAmount price = priceSentinel == null ? null
            : new MoneyAmount(java.math.BigDecimal.valueOf(priceSentinel), CurrencyUnit.USD);
        return new ModelProfile(NEMO, 131072, 4096, price, price,
            new ReasoningPolicy.Effort[] {ReasoningPolicy.Effort.LOW}, false,
            Instant.parse("2026-10-02T09:00:00Z"), true, true);
    }

    private static CandidateSet generate(Map<ModelRef, ModelProfile> profiles) {
        var pool = new ModelPool("main",
            List.of(new PoolModel("nemo", NEMO,
                new LinkedHashSet<>(List.of((ReasoningPolicy)
                    new ReasoningPolicy.ProviderDefault())))));
        return new CandidateFactory().generate(pool, profiles,
            new OperationRequirements(true, false, 8192, false),
            "catalog-test", "config-test");
    }

    private static Set<String> ids(CandidateSet set) {
        return set.candidates().stream().map(ExecutionCandidate::id)
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("A candidate whose profile has unknown prices is excluded from paid admission")
    void pricelessEvidenceExcluded() {
        CandidateSet set = generate(Map.of(NEMO, profile(null)));

        assertTrue(ids(set).isEmpty(), "no priced evidence, no paid admission");
        assertEquals(List.of("unknown-evidence"),
            set.exclusions().stream().map(CandidateSet.Exclusion::reason).toList());
    }

    @Test
    @DisplayName("A candidate with no profile at all is excluded, never silently executed")
    void missingProfileExcluded() {
        CandidateSet set = generate(Map.of());

        assertTrue(ids(set).isEmpty());
        assertFalse(set.exclusions().isEmpty());
    }

    @Test
    @DisplayName("Priced, fresh evidence admits the candidate")
    void pricedFreshAdmitted() {
        CandidateSet set = generate(Map.of(NEMO, profile(3)));

        assertEquals(Set.of("nemo@default"), ids(set));
        assertTrue(set.exclusions().isEmpty());
    }

    @Test
    @DisplayName("Stale evidence is excluded even when prices are present")
    void staleEvidenceExcluded() {
        var stale = new ModelProfile(NEMO, 131072, 4096,
            new MoneyAmount(java.math.BigDecimal.valueOf(3), CurrencyUnit.USD),
            new MoneyAmount(java.math.BigDecimal.valueOf(6), CurrencyUnit.USD),
            new ReasoningPolicy.Effort[] {ReasoningPolicy.Effort.LOW}, false,
            Instant.parse("2026-10-02T09:00:00Z"), false, true);
        CandidateSet set = generate(Map.of(NEMO, stale));

        assertTrue(ids(set).isEmpty(), "stale evidence refuses paid admission");
    }
}
