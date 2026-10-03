package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.trace.TraceReader;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;
import rahu.core.context.SessionState;
import rahu.core.privacy.Provenance;

/**
 * AUDIT-2026-10-03-b: the offline loop must gate privacy and write a trace.
 *
 * <p>Written after two defects were found in {@code ChatCommand.runOffline} and
 * confirmed against a packaged build, not merely by reading the source:
 *
 * <ul>
 * <li>The loop echoed its input straight back to stdout. Piping an email and a
 * card number printed both verbatim and exited 0, while the live loop blocked
 * the same input. cli.md requires that "safe diagnostics and provenance checks
 * apply even when no model call is planned", and configuration.md makes offline
 * the DEFAULT mode - so the least protected path was the default one.
 *
 * <li>The loop wrote no trace at all, so product.md's first-run promise (a
 * complete trace, offline, no API keys needed) was unreachable under the
 * default configuration.
 * </ul>
 *
 * <p>Shaped like {@link RunTraceWiringTest} for the same reason: every test
 * drives the real driver and asserts on what landed on disk. A test that built
 * the expected trace itself, or called the writer directly, would pass against
 * exactly the broken state - which is how the original PASS claims were wrong.
 */
class OfflineTraceWiringTest {

    @TempDir
    Path root;

    /** A real email and a real-shaped card number: the input that used to echo. */
    private static final String PII_INPUT =
        "my email is bob@example.com and my card is 4111111111111111";
    private static final String CLEAN_INPUT = "summarise the readme";

    private record Result(int exit, String out, String err) { }

    /** Replays fixed input lines, so no test depends on System.in. */
    private static Supplier<Optional<String>> input(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> Optional.ofNullable(queue.poll());
    }

    private rahu.cli.config.RahuConfig config(Path traceDirectory, String classification)
        throws Exception {
        String json = """
            {
              "schemaVersion": 1,
              "mode": "offline",
              "decision": {"adapter": "fake", "model": "demo-decision",
                "baseUrl": "http://127.0.0.1:8000"},
              "generation": {"adapter": "fake", "baseUrl": "http://127.0.0.1:8000"},
              "agent": {"maxCostUsd": "1.00"},
              "routing": {"mode": "shadow", "pool": "demo",
                "baseline": "fast@low", "fallback": "fast@low",
                "confidenceField": "chosen_probability", "confidenceFloor": 0.65},
              "pools": {"demo": {"models": [
                {"alias": "fast", "id": "demo-fast", "reasoning": ["low", "medium"]}
              ]}},
              "context": {"instructionFiles": []},
              "tools": {"root": ".", "enabled": ["workspace.read"]},
              "trace": {"directory": %s, "capture": "metadata", "onFailure": "stop"},
              "orchestration": {"mode": "single"},
              "session": {"mode": "in-process", "maxTurns": 20, "maxCostUsd": "10.00"},
              "privacy": {"mode": "strict", "onUnknown": "block",
                "inputClassification": "%s"}
            }
            """.formatted(
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode().put("d", traceDirectory.toString()).get("d").toString(),
                classification);
        Path configPath = root.resolve("offline-" + traceDirectory.getFileName() + ".json");
        Files.writeString(configPath, json);
        return new rahu.cli.config.ConfigLoader().load(configPath);
    }

    /** Drives the real offline driver over the given input lines. */
    private Result drive(Path traceDirectory, String classification, String... lines)
        throws Exception {
        var cfg = config(traceDirectory, classification);
        var session = new SessionState("chat-offline-test", cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));
        var out = new StringWriter();
        var err = new StringWriter();
        Provenance provenance = "approved-nonsensitive".equals(classification)
            ? new Provenance.ApprovedNonSensitive("operator-classification")
            : Provenance.Unknown.INSTANCE;
        int exit = new OfflineTurnDriver(cfg, session, provenance,
            l -> null, new PrintWriter(out), new PrintWriter(err),
            input(lines), false).run();
        return new Result(exit, out.toString(), err.toString());
    }

    private List<Path> traces(Path directory) throws Exception {
        try (var walk = Files.walk(directory)) {
            return walk.filter(p -> p.toString().endsWith("events.jsonl")).toList();
        }
    }

    @Test
    @DisplayName("protected input is refused and never echoed to stdout")
    void protectedInputIsRefusedAndNotEchoed() throws Exception {
        var dir = Files.createDirectories(root.resolve("t1"));
        var result = drive(dir, "unknown", PII_INPUT);

        assertEquals(4, result.exit(), "a privacy block uses exit 4, as the live driver does");
        // The defect: this string contained both the email and the card number.
        assertFalse(result.out().contains("bob@example.com"),
            "the refused input must not reach stdout");
        assertFalse(result.out().contains("4111111111111111"),
            "the refused input must not reach stdout");
        // cli.md: a block names the category, never the content.
        assertTrue(result.err().contains("privacy blocked"),
            "the block must be reported: " + result.err());
        assertFalse(result.err().contains("bob@example.com"),
            "the diagnostic must identify the category, not the data");
    }

    @Test
    @DisplayName("an admitted turn writes a trace with real hashes and a terminal reason")
    void admittedTurnWritesTrace() throws Exception {
        var dir = Files.createDirectories(root.resolve("t2"));
        var result = drive(dir, "approved-nonsensitive", CLEAN_INPUT);

        assertEquals(0, result.exit(), "an admitted turn completes: " + result.err());
        var found = traces(dir);
        assertEquals(1, found.size(),
            "exactly one trace per turn, under the CONFIGURED directory: " + found);

        var reader = TraceReader.read(found.get(0));
        assertEquals(TraceReader.Completeness.COMPLETE, reader.completeness(),
            "a trace without its terminal record is an incomplete run, which is a"
                + " different claim from a finished one (A16): " + reader.corruptionReason());
        var events = reader.events();
        assertEquals(List.of("RunStarted", "RunTerminated"),
            events.stream().map(rahu.cli.trace.TraceEvent::type).toList(),
            "offline mode records only what is true: it routes nothing and calls no model");

        var started = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(events.get(0).payloadJson());
        assertFalse("synthetic".equals(started.get("configHash").asText()),
            "the config hash must be a real hash, not the demo placeholder");
        assertEquals(64, started.get("configHash").asText().length());
        assertTrue(started.get("sessionId").asText().startsWith("chat-offline-test"));

        assertEquals("ANSWER_COMPLETE",
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(events.get(1).payloadJson()).get("reason").asText());
    }

    @Test
    @DisplayName("a refusal is traced as a refusal, on a refused- run id")
    void refusalIsTraced() throws Exception {
        var dir = Files.createDirectories(root.resolve("t3"));
        drive(dir, "unknown", PII_INPUT);

        var found = traces(dir);
        assertEquals(1, found.size(), "a refusal leaves a record: " + found);
        // The id must be unmistakable: a refused turn has no run handle.
        assertTrue(found.get(0).toString().contains("refused-"),
            "a refusal trace must be distinguishable from a real run: " + found.get(0));

        var reader = TraceReader.read(found.get(0));
        assertEquals(TraceReader.Completeness.COMPLETE, reader.completeness(),
            "a refusal trace is a complete record of a refused run: "
                + reader.corruptionReason());
        var events = reader.events();
        assertEquals(List.of("RunStarted", "RunRefused", "RunTerminated"),
            events.stream().map(rahu.cli.trace.TraceEvent::type).toList());
        assertEquals("PRIVACY_BLOCKED",
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(events.get(2).payloadJson()).get("reason").asText());
    }

    @Test
    @DisplayName("an ADMITTED turn does not echo its input back either")
    void admittedTurnDoesNotEchoInput() throws Exception {
        // Mutation testing found this test missing: the original defect had two
        // halves, and while the refused path was covered, the admitted path was
        // not. Re-introducing the echo made all five tests pass, because nothing
        // asserted on stdout for a turn that was allowed to proceed.
        //
        // An admitted input may still be sensitive - the gate admits on
        // PROVENANCE, not on content being harmless - so echoing it is a second
        // disclosure the gate never got to judge.
        var dir = Files.createDirectories(root.resolve("t6"));
        var result = drive(dir, "approved-nonsensitive", CLEAN_INPUT);

        assertEquals(0, result.exit());
        assertTrue(result.out().contains("offline:"),
            "the turn must still answer: " + result.out());
        assertFalse(result.out().contains(CLEAN_INPUT),
            "an admitted input must not be echoed: " + result.out());
    }

    @Test
    @DisplayName("no offline trace ever contains the input text")
    void traceNeverContainsInputText() throws Exception {
        var admitted = Files.createDirectories(root.resolve("t4a"));
        var refused = Files.createDirectories(root.resolve("t4b"));
        drive(admitted, "approved-nonsensitive", CLEAN_INPUT);
        drive(refused, "unknown", PII_INPUT);

        for (Path trace : traces(admitted)) {
            String text = Files.readString(trace, StandardCharsets.UTF_8);
            assertFalse(text.contains(CLEAN_INPUT),
                "observability.md: metadata only, never the prompt: " + text);
        }
        for (Path trace : traces(refused)) {
            String text = Files.readString(trace, StandardCharsets.UTF_8);
            assertFalse(text.contains("bob@example.com"), "PII must not be traced: " + text);
            assertFalse(text.contains("4111111111111111"), "PII must not be traced: " + text);
        }
    }

    @Test
    @DisplayName("every line of input is processed, not every other one")
    void everyLineIsProcessed() throws Exception {
        var dir = Files.createDirectories(root.resolve("t5"));
        var result = drive(dir, "approved-nonsensitive",
            "first question", "second question", "third question");

        assertEquals(0, result.exit());
        // An earlier draft of the driver called the line supplier twice per
        // iteration, which consumed two lines per turn and silently dropped every
        // other input. Counting traces is what caught it.
        assertEquals(3, traces(dir).size(),
            "one trace per input line, so no input is silently skipped: " + traces(dir));
    }
}
