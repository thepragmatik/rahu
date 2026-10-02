package rahu.core.context;

import java.util.List;
import java.util.Objects;
import rahu.core.model.ChatMessage;

/**
 * Deterministic prompt/projection plan (ARCHITECTURE.md ContextPlan): ordered
 * messages, pinned units, instruction hashes, template version, conservative
 * token estimate against the allowance.
 */
public record ContextPlan(
    List<ChatMessage> messages,
    List<String> instructionHashes,
    int templateVersion,
    int estimatedTokens,
    int contextAllowanceTokens) {

    public ContextPlan {
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        instructionHashes = instructionHashes == null ? List.of() : List.copyOf(instructionHashes);
    }
}
