package rahu.core.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A batched turn profile; every failure path degrades, never widens. */
class TurnProfileTest {

    @Test
    @DisplayName("A confident classification and relevance set are carried through")
    void happyPath() {
        var profile = TurnProfile.from(
            Optional.of(new DecisionResult.ValidChoice("taskClass", "coding",
                Map.of("answer", 0.05, "coding", 0.9, "analysis", 0.02,
                       "classification", 0.01, "summarisation", 0.01, "unknown", 0.01),
                Optional.of(0.9), "concentration")),
            Set.of("workspace.read"),
            List.of("workspace.read", "workspace.search"),
            Optional.of(new DecisionResult.ValidNoul("tool:workspace.read", true, Optional.of(0.8))));

        assertEquals(TaskClass.CODING, profile.taskClass());
        assertEquals(Set.of("workspace.read"), profile.relevantTools());
        assertEquals(false, profile.degraded());
    }

    @Test
    @DisplayName("A failed classification degrades to UNKNOWN and never widens tools")
    void failureDegradesClosed() {
        var profile = TurnProfile.from(
            Optional.of(new DecisionResult.Failure(
                DecisionResult.FailureKind.TIMEOUT, "decision transport failed")),
            Set.of(),
            List.of("workspace.read", "workspace.search"),
            Optional.empty());

        assertEquals(TaskClass.UNKNOWN, profile.taskClass());
        assertTrue(profile.degraded());
        assertEquals(Set.of("workspace.read", "workspace.search"), profile.relevantTools(),
            "a failed relevance judgment exposes the permitted read-only set, never fewer");
    }
}
