package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Trace reader (observability.md): parses JSONL events; a truncated or corrupt
 * final line makes the run INCOMPLETE — never accepted as a complete success
 * (A16). Sequence gaps are reported but earlier events still inspect.
 */
public final class TraceReader {

    public enum Completeness { COMPLETE, INCOMPLETE, EMPTY }

    private final List<TraceEvent> events;
    private final Completeness completeness;
    private final String corruptionReason;

    private TraceReader(List<TraceEvent> events, Completeness completeness,
        String corruptionReason) {
        this.events = List.copyOf(events);
        this.completeness = completeness;
        this.corruptionReason = corruptionReason;
    }

    public static TraceReader read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        var mapper = new ObjectMapper();
        List<TraceEvent> events = new ArrayList<>();
        String corruption = null;

        int last = lines.size() - 1;
        while (last >= 0 && lines.get(last).isBlank()) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                if (!node.isObject()) {
                    corruption = "line " + (i + 1) + " is not a JSON object";
                    break;
                }
                events.add(new TraceEvent(
                    node.path("schemaVersion").asInt(1),
                    node.path("runId").asText(""),
                    node.path("sequence").asInt(0),
                    node.path("timestamp").asText(""),
                    node.path("elapsedMs").asLong(0),
                    node.path("type").asText(""),
                    node.hasNonNull("operationId") ? node.get("operationId").asText() : null,
                    node.has("payload") ? node.get("payload").toString() : "{}"));
            } catch (IOException e) {
                // An unparseable line: only tolerable as the truncated FINAL line.
                if (i == last) {
                    corruption = "truncated final line (line " + (i + 1) + ")";
                } else {
                    corruption = "corrupt event at line " + (i + 1);
                }
                break;
            }
        }
        if (corruption != null) {
            return new TraceReader(events, Completeness.INCOMPLETE, corruption);
        }
        if (events.isEmpty()) {
            return new TraceReader(events, Completeness.EMPTY, null);
        }
        return new TraceReader(events, Completeness.COMPLETE, null);
    }

    public List<TraceEvent> events() {
        return events;
    }

    public Completeness completeness() {
        return completeness;
    }

    public String corruptionReason() {
        return corruptionReason;
    }
}
