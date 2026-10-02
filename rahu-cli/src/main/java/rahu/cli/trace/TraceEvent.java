package rahu.cli.trace;

import java.util.Objects;

/**
 * Versioned trace event (observability.md): schemaVersion=1, runId, writer-assigned
 * monotonic sequence, UTC timestamp, elapsed monotonic ms, event type, optional
 * operation/parent IDs, bounded payload (metadata-only by default).
 */
public record TraceEvent(
    int schemaVersion,
    String runId,
    int sequence,
    String timestamp,
    long elapsedMs,
    String type,
    String operationId,
    String payloadJson) {

    public TraceEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
    }

    public static final int SCHEMA_VERSION = 1;
}
