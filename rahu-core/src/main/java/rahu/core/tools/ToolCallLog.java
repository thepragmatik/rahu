package rahu.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A09 dedup log for tool-call IDs (tools.md): same ID + identical canonical
 * arguments reuses the recorded outcome; same ID + different arguments is a
 * protocol error; new IDs are fresh requests subject to ordinary limits.
 */
public final class ToolCallLog {

    /** Same ID with different arguments (provider protocol violation). */
    public static final class ProtocolError extends IllegalStateException {

        public ProtocolError(String message) {
            super(message);
        }
    }

    private record Entry(String canonicalArgs, ToolResult outcome) {
    }

    private final Map<String, Entry> log = new LinkedHashMap<>();

    public ToolResult lookup(String callId, String canonicalArgs) {
        Entry e = log.get(callId);
        if (e == null) {
            return null;
        }
        if (!e.canonicalArgs().equals(canonicalArgs)) {
            throw new ProtocolError(
                "tool call ID " + callId + " repeated with different arguments");
        }
        return e.outcome().asReused();
    }

    public void record(String callId, String canonicalArgs, ToolResult outcome) {
        log.put(callId, new Entry(canonicalArgs, outcome));
    }
}
