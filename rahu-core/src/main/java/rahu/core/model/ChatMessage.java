package rahu.core.model;

import java.util.Objects;

/** Ordered conversation message (openrouter.md role ordering). */
public record ChatMessage(Role role, String content, String toolCallId,
    java.util.List<ToolCall> toolCalls) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null || toolCalls.isEmpty()
            ? java.util.List.of() : java.util.List.copyOf(toolCalls);
        }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, null);
    }

    public static ChatMessage assistantWithToolCalls(java.util.List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, "", null, calls);
    }
}
