package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSONL trace writer (observability.md): one writer owns per-run sequence
 * assignment; append-failures raise TraceFailureException (A16) and latch the
 * writer into a failed state — callers stop new work instead of faking success.
 */
public final class TraceWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private BufferedWriter out;
    private int nextSequence = 1;
    private boolean failed;
    private boolean closed;

    public TraceWriter(Path file) {
        this.file = file;
        // Open is deferred to the first append so construction cannot fail:
        // callers learn about a broken trace sink at the A16 checkpoint, where
        // the run-stop semantics live.
    }

    private BufferedWriter writer() throws IOException {
        if (out == null) {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        }
        return out;
    }

    /** Appends one event; sequence is assigned here, ignoring any client value. */
    public synchronized void append(TraceEvent event) {
        if (failed) {
            throw new TraceFailureException("trace writer already failed", null);
        }
        if (closed) {
            throw new TraceFailureException("trace writer is closed", null);
        }
        try {
            BufferedWriter w = writer();
            ObjectNode node = MAPPER.createObjectNode();
            node.put("schemaVersion", TraceEvent.SCHEMA_VERSION);
            node.put("runId", event.runId());
            node.put("sequence", nextSequence++);
            node.put("timestamp", event.timestamp());
            node.put("elapsedMs", event.elapsedMs());
            node.put("type", event.type());
            if (event.operationId() != null) {
                node.put("operationId", event.operationId());
            }
            JsonNode payload = MAPPER.readTree(event.payloadJson());
            node.set("payload", payload);
            w.write(MAPPER.writeValueAsString(node));
            w.newLine();
            w.flush();
        } catch (IOException e) {
            failed = true;
            throw new TraceFailureException("trace append failed", e);
        }
    }

    public synchronized boolean failed() {
        return failed;
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            failed = true;
            throw new TraceFailureException("trace close failed", e);
        }
    }
}
