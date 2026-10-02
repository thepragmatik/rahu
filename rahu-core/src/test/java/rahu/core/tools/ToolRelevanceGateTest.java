package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Relevance is advisory: it may narrow the permitted set and can never widen it. */
class ToolRelevanceGateTest {

    private static final Set<String> PERMITTED =
        new LinkedHashSet<>(List.of("workspace.list", "workspace.read", "workspace.search"));

    @Test
    @DisplayName("A relevant subset is narrowed to")
    void narrows() {
        assertEquals(Set.of("workspace.read"),
            ToolRelevanceGate.apply(PERMITTED, Set.of("workspace.read")));
    }

    @Test
    @DisplayName("A tool outside the permitted set is rejected, not silently added")
    void cannotWiden() {
        assertThrows(IllegalArgumentException.class,
            () -> ToolRelevanceGate.apply(PERMITTED, Set.of("shell.exec")));
    }

    @Test
    @DisplayName("An empty or failed judgment keeps the full permitted set")
    void emptyKeepsPermitted() {
        assertEquals(PERMITTED, ToolRelevanceGate.apply(PERMITTED, Set.of()));
    }
}
