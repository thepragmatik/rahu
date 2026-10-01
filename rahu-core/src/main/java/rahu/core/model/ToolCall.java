package rahu.core.model;

import java.util.Objects;

/** A proposed tool call from the model (openrouter.md tool-call mapping). */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
