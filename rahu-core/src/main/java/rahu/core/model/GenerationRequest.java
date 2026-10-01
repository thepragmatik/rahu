package rahu.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;

/**
 * One non-streaming generation request (openrouter.md generation mapping):
 * exact selected model, ordered messages, optional tools, output cap, reasoning
 * policy, provider constraints.
 */
public record GenerationRequest(
    ModelRef model,
    ReasoningPolicy reasoningPolicy,
    List<ChatMessage> messages,
    List<ToolDescriptor> tools,
    int maxCompletionTokens) {

    public GenerationRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(reasoningPolicy, "reasoningPolicy");
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        tools = tools == null || tools.isEmpty() ? List.of() : List.copyOf(tools);
        if (maxCompletionTokens <= 0) {
            throw new IllegalArgumentException("maxCompletionTokens must be positive");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
    }

    public Optional<List<ToolDescriptor>> toolsOpt() {
        return tools.isEmpty() ? Optional.empty() : Optional.of(tools);
    }
}
