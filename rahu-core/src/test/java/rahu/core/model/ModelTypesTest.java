package rahu.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/** S04 core model types (A09, A18 semantics at the type level). */
class ModelTypesTest {

    @Test
    @DisplayName("A18: continuation envelope bytes are defensively copied both ways")
    void envelopeDefensiveCopy() {
        byte[] secret = { 1, 2, 3 };
        var env = new ContinuationEnvelope("openrouter", secret, true);
        secret[0] = 9;
        assertEquals(1, env.data()[0]);
        env.data()[0] = 5;
        assertEquals(1, env.data()[0]);
        var empty = ContinuationEnvelope.empty("openrouter");
        assertTrue(!empty.present());
    }

    @Test
    @DisplayName("Usage absent fields are unknown, never zero")
    void usageUnknownNotZero() {
        var usage = new Usage(null, null, null, null);
        assertTrue(usage.promptTokensOpt().isEmpty());
        assertNotEquals(Integer.valueOf(0), usage.promptTokensOpt().orElse(-1));
    }

    @Test
    @DisplayName("GenerationRequest requires positive token caps and nonempty messages")
    void requestValidation() {
        var model = new ModelRef("m");
        assertThrows(IllegalArgumentException.class,
            () -> new GenerationRequest(model, ReasoningPolicy.ProviderDefault.INSTANCE,
                List.of(ChatMessage.user("hi")), List.of(), 0));
        assertThrows(IllegalArgumentException.class,
            () -> new GenerationRequest(model, ReasoningPolicy.ProviderDefault.INSTANCE,
                List.of(), List.of(), 100));
        var ok = new GenerationRequest(model, ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("hi")), List.of(), 100);
        assertTrue(ok.toolsOpt().isEmpty());
    }

    @Test
    @DisplayName("Completed outcome with tool calls keeps order")
    void toolCallOrderPreserved() {
        var calls = List.of(new ToolCall("c1", "workspace.list", "{}"),
            new ToolCall("c2", "workspace.read", "{\"path\":\"a.md\"}"));
        var outcome = new ModelOutcome.Completed("", calls,
            new Usage(10, 20, null, null), "tool_calls", Optional.empty(),
            Optional.empty(), ContinuationEnvelope.empty("openrouter"));
        assertEquals("c1", outcome.proposedToolCalls().get(0).id());
        assertEquals("c2", outcome.proposedToolCalls().get(1).id());
        assertTrue(outcome.hasToolCalls());
    }
}
