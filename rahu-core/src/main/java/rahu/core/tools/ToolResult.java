package rahu.core.tools;

import java.util.Objects;

/**
 * Bounded tool outcome (tools.md): success/denied/invalid/failed with content
 * limits and explicit truncation — never an empty string swallowing an error.
 */
public record ToolResult(Status status, String content, boolean truncated, boolean reused,
    String safeReason) {

    public enum Status { SUCCESS, DENIED, INVALID, FAILED }

    public ToolResult {
        Objects.requireNonNull(status, "status");
        content = content == null ? "" : content;
    }

    public static ToolResult success(String content, boolean truncated) {
        return new ToolResult(Status.SUCCESS, content, truncated, false, null);
    }

    public static ToolResult invalid(String reason) {
        return new ToolResult(Status.INVALID, "", false, false, reason);
    }

    public static ToolResult denied(String reason) {
        return new ToolResult(Status.DENIED, "", false, false, reason);
    }

    public static ToolResult failed(String reason) {
        return new ToolResult(Status.FAILED, "", false, false, reason);
    }

    ToolResult asReused() {
        return new ToolResult(status, content, truncated, true, safeReason);
    }
}
