package rahu.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
