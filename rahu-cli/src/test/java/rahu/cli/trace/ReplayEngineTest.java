package rahu.cli.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.cli.trace.ReplayOutcome.ReplayStatus;
import rahu.core.routing.RoutingMode;

/**
 * A11: offline deterministic policy replay — recorded inputs drive pure
 * routing/state policies via fakes; zero network, zero tool effects.
 */
class ReplayEngineTest {

    @Test
    @DisplayName("Replay with captured decision outcomes reproduces the policy deterministically")
    void deterministicPolicyReplay() {
        // Captured: a decision suggesting quality@medium over candidates fast@low /
        // quality@medium, with active mode. The policy must re-derive the same
        // executed route from the recorded distribution.
        var candidates = List.of(
            new rahu.core.ExecutionCandidate("fast@low",
                new rahu.core.ModelRef("id-fast"),
                rahu.core.ReasoningPolicy.ExplicitEffort.of(rahu.core.ReasoningPolicy.Effort.LOW),
                java.util.Map.of(), "cat", "cfg"),
            new rahu.core.ExecutionCandidate("quality@medium",
                new rahu.core.ModelRef("id-quality"),
                rahu.core.ReasoningPolicy.ExplicitEffort.of(rahu.core.ReasoningPolicy.Effort.MEDIUM),
                java.util.Map.of(), "cat", "cfg"));
        var set = new rahu.core.routing.CandidateSet(candidates, List.of());
        var recorded = new DecisionResultFixtures().validChoice("quality@medium",
            java.util.Map.of("fast@low", 0.2, "quality@medium", 0.8));

        ReplayOutcome first = ReplayEngine.replay(set, RoutingMode.ACTIVE,
            java.util.Optional.of(recorded), "fast@low", "quality@medium");
        ReplayOutcome second = ReplayEngine.replay(set, RoutingMode.ACTIVE,
            java.util.Optional.of(recorded), "fast@low", "quality@medium");

        assertEquals(first, second, "same recorded inputs must replay identically");
        assertEquals(java.util.Optional.of("quality@medium"), first.executedId());
        assertEquals(java.util.Optional.of("quality@medium"), first.suggestedId());
    }

    @Test
    @DisplayName("A11: replay without captured payloads reports unavailable, never fakes")
    void replayUnavailableWithoutPayloads() {
        ReplayOutcome outcome = ReplayEngine.replay(new rahu.core.routing.CandidateSet(
            List.of(), List.of()), RoutingMode.SHADOW, java.util.Optional.empty(),
            null, null);
        assertEquals(ReplayStatus.UNAVAILABLE, outcome.status());
        assertTrue(outcome.unavailableReason().contains("no captured"));
    }

    @Test
    @DisplayName("Replay of a degraded decision records the fallback, not a fabricated success")
    void replayKeepsDegradation() {
        var candidates = List.of(
            new rahu.core.ExecutionCandidate("fast@low",
                new rahu.core.ModelRef("id-fast"),
                rahu.core.ReasoningPolicy.ExplicitEffort.of(rahu.core.ReasoningPolicy.Effort.LOW),
                java.util.Map.of(), "cat", "cfg"));
        var set = new rahu.core.routing.CandidateSet(candidates, List.of());
        var failure = new rahu.core.decision.DecisionResult.Failure(
            rahu.core.decision.DecisionResult.FailureKind.TIMEOUT, "recorded timeout");

        ReplayOutcome outcome = ReplayEngine.replay(set, RoutingMode.ACTIVE,
            java.util.Optional.of(failure), "fast@low", "fast@low");
        assertEquals(ReplayStatus.REPLAYED, outcome.status());
        assertEquals(java.util.Optional.of("fast@low"), outcome.executedId());
        assertTrue(outcome.degraded(), "fallback cause must survive replay");
    }
}
