package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rahu demo} — AUDIT-2026-10-03-j.
 *
 * <p>The demo used to hand-assemble its own JSONL with a private {@code event()}
 * helper, making it a second, independently-maintained writer for the same four
 * event types the real tracer writes. It had already drifted: no {@code turnIndex},
 * no {@code degraded}, no {@code excludedCandidates}, and {@code usage} encoded as
 * the string {@code "unavailable"} where every reader expects a counters object.
 *
 * <p>Nothing caught it, because nothing compared the two writers. So the test here
 * is not "demo prints some output" - it is that demo's trace is byte-compatible in
 * SHAPE with what {@code RunTracer} produces, which is the property that would have
 * caught the drift. The demo's job is to show what a real run looks like, and it
 * cannot do that while writing a different dialect.
 */
class DemoCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shipped default, restored after every test so no test leaks its root. */
    private static final Path SHIPPED_ROOT = Path.of(".rahu", "runs");

    private static Path root;

    @BeforeEach
    void redirectTraceRoot(@TempDir Path tmp) {
        root = tmp.resolve("runs");
        DemoCommand.traceRoot = root;
    }

    @AfterEach
    void restoreTraceRoot() {
        DemoCommand.traceRoot = SHIPPED_ROOT;
    }

    /** Reads the single demo run directory the command created. */
    private static Path demoRun(Path root) throws IOException {
        try (var dirs = Files.list(root)) {
            List<Path> runs = dirs.filter(Files::isDirectory).toList();
            assertEquals(1, runs.size(), "demo must create exactly one run: " + runs);
            return runs.get(0);
        }
    }

    private static List<JsonNode> events(Path run) throws IOException {
        return Files.readAllLines(run.resolve("events.jsonl")).stream()
            .map(line -> {
                try {
                    return MAPPER.readTree(line);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("demo wrote invalid JSON: " + line, e);
                }
            })
            .toList();
    }

    private static JsonNode payloadOfType(List<JsonNode> events, String type) {
        return events.stream().filter(e -> type.equals(e.path("type").asText()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "demo trace has no " + type + " event: "
                    + events.stream().map(e -> e.path("type").asText()).toList()))
            .path("payload");
    }

    @Test
    @DisplayName("demo writes the same envelope fields as the real tracer")
    void demoUsesTheRealTraceDialect() throws Exception {
        // Driven through the real entry point so the trace path under test is the
        // one shipped, not a reconstructed copy - the lesson of AUDIT-2026-10-03-f.
        int exit = new picocli.CommandLine(new Main())
            .execute("demo");
        assertEquals(0, exit, "demo must succeed");

        Path run = demoRun(root);
        List<JsonNode> evs = events(run);
        assertEquals(4, evs.size(), "demo must write four events: " + evs);

        String runId = run.getFileName().toString();
        for (int i = 0; i < evs.size(); i++) {
            JsonNode e = evs.get(i);
            assertEquals(1, e.path("schemaVersion").asInt(), "schemaVersion: " + e);
            assertEquals(runId, e.path("runId").asText(),
                "every event must carry the run id, so the trace is one run: " + e);
            assertEquals(i + 1, e.path("sequence").asInt(),
                "sequence must be dense and 1-based; a gap breaks replay: " + e);
            assertFalse(e.path("timestamp").asText().isBlank(), "timestamp: " + e);
            assertTrue(e.path("elapsedMs").asInt(-1) >= 0, "elapsedMs: " + e);
        }
    }

    @Test
    @DisplayName("demo payload carries every field the real tracer emits")
    void demoPayloadIsNotMissingTracerFields() throws Exception {
        new picocli.CommandLine(new Main()).execute("demo");
        List<JsonNode> evs = events(demoRun(root));

        // The three fields the hand-built writer silently omitted. Asserted by NAME
        // because their absence is the defect: a reader that defaults them (like
        // inspect's asBoolean(false)) cannot tell "not degraded" from "not recorded".
        JsonNode started = payloadOfType(evs, "RunStarted");
        assertTrue(started.has("turnIndex"), "RunStarted lost turnIndex: " + started);
        assertTrue(started.has("configHash") && started.has("catalogHash"),
            "RunStarted lost its hashes: " + started);

        JsonNode route = payloadOfType(evs, "RouteResolved");
        assertTrue(route.has("degraded"), "RouteResolved lost degraded: " + route);
        assertEquals(false, route.path("degraded").asBoolean(),
            "demo does not degrade; the field must say so explicitly: " + route);
        assertTrue(route.has("excludedCandidates"), "RouteResolved lost excludedCandidates: " + route);

        JsonNode completed = payloadOfType(evs, "ModelCompleted");
        assertTrue(completed.path("usage").isObject(),
            "usage must be a counters object like RunTracer emits, not a string: " + completed);
        for (String field : List.of("promptTokens", "completionTokens", "costMicros")) {
            assertTrue(completed.path("usage").has(field),
                "usage is missing " + field + ": " + completed);
        }
    }

    /**
     * The demo made no model call, so "unobserved" is the honest claim. Asserted
     * because the previous encoding ("usage":"unavailable", a string) was read back
     * by inspect as a fabricated zero - a free request the demo never made.
     */
    @Test
    @DisplayName("demo records unobserved usage as null, not as zero")
    void demoDoesNotFabricateTokenCounts() throws Exception {
        new picocli.CommandLine(new Main()).execute("demo");
        JsonNode usage = payloadOfType(events(demoRun(root)), "ModelCompleted").path("usage");
        assertTrue(usage.path("promptTokens").isNull(),
            "demo made no call, so token counts must be null: " + usage);
        assertTrue(usage.path("completionTokens").isNull(),
            "demo made no call, so token counts must be null: " + usage);
        assertTrue(usage.path("costMicros").isNull(),
            "demo made no call, so cost must be null: " + usage);
    }

    /**
     * The demo's trace must be readable by the same tooling a real run's is -
     * otherwise the demo is showing the operator a format the product cannot open.
     */
    @Test
    @DisplayName("the demo trace is readable by the shipped inspect command")
    void demoTraceIsReadableByInspect() throws Exception {
        new picocli.CommandLine(new Main()).execute("demo");
        Path run = demoRun(root);

        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new picocli.CommandLine(new Main())
                .execute("trace", "inspect", "--format", "json", run.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        assertEquals(0, exit, "inspect rejected the demo trace: " + err);

        JsonNode report = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals("COMPLETE", report.path("status").asText(), report.toString());
        // And the round trip preserves the honest null rather than re-flattening it.
        assertTrue(report.path("usage").path("promptTokens").isNull(),
            "inspect re-flattened demo's unobserved usage to a number: " + report.path("usage"));
    }
}