package rahu.cli.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S08 trace envelope + persistence semantics (observability.md; A10, A16).
 */
class TraceWriterTest {

    @TempDir
    Path tmp;

    private static TraceEvent event(int sequence, String type) {
        return new TraceEvent(1, "run-1", sequence, "2026-10-01T00:00:00Z", 5L, type, null,
            "{\"ok\":true}");
    }

    @Test
    @DisplayName("Envelope: schemaVersion, runId, sequence, timestamp, elapsedMs, type, payload")
    void envelopeShape() throws IOException {
        Path file = tmp.resolve("run.jsonl");
        var writer = new TraceWriter(file);
        writer.append(event(1, "RunStarted"));
        writer.append(event(2, "ModelCompleted"));
        writer.close();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(lines.get(0));
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("run-1", root.path("runId").asText());
        assertEquals(1, root.path("sequence").asInt());
        assertEquals("RunStarted", root.path("type").asText());
        assertTrue(root.has("timestamp"));
        assertTrue(root.has("elapsedMs"));
        assertTrue(root.path("payload").isObject() || root.path("payload").isTextual());
    }

    @Test
    @DisplayName("Sequence is assigned by the writer in append order")
    void writerOwnsSequence() throws IOException {
        Path file = tmp.resolve("run.jsonl");
        var writer = new TraceWriter(file);
        writer.append(event(99, "IgnoredClientSequence"));
        writer.append(event(99, "AlsoIgnored"));
        writer.close();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(Files.readAllLines(file).get(0));
        var second = mapper.readTree(Files.readAllLines(file).get(1));
        assertEquals(1, first.path("sequence").asInt());
        assertEquals(2, second.path("sequence").asInt());
    }

    @Test
    @DisplayName("A16: write failure marks the trace failed; the run must stop")
    void writeFailureStopsRun() throws IOException {
        Path dir = tmp.resolve("not-a-dir");
        Files.createDirectories(dir);
        Path file = dir; // a directory, not a file: append must fail
        var writer = new TraceWriter(file);
        assertThrows(TraceFailureException.class, () -> writer.append(event(1, "RunStarted")));
        assertTrue(writer.failed(), "writer must surface the failure state");
    }

    @Test
    @DisplayName("Flushed-before-effect contract: close flushes; reading back succeeds")
    void closeFlushes() throws IOException {
        Path file = tmp.resolve("run.jsonl");
        var writer = new TraceWriter(file);
        writer.append(event(1, "RunStarted"));
        writer.close();
        assertTrue(Files.size(file) > 0);
    }

    @Test
    @DisplayName("A16: a truncated last line makes the run incomplete, never a success")
    void truncatedLastLineIncomplete() throws IOException {
        Path file = tmp.resolve("run.jsonl");
        Files.writeString(file,
            "{\"schemaVersion\":1,\"runId\":\"r\",\"sequence\":1,\"type\":\"RunStarted\"}\n"
                + "{\"schemaVersion\":1,\"runId\":\"r\",\"sequence\":2,\"type\":\"RunTer");

        var reader = TraceReader.read(file);
        assertEquals(TraceReader.Completeness.INCOMPLETE, reader.completeness());
        assertEquals(1, reader.events().size(), "only complete events parse");
    }
}
