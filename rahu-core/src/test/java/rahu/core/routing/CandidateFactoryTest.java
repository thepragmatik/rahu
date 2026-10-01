package rahu.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;

/** S02 candidate generation invariants (routing.md; A04, A05, A28-generation). */
class CandidateFactoryTest {

    private static ModelProfile profile(ModelRef ref, Integer context, boolean tools,
        ReasoningPolicy.Effort[] efforts, boolean mandatory) {
        return new ModelProfile(ref, context, 4096,
            new MoneyAmount(new java.math.BigDecimal("0.000001"), rahu.core.CurrencyUnit.USD),
            new MoneyAmount(new java.math.BigDecimal("0.000002"), rahu.core.CurrencyUnit.USD),
            efforts, mandatory, Instant.parse("2026-10-01T00:00:00Z"), true, tools);
    }

    @Test
    @DisplayName("A04: only supported efforts survive; mandatory reasoning excludes none")
    void neverSelectsUnsupportedEffort() {
        var model = new PoolModel("fast", new ModelRef("demo-fast"), Set.of(
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW),
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.MEDIUM)));
        var profiles = Map.of(new ModelRef("demo-fast"), profile(
            new ModelRef("demo-fast"), 8000, true,
            new ReasoningPolicy.Effort[] { ReasoningPolicy.Effort.LOW,
                ReasoningPolicy.Effort.HIGH }, false));
        var factory = new CandidateFactory();

        CandidateSet set = factory.generate(new ModelPool("demo", List.of(model)), profiles,
            new OperationRequirements(false, false, 2000, false), "cat-sha", "cfg-sha");

        assertEquals(List.of("fast@low"), set.candidates().stream().map(
            rahu.core.ExecutionCandidate::id).toList());
        assertTrue(set.exclusions().stream().anyMatch(e ->
            e.reason().equals("effort-unsupported") && e.ref().equals("fast@medium")));
    }

    @Test
    @DisplayName("A04: mandatory-reasoning models reject none/disabled policies")
    void mandatoryReasoningExcludesNone() {
        var model = new PoolModel("m", new ModelRef("demo-m"), Set.of(
            ReasoningPolicy.Disabled.INSTANCE,
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW)));
        var profiles = Map.of(new ModelRef("demo-m"), profile(
            new ModelRef("demo-m"), 8000, true,
            new ReasoningPolicy.Effort[] { ReasoningPolicy.Effort.LOW,
                ReasoningPolicy.Effort.HIGH }, true));
        var factory = new CandidateFactory();

        CandidateSet set = factory.generate(new ModelPool("demo", List.of(model)), profiles,
            new OperationRequirements(false, false, 2000, false), "cat-sha", "cfg-sha");

        assertEquals(List.of("m@low"), set.candidates().stream().map(
            rahu.core.ExecutionCandidate::id).toList());
        assertTrue(set.exclusions().stream().anyMatch(e ->
            e.reason().equals("mandatory-reasoning") && e.ref().equals("m@none")));
    }

    @Test
    @DisplayName("A05: tool-required operations exclude tool-less models before selection")
    void toolSupportRequiredExclusions() {
        var toolless = new PoolModel("b", new ModelRef("demo-b"), Set.of(
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW)));
        var profiles = Map.of(new ModelRef("demo-b"), profile(
            new ModelRef("demo-b"), 8000, false,
            new ReasoningPolicy.Effort[] { ReasoningPolicy.Effort.LOW }, false));
        var factory = new CandidateFactory();

        CandidateSet set = factory.generate(new ModelPool("demo", List.of(toolless)), profiles,
            new OperationRequirements(true, true, 2000, false), "cat-sha", "cfg-sha");

        assertTrue(set.candidates().isEmpty());
        assertTrue(set.exclusions().stream().anyMatch(e ->
            e.reason().equals("no-tool-support") && e.ref().equals("b@low")));
    }

    @Test
    @DisplayName("Unknown context/pricing evidence refuses paid admission under default policy")
    void unknownEvidenceExcludesPaidCandidate() {
        var model = new PoolModel("u", new ModelRef("demo-u"), Set.of(
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW)));
        var bare = new ModelProfile(new ModelRef("demo-u"), null, null, null, null,
            new ReasoningPolicy.Effort[] { ReasoningPolicy.Effort.LOW }, false,
            Instant.parse("2026-10-01T00:00:00Z"), true, true);
        var factory = new CandidateFactory();

        CandidateSet set = factory.generate(new ModelPool("demo", List.of(model)),
            Map.of(new ModelRef("demo-u"), bare),
            new OperationRequirements(false, false, 2000, false), "cat-sha", "cfg-sha");

        assertTrue(set.candidates().isEmpty());
        assertTrue(set.exclusions().stream().anyMatch(e ->
            e.reason().equals("unknown-evidence") && e.ref().equals("u@low")));
    }

    @Test
    @DisplayName("Oversized candidate sets are rejected explicitly, never truncated")
    void oversizedPoolRejected() {
        var models = new java.util.ArrayList<PoolModel>();
        var profiles = new java.util.HashMap<ModelRef, ModelProfile>();
        for (int i = 0; i < 40; i++) {
            String alias = "m" + i;
            var ref = new ModelRef("id-" + i);
            models.add(new PoolModel(alias, ref, Set.of(
                ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW),
                ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.MEDIUM))));
            profiles.put(ref, profile(ref, 8000, true,
                new ReasoningPolicy.Effort[] { ReasoningPolicy.Effort.LOW,
                    ReasoningPolicy.Effort.MEDIUM }, false));
        }
        var factory = new CandidateFactory();

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.generate(
            new ModelPool("big", models), profiles,
            new OperationRequirements(false, false, 2000, false), "cat-sha", "cfg-sha"));
        assertTrue(ex.getMessage().contains("narrow the pool"));
    }
}
