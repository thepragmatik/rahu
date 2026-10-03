package rahu.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import rahu.core.decision.DecisionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.ExecutionCandidate;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/** S02 route resolution invariants (routing.md decision table; A03, A06, A07). */
class RouteResolverTest {

    private static ExecutionCandidate candidate(String alias, ReasoningPolicy.Effort effort) {
        return new ExecutionCandidate(alias + "@" + effort.name().toLowerCase(),
            new ModelRef("id-" + alias),
            ReasoningPolicy.ExplicitEffort.of(effort), Map.of(), "cat-sha", "cfg-sha");
    }

    private static RouteResolver.ResolutionInput input(CandidateSet set, String baseline,
        String fallback) {
        return new RouteResolver.ResolutionInput(set, Optional.of(baseline),
            Optional.of(fallback), "chosen_probability", 0.65);
    }

    // ==================================================================
    // AUDIT-e / F-11: `confidenceField` was validated, plumbed, traced and
    // replayed - and never read. The floor was always applied to the chosen
    // probability whatever the operator configured. routing.md:26: "Confidence
    // thresholds operate on a named field; default chosen_probability is
    // 0.65." An operator who set `raw_confidence` got no error and no effect.
    // ==================================================================

    @Test
    @DisplayName("a non-finite provider confidence cannot satisfy the floor")
    void nonFiniteConfidenceNeverSatisfiesTheFloor() {
        // Found by MUTATION, not by reading: removing the isFinite filter produced
        // ZERO failing tests, and I nearly filed that as an equivalent mutant.
        // It is not. Double.POSITIVE_INFINITY >= 0.65 is TRUE, so without the
        // filter a provider returning an infinite raw_confidence would sail past
        // the concentration gate - the exact opposite of what the gate is for.
        // NaN fails the comparison anyway, which is why only +inf distinguishes them.
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var in = new RouteResolver.ResolutionInput(set, Optional.of("a@low"),
            Optional.of("b@low"), "raw_confidence", 0.65);

        for (double bad : new double[] {Double.POSITIVE_INFINITY, Double.NaN}) {
            var valid = new DecisionResult.ValidChoice("q", "a@low",
                Map.of("a@low", 0.9, "b@low", 0.1), Optional.of(bad), "provider_score");
            assertEquals(Optional.empty(),
                new RouteResolver().resolve(RoutingMode.ACTIVE, Optional.of(valid), in)
                    .suggestedId(),
                "a non-finite confidence (" + bad + ") must not pass the floor: "
                    + "+inf compares >= any threshold, so the isFinite filter is load-bearing");
        }
    }

    @Test
    @DisplayName("routing.md:26 - the floor applies to the NAMED confidence field")
    void confidenceFloorReadsTheNamedField() {
        // chosen_probability 0.50 fails a 0.65 floor, but raw_confidence 0.90
        // passes. With the field ignored, configuring `raw_confidence` had no
        // effect at all and this decision was wrongly rejected.
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        // rawConfidence 0.9 is a PROVIDER value, deliberately kept separate
        // from the chosen probability - routing.md:26 requires them not be
        // conflated, which is the whole reason the field is named.
        var valid = new DecisionResult.ValidChoice("q", "a@low",
            Map.of("a@low", 0.5, "b@low", 0.5), Optional.of(0.9), "provider_score");

        var in = new RouteResolver.ResolutionInput(set, Optional.of("a@low"),
            Optional.of("b@low"), "raw_confidence", 0.65);

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), in);

        assertEquals(Optional.of("a@low"), r.suggestedId(),
            "the floor must be evaluated against the field the operator NAMED, "
                + "not always against chosen_probability: " + r);
        assertEquals(Optional.of("a@low"), r.executedId(), "and the suggestion runs");
        assertTrue(!r.degraded(), "not a degraded decision: " + r);
    }

    @Test
    @DisplayName("the default field stays chosen_probability (routing.md:26)")
    void defaultFieldIsChosenProbability() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        // chosen 0.5 is below the floor but raw_confidence 0.99 is far above it.
        // A run relying on the default MUST still be gated on the probability.
        var valid = new DecisionResult.ValidChoice("q", "a@low",
            Map.of("a@low", 0.5, "b@low", 0.5), Optional.of(0.99), "provider_score");

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), input(set, "a@low", "b@low"));

        assertEquals(Optional.empty(), r.suggestedId(),
            "chosen_probability 0.5 must NOT be rescued by a high raw_confidence: "
                + r);
        assertTrue(r.degraded(), "a rejected decision is degraded: " + r);
    }

    @Test
    @DisplayName("a named field the decision does not carry is rejected, not assumed")
    void missingNamedFieldIsRejected() {
        // Named a field the decision does not provide. Assuming a value would
        // invent evidence - the AUDIT-f shape. There is no number to compare, so
        // the gate cannot pass and the decision degrades.
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var valid = new DecisionResult.ValidChoice("q", "a@low",
            Map.of("a@low", 0.95, "b@low", 0.05), Optional.empty(), "none");

        var in = new RouteResolver.ResolutionInput(set, Optional.of("a@low"),
            Optional.of("b@low"), "raw_confidence", 0.65);

        // Built INSIDE the assertion: constructing the input is what throws, since
        // an unknown field name is a load-time configuration error. Building it out
        // here failed the test before the assertion could say anything.
        assertThrows(IllegalArgumentException.class,
            () -> new RouteResolver.ResolutionInput(set, Optional.of("a@low"),
                Optional.of("b@low"), "no_such_field", 0.65),
            "an unknown confidence field must be refused loudly: silently "
                + "falling back to the default would make the setting a lie again");
        assertEquals(Optional.empty(),
            new RouteResolver().resolve(RoutingMode.ACTIVE, Optional.of(valid), in).suggestedId(),
            "absent raw_confidence must not satisfy the floor");
    }

    @Test
    @DisplayName("A03: a suggestion outside the submitted set is rejected; fallback runs")
    void routesOnlyAmongConfiguredCandidates() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var valid = DecisionResultFixtures.validChoice("c@low", Map.of("a@low", 0.9, "b@low", 0.1));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), input(set, "a@low", "b@low"));

        assertEquals(Optional.of("b@low"), r.executedId());
        assertEquals(Optional.empty(), r.suggestedId());
        assertEquals(Optional.of("decision-rejected"), r.fallbackCause());
        assertTrue(r.terminalReason().isEmpty());
    }

    @Test
    @DisplayName("A07 shadow: suggestion is recorded, baseline is executed")
    void shadowRecordsSuggestionExecutesBaseline() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.MEDIUM);
        var set = new CandidateSet(List.of(a, b), List.of());
        var valid = DecisionResultFixtures.validChoice("b@medium",
            Map.of("a@low", 0.2, "b@medium", 0.8));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.SHADOW,
            Optional.of(valid), input(set, "a@low", "b@medium"));

        assertEquals(Optional.of("b@medium"), r.suggestedId());
        assertEquals(Optional.of("a@low"), r.executedId());
        assertEquals(RoutingMode.SHADOW, r.mode());
        assertTrue(r.terminalReason().isEmpty());
    }

    @Test
    @DisplayName("A07 active: admissible suggestion is executed")
    void activeExecutesAdmissibleSuggestion() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.MEDIUM);
        var set = new CandidateSet(List.of(a, b), List.of());
        var valid = DecisionResultFixtures.validChoice("b@medium",
            Map.of("a@low", 0.2, "b@medium", 0.8));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), input(set, "a@low", "b@medium"));

        assertEquals(Optional.of("b@medium"), r.executedId());
    }

    @Test
    @DisplayName("A06: malformed distribution is a typed failure, never a repaired choice")
    void malformedDistributionFailsTyped() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var malformed = DecisionResultFixtures.validChoice("b@low",
            Map.of("a@low", 0.6, "b@low", 0.6));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(malformed), input(set, "a@low", "b@low"));

        assertEquals(Optional.of("b@low"), r.executedId());
        assertEquals(Optional.of("decision-rejected"), r.fallbackCause());
    }

    @Test
    @DisplayName("Low concentration below the floor uses the fallback with uncertainty")
    void lowConcentrationUsesFallback() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var valid = DecisionResultFixtures.validChoice("a@low",
            Map.of("a@low", 0.5, "b@low", 0.5));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), input(set, "b@low", "b@low"));

        assertEquals(Optional.of("b@low"), r.executedId());
        assertTrue(r.degraded());
    }

    @Test
    @DisplayName("Timeout/protocol failure degrades to fallback")
    void timeoutDegradesToFallback() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var b = candidate("b", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a, b), List.of());
        var failed = new rahu.core.decision.DecisionResult.Failure(
            rahu.core.decision.DecisionResult.FailureKind.TIMEOUT, "decision timeout");

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(failed), input(set, "a@low", "b@low"));

        assertEquals(Optional.of("b@low"), r.executedId());
        assertTrue(r.degraded());
        assertEquals(Optional.of("decision-failed"), r.fallbackCause());
    }

    @Test
    @DisplayName("No feasible fallback terminates with NO_FEASIBLE_ROUTE before any call")
    void noFeasibleFallbackTerminates() {
        var a = candidate("a", ReasoningPolicy.Effort.LOW);
        var set = new CandidateSet(List.of(a), List.of());
        var valid = DecisionResultFixtures.validChoice("a@low", Map.of("a@low", 0.95));

        RouteResolution r = new RouteResolver().resolve(RoutingMode.ACTIVE,
            Optional.of(valid), new RouteResolver.ResolutionInput(set, Optional.empty(),
                Optional.empty(), "chosen_probability", 0.65));

        assertTrue(r.executedId().isEmpty());
        assertEquals(Optional.of(TerminalReason.NO_FEASIBLE_ROUTE), r.terminalReason());
    }

    @Test
    @DisplayName("Invariant sweep: executed is always within the feasible set")
    void executedAlwaysWithinFeasibleSet() {
        var resolver = new RouteResolver();
        java.util.Random random = new java.util.Random(42);
        for (int trial = 0; trial < 200; trial++) {
            int size = 1 + random.nextInt(4);
            var list = new java.util.ArrayList<ExecutionCandidate>();
            for (int i = 0; i < size; i++) {
                list.add(candidate("m" + i, ReasoningPolicy.Effort.LOW));
            }
            var set = new CandidateSet(list, List.of());
            String suggested = list.get(random.nextInt(size)).id();
            var probabilities = new java.util.LinkedHashMap<String, Double>();
            double remaining = 1.0;
            for (int i = 0; i < size; i++) {
                double share = i == size - 1 ? remaining : remaining * random.nextDouble();
                probabilities.put(list.get(i).id(), share);
                remaining -= share;
            }
            Optional<rahu.core.decision.DecisionResult> result =
                random.nextBoolean() ? Optional.of(DecisionResultFixtures.validChoice(
                    suggested, probabilities)) : Optional.of(
                    new rahu.core.decision.DecisionResult.Failure(
                        rahu.core.decision.DecisionResult.FailureKind.TIMEOUT, "t"));
            var baseline = list.get(random.nextInt(size)).id();
            var fallback = list.get(random.nextInt(size)).id();

            RouteResolution r = resolver.resolve(
                random.nextBoolean() ? RoutingMode.ACTIVE : RoutingMode.SHADOW,
                result, input(set, baseline, fallback));

            final int trialNumber = trial;
            r.executedId().ifPresent(executed -> assertTrue(
                set.candidates().stream().anyMatch(c -> c.id().equals(executed)),
                "executed " + executed + " outside feasible set, trial " + trialNumber));
        }
    }
}
