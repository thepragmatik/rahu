package rahu.cli.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import rahu.cli.Main;

/**
 * {@code rahu trace inspect} (cli.md:13) — AUDIT-2026-10-03-f.
 *
 * <p>These drive {@link Main} through {@link CommandLine}, not the command class
 * directly. That is deliberate: an earlier slice proved that a command class can
 * be fully tested and still not be reachable from the packaged entry point, which
 * is how {@code replay} was green and absent at the same time. A test of the
 * command object cannot see a missing registration; a test through {@code Main}
 * can.
 */
class TraceInspectCommandTest {

    private record Result(int exit, String out, String err) {
    }

    /** Runs the real entry point and captures both streams. */
    private static Result run(String... args) throws IOException {
        // Swapped at the JVM level rather than via picocli's setOut/setErr.
        // AUDIT-2026-10-03-i routed every command through the INJECTED writers, so
        // picocli's setters would now capture just fine - but this harness still
        // sees the real streams, which is the stronger assertion: it means the test
        // does not depend on the command being wired the way the test expects. A
        // regression back to System.out still shows up here.
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        int code;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            code = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return new Result(code, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    /** A run whose trace is complete: RunStarted, RouteResolved, RunTerminated. */
    private static Path completeRun(Path root, String prompt, boolean withCapture) throws IOException {
        Path run = Files.createDirectories(root.resolve("run-" + prompt.hashCode()));
        List<String> events = new ArrayList<>(List.of(
            event(1, "RunStarted", "{\"sessionId\":\"s\",\"turnIndex\":0}"),
            event(2, "RouteResolved", "{\"suggested\":\"quality@medium\","
                + "\"executed\":\"quality@medium\",\"mode\":\"SHADOW\",\"degraded\":false,"
                + "\"fallbackCause\":null,\"excludedCandidates\":0}"),
            event(3, "ModelCompleted", "{\"requestedModel\":\"demo\",\"usage\":"
                + "{\"promptTokens\":11,\"completionTokens\":5}}"),
            event(4, "RunTerminated", "{\"reason\":\"ANSWER_COMPLETE\","
                + "\"generationSteps\":1}")));
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            String.join("\n", events) + "\n", StandardCharsets.UTF_8);
        if (withCapture) {
            writeRealCapture(run);
        }
        return run;
    }

    /**
     * Writes a capture with the PRODUCTION writer.
     *
     * <p>An earlier version of this test hand-wrote a plausible-looking
     * {@code replay.json}. Replay rejected it with exit 5, and the honest reading
     * was that the fixture was wrong, not the command - which is why this uses
     * {@link ReplayCapture#write} instead of a literal. A fixture that only looks
     * like the artifact tests nothing but the reader's error handling.
     */
    private static void writeRealCapture(Path run) throws IOException {
        var candidates = new rahu.core.routing.CandidateSet(List.of(
            candidate("fast@low"), candidate("quality@medium")),
            List.of(new rahu.core.routing.CandidateSet.Exclusion(
                "stale@none", "unknown-evidence")));
        var input = new rahu.core.routing.RouteResolver.ResolutionInput(candidates,
            java.util.Optional.of("fast@low"), java.util.Optional.of("quality@medium"),
            "chosen_probability", 0.65);
        var probabilities = new java.util.LinkedHashMap<String, Double>();
        probabilities.put("fast@low", 0.3);
        probabilities.put("quality@medium", 0.7);
        var decision = new rahu.core.decision.DecisionResult.ValidChoice("q",
            "quality@medium", probabilities, java.util.Optional.of(0.7), "self-reported");
        ReplayCapture.write(run, candidates, rahu.core.routing.RoutingMode.SHADOW, input,
            java.util.Optional.of(decision));
    }

    private static rahu.core.ExecutionCandidate candidate(String id) {
        int at = id.indexOf('@');
        return new rahu.core.ExecutionCandidate(id,
            new rahu.core.ModelRef(id.substring(0, at)),
            id.endsWith("low")
                ? rahu.core.ReasoningPolicy.ExplicitEffort.of(
                    rahu.core.ReasoningPolicy.Effort.LOW)
                : rahu.core.ReasoningPolicy.ExplicitEffort.of(
                    rahu.core.ReasoningPolicy.Effort.MEDIUM),
            java.util.Map.of(), "cat", "cfg");
    }

    private static String event(int sequence, String type, String payload) {
        return "{\"schemaVersion\":1,\"runId\":\"r1\",\"sequence\":" + sequence
            + ",\"timestamp\":\"2026-10-03T00:00:00Z\",\"elapsedMs\":" + (sequence * 10)
            + ",\"type\":\"" + type + "\",\"payload\":" + payload + "}";
    }

    // ------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a complete trace reports COMPLETE and its routing outcome")
    void reportsCompleteRun(@TempDir Path tmp) throws IOException {
        Path run = completeRun(tmp, "hello", true);
        Result result = run("trace", "inspect", run.toString());
        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("trace: COMPLETE"), result.out());
        assertTrue(result.out().contains("executed=quality@medium"), result.out());
        assertTrue(result.out().contains("capture=true"), result.out());
        assertTrue(result.out().contains("RunTerminated=1"), result.out());
    }

    @Test
    @DisplayName("JSON mode emits one document with the same facts")
    void jsonShape(@TempDir Path tmp) throws IOException {
        Path run = completeRun(tmp, "hello", false);
        Result result = run("trace", "inspect", run.toString(), "--format", "json");
        assertEquals(0, result.exit(), result.err());
        String json = result.out().trim();
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(json.contains("\"status\":\"COMPLETE\""), json);
        assertTrue(json.contains("\"capturePresent\":false"), json);
        assertTrue(json.contains("\"promptTokens\":11"), json);
    }

    @Test
    @DisplayName("--verbose adds the timeline without adding payloads")
    void verboseAddsTimeline(@TempDir Path tmp) throws IOException {
        Path run = completeRun(tmp, "hello", false);
        Result result = run("trace", "inspect", run.toString(), "--verbose");
        assertTrue(result.out().contains("timeline:"), result.out());
        assertTrue(result.out().contains("#4 RunTerminated"), result.out());
    }

    // --------------------------------------------------------- completeness gate

    @Test
    @DisplayName("a trace with no terminal record is INCOMPLETE and exits 5")
    void incompleteTraceExitsFive(@TempDir Path tmp) throws IOException {
        Path run = Files.createDirectories(tmp.resolve("run-x"));
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            event(1, "RunStarted", "{}") + "\n" + event(2, "RouteResolved",
                "{\"executed\":\"fast@low\"}") + "\n", StandardCharsets.UTF_8);
        Result result = run("trace", "inspect", run.toString());
        assertEquals(5, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("INCOMPLETE"), result.out());
        assertTrue(result.out().contains("no RunTerminated"), result.out());
    }

    @Test
    @DisplayName("a sequence gap is detected: a trace with holes is not evidence")
    void sequenceGapDetected(@TempDir Path tmp) throws IOException {
        Path run = Files.createDirectories(tmp.resolve("run-gap"));
        // 1, 2, 4 - event 3 is missing. A trace that lost events cannot support a
        // claim about the whole run, however complete its start and end look.
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            event(1, "RunStarted", "{}") + "\n" + event(2, "RouteResolved", "{}") + "\n"
                + event(4, "RunTerminated", "{\"reason\":\"ANSWER_COMPLETE\"}") + "\n",
            StandardCharsets.UTF_8);
        Result result = run("trace", "inspect", run.toString());
        assertEquals(5, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("breaks the record"), result.out());
    }

    @Test
    @DisplayName("a truncated final line AFTER the terminal event is still honest evidence")
    void truncatedTailAfterTerminalAccepted(@TempDir Path tmp) throws IOException {
        Path run = Files.createDirectories(tmp.resolve("run-trunc"));
        String body = event(1, "RunStarted", "{}") + "\n"
            + event(2, "RunTerminated", "{\"reason\":\"ANSWER_COMPLETE\"}") + "\n";
        Files.writeString(run.resolve(TraceFiles.EVENTS), body + "{\"schemaVersion\":1,\"ty",
            StandardCharsets.UTF_8);
        Result result = run("trace", "inspect", run.toString());
        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("COMPLETE"), result.out());
    }

    @Test
    @DisplayName("a corrupt line BEFORE the terminal event fails the run")
    void corruptMiddleLineRejected(@TempDir Path tmp) throws IOException {
        Path run = Files.createDirectories(tmp.resolve("run-corrupt"));
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            event(1, "RunStarted", "{}") + "\n" + "not json at all\n"
                + event(3, "RunTerminated", "{\"reason\":\"ANSWER_COMPLETE\"}") + "\n",
            StandardCharsets.UTF_8);
        Result result = run("trace", "inspect", run.toString());
        assertEquals(5, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("corrupt event at line 2"), result.out());
    }

    // --------------------------------------------------------------- the privacy

    @Test
    @DisplayName("no prompt text reaches any output format")
    void neverEchoesPromptText(@TempDir Path tmp) throws IOException {
        String secret = "what is the quarterly revenue of the unnamed subsidiary";
        Path run = Files.createDirectories(tmp.resolve("run-pii"));
        // A realistic hostile trace: the prompt leaked into a payload field. An
        // inspect command that dumps payloads would print it here.
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            event(1, "RunStarted", "{\"sessionId\":\"s\",\"prompt\":\"" + secret + "\"}") + "\n"
                + event(2, "ModelRequested", "{\"prompt\":\"" + secret + "\"}") + "\n"
                + event(3, "RunTerminated", "{\"reason\":\"ANSWER_COMPLETE\"}") + "\n",
            StandardCharsets.UTF_8);
        for (String[] args : List.of(
            new String[] {"trace", "inspect", run.toString()},
            new String[] {"trace", "inspect", run.toString(), "--verbose"},
            new String[] {"trace", "inspect", run.toString(), "--format", "json"})) {
            Result result = run(args);
            assertFalse(result.out().contains(secret), "prompt leaked to stdout: " + result.out());
            assertFalse(result.err().contains(secret), "prompt leaked to stderr: " + result.err());
        }
    }

    @Test
    @DisplayName("the class cannot make a network or tool call")
    void noNetworkOrToolReference() throws IOException {
        // A behavioural test can only prove that a call DID happen. This proves the
        // absence of the means, which is what A11 actually requires.
        String source = Files.readString(
            Path.of("src/main/java/rahu/cli/trace/TraceInspectCommand.java"),
            StandardCharsets.UTF_8);
        for (String forbidden : new String[] {
            "HttpClient", "http://", "https://", "OkHttp", "URL(", "Socket",
            "openStream", "OpenRouterProvider", "DecisionEngine", "Tools.",
            "Dispatch", "Generat"}) {
            assertFalse(source.contains(forbidden),
                "trace inspect must not reference " + forbidden);
        }
    }

    // ------------------------------------------------------- agreement with replay

    @Test
    @DisplayName("inspect and replay judge the same file identically")
    void inspectAndReplayAgreeOnIntegrity(@TempDir Path tmp) throws IOException {
        Path good = completeRun(tmp, "good", true);
        Path bad = Files.createDirectories(tmp.resolve("run-bad"));
        Files.writeString(bad.resolve(TraceFiles.EVENTS),
            event(1, "RunStarted", "{}") + "\n", StandardCharsets.UTF_8);

        for (Path run : List.of(good, bad)) {
            int inspect = run("trace", "inspect", run.toString()).exit();
            int replay = run("replay", run.toString()).exit();
            // A file that inspect calls COMPLETE must replay rather than refuse;
            // a file inspect calls INCOMPLETE must be refused by both. The two
            // commands share RunLocator, and this test is what would notice if
            // that sharing ever broke.
            assertEquals(inspect == 0, replay == 0,
                "inspect exit " + inspect + " disagrees with replay exit " + replay
                    + " for " + run);
        }
    }

    @Test
    @DisplayName("both commands resolve the same run from a parent directory")
    void bothAcceptParentPath(@TempDir Path tmp) throws IOException {
        completeRun(tmp, "only", false);
        assertEquals(0, run("trace", "inspect", tmp.toString()).exit());
        assertEquals(3, run("replay", tmp.toString()).exit()); // complete, but no capture
    }

    @Test
    @DisplayName("an ambiguous parent names the candidates instead of guessing")
    void ambiguousParentIsRefused(@TempDir Path tmp) throws IOException {
        completeRun(tmp, "one", false);
        completeRun(tmp, "two", false);
        Result result = run("trace", "inspect", tmp.toString());
        assertEquals(2, result.exit(), result.out() + result.err());
        assertTrue(result.err().contains("holds 2 runs"), result.err());
    }

    @Test
    @DisplayName("a missing path exits 2, not 0")
    void missingPathIsInvalidInput(@TempDir Path tmp) throws IOException {
        Result result = run("trace", "inspect", tmp.resolve("nope").toString());
        assertEquals(2, result.exit(), result.out() + result.err());
        assertTrue(result.err().contains("no run trace"), result.err());
    }

    @Test
    @DisplayName("both commands are registered on the entry point")
    void bothCommandsRegistered() throws IOException {
        var help = new CommandLine(new Main()).getSubcommands().keySet();
        assertTrue(help.contains("trace"), help.toString());
        assertTrue(help.contains("replay"), help.toString());
        var trace = new CommandLine(new Main()).getSubcommands().get("trace");
        assertNotEquals(null, trace);
        assertTrue(trace.getSubcommands().keySet().contains("inspect"),
            trace.getSubcommands().keySet().toString());
    }

    /**
     * AUDIT-2026-10-03-j. A run that never observed usage must not be reported as
     * having used zero tokens.
     *
     * <p>The original encoding was {@code asInt(0)}, so a provider that returned no
     * counts became "promptTokens: 0" in the report - indistinguishable from a
     * genuinely free request. For an observability tool whose entire job is to
     * report what happened, a self-flattering default is a defect, not a cosmetic
     * one. Asserted on the JSON, because that is the machine-readable surface other
     * tools consume.
     */
    @Test
    @DisplayName("unobserved token usage is null, never a fabricated 0")
    void unobservedUsageIsNotZero(@TempDir Path run) throws IOException {
        Files.writeString(run.resolve("events.jsonl"), """
            {"schemaVersion":1,"runId":"r1","sequence":1,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":1,"type":"RunStarted","payload":{"sessionId":"s","configHash":"c",\
            "catalogHash":"k"}}
            {"schemaVersion":1,"runId":"r1","sequence":2,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":2,"type":"ModelCompleted","payload":{"requestedModel":"m",\
            "observedModel":"m","finishReason":"stop",\
            "usage":{"promptTokens":null,"completionTokens":null,"costMicros":null},\
            "effort":"provider-default"}}
            {"schemaVersion":1,"runId":"r1","sequence":3,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":3,"type":"RunTerminated","payload":{"reason":"ANSWER_COMPLETE",\
            "generationSteps":1}}
            """);

        Result r = run("trace", "inspect", "--format", "json", run.toString());
        assertEquals(0, r.exit(), r.err());
        var usage = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(r.out()).path("usage");
        assertTrue(usage.path("promptTokens").isNull(),
            "unobserved prompt tokens must be null, not 0: " + usage);
        assertTrue(usage.path("completionTokens").isNull(),
            "unobserved completion tokens must be null, not 0: " + usage);
    }

    /**
     * The complement of the test above: a real measurement is still reported as a
     * number. Without this, "always emit null" would satisfy the previous test while
     * destroying the feature.
     */
    @Test
    @DisplayName("a recorded token count is still reported as a number")
    void observedUsageIsReportedVerbatim(@TempDir Path run) throws IOException {
        Files.writeString(run.resolve("events.jsonl"), """
            {"schemaVersion":1,"runId":"r1","sequence":1,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":1,"type":"RunStarted","payload":{"sessionId":"s","configHash":"c",\
            "catalogHash":"k"}}
            {"schemaVersion":1,"runId":"r1","sequence":2,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":2,"type":"ModelCompleted","payload":{"requestedModel":"m",\
            "observedModel":"m","finishReason":"stop",\
            "usage":{"promptTokens":11,"completionTokens":5,"costMicros":7},\
            "effort":"provider-default"}}
            {"schemaVersion":1,"runId":"r1","sequence":3,"timestamp":"2026-01-01T00:00:00Z",\
            "elapsedMs":3,"type":"RunTerminated","payload":{"reason":"ANSWER_COMPLETE",\
            "generationSteps":1}}
            """);

        Result r = run("trace", "inspect", "--format", "json", run.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("\"promptTokens\":11"), r.out());
    }
}
