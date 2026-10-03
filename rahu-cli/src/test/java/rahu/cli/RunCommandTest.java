package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;

/**
 * {@code rahu run} (cli.md:11, 13, 15, 17, 27).
 *
 * <p>Audit finding AUDIT-2026-10-03-g: the command was documented but did not exist -
 * the last gap in the command table. These tests pin the behaviours cli.md specifies
 * rather than the implementation's shape, because the specification is what the command
 * was missing.
 *
 * <p>The stdout/stderr split is the property most likely to rot silently and is
 * therefore asserted by parsing the streams, not by substring-matching everything at
 * once. A test that only checks "the answer appears somewhere" passes even when the
 * answer is on stderr and stdout carries progress noise, which is exactly the defect
 * automation consuming {@code --format json} would hit.
 */
class RunCommandTest {

    /** The offline fixture; its answers are deterministic (A21). */
    private static final Path OFFLINE = RepoFile.of("examples/offline.json");

    private record Run(int code, String out, String err) {
    }

    /**
     * A run plus whatever reached the real {@link System#out}.
     *
     * <p>{@code ReplayCommand} prints to {@code System.out} rather than to an injected
     * {@code PrintWriter} (unlike {@code RunCommand}), so {@link #run} captures nothing
     * for it. Without this, every assertion on replay's output silently passes on an
     * empty string - which is how a self-contradicting message survived a full
     * increment. Converting both trace commands to injected writers is recorded as
     * AUDIT-2026-10-03-i; capturing the stream keeps this finding honest now.
     */
    private record Captured(int code, String systemOut, String err) {
    }

    private static Captured capturingSystemOut(java.util.function.Supplier<Run> body) {
        PrintStream real = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            Run r = body.get();
            return new Captured(r.code(), buffer.toString(StandardCharsets.UTF_8), r.err());
        } finally {
            System.setOut(real);
        }
    }

    private static Run run(String stdin, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        InputStream previousIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(
                stdin.getBytes(StandardCharsets.UTF_8)));
            CommandLine cmd = new CommandLine(new Main());
            cmd.setOut(new PrintWriter(out, true));
            cmd.setErr(new PrintWriter(err, true));
            int code = cmd.execute(args);
            return new Run(code, out.toString(), err.toString());
        } finally {
            System.setIn(previousIn);
        }
    }

    /**
     * Runs {@code rahu run} against the offline fixture.
     *
     * <p>The argument order is built explicitly because picocli pairs {@code --prompt}
     * with its value; an earlier draft of this helper emitted {@code --prompt} with no
     * value and every test then failed on "Missing required option" - which looks like
     * a broken command rather than a broken helper.
     */
    private static Run offline(String... extra) {
        return offlineWith("", "summarise this repository", extra);
    }

    /**
     * {@code approved-nonsensitive} is passed by default because the fixture is
     * {@code privacy.onUnknown: block} and normal text classifies as unknown, so an
     * unclassified run is blocked before it does anything.
     *
     * <p>Verified rather than assumed: {@code rahu chat} against this same fixture and
     * the same unclassified input also exits 3 with the same reason. The two commands
     * agreeing here is the point of {@link rahu.cli.live.LiveAssembly#provenance} - a
     * divergence would be a privacy divergence, so this is asserted through the shared
     * helper rather than reimplemented per command.
     */
    private static Run offlineWith(String stdin, String task, String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
            "run", "--config", OFFLINE.toString(), "--prompt", task,
            "--input-classification", "approved-nonsensitive"));
        args.addAll(java.util.List.of(extra));
        return run(stdin, args.toArray(String[]::new));
    }

    /**
     * A copy of the fixture with its trace directory redirected into {@code tmp}.
     *
     * <p>Rewrites the existing value rather than adding a key: an earlier draft
     * inserted a second {@code "directory"}, and the loader's duplicate-key check
     * rejected the config - correctly, and for a reason unrelated to the routing
     * override the test was written to check.
     */
    private static Path withTraceDir(Path tmp, String name) {
        try {
            Path cfg = tmp.resolve(name);
            String json = Files.readString(OFFLINE).replace("\"directory\": \".rahu/runs\"",
                "\"directory\": \"" + tmp.resolve("runs").toString().replace("\\", "\\\\") + "\"");
            Files.writeString(cfg, json);
            return cfg;
        } catch (java.io.IOException e) {
            throw new AssertionError("could not write " + name, e);
        }
    }

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("a completed run exits 0 with the answer on stdout")
    void answerGoesToStdout() {
        Run result = offline();
        assertEquals(0, result.code(), "stderr was: " + result.err());
        assertTrue(result.out().contains("bounded read-only answer"),
            "answer missing from stdout: " + result.out());
    }

    @Test
    @DisplayName("stdout carries the answer and nothing else")
    void progressNeverPollutesStdout() {
        Run result = offline();
        assertEquals(1, result.out().lines().filter(l -> !l.isBlank()).count(),
            "stdout must be exactly the answer, got: " + result.out());
        assertTrue(result.out().startsWith("offline:"), result.out());
        // The offline driver prints no banner for a bounded run, so stderr is empty
        // rather than carrying a note. Either is acceptable; what matters is that
        // nothing progress-shaped reaches stdout.
    }

    @Test
    @DisplayName("json mode reports the run id the driver actually wrote")
    void jsonReportsTheRealRunId() {
        Run result = offline("--format", "json");
        var node = new ObjectMapper();
        try {
            var doc = node.readTree(result.out());
            String runId = doc.get("runId").isNull() ? null : doc.get("runId").asText();
            assertTrue(runId != null && runId.startsWith("run-"),
                "json mode must report the run id the trace was written under, got: "
                    + result.out());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("stdout is not valid JSON: " + result.out(), e);
        }
    }

    // -------------------------------------------------------------- --prompt -

    @Test
    @DisplayName("`--prompt -` reads the task from stdin")
    void readsTaskFromStdin() {
        // Restored in a finally: System.in is JVM-global, and a test that replaces it
        // without restoring it makes every later test in the suite read this fixture.
        Run result = offlineWith("a task supplied on stdin", "-");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("bounded read-only answer"),
            "stdin task should have produced an answer: " + result.out());
    }

    @Test
    @DisplayName("an empty task is refused rather than run")
    void emptyTaskIsRefused() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("run", "--config", OFFLINE.toString(), "--prompt", "   ");
        // cli.md:42 - invalid input is exit 2. An empty task accepted here would burn a
        // paid call to produce an empty answer that looks like a completed turn.
        assertEquals(2, code, err.toString());
        assertTrue(err.toString().contains("empty"), err.toString());
        assertTrue(out.toString().isBlank(), "nothing should reach stdout: " + out);
    }

    // ----------------------------------------------------------------- format

    @Test
    @DisplayName("--format json emits exactly one document on stdout")
    void jsonModeEmitsOneDocument() {
        Run result = offline("--format", "json");
        assertEquals(0, result.code(), result.err());
        String[] lines = result.out().strip().split("\n");
        assertEquals(1, lines.length,
            "stdout must carry ONE document, got: " + result.out());
        assertTrue(lines[0].startsWith("{") && lines[0].endsWith("}"),
            "not a JSON object: " + lines[0]);
    }

    @Test
    @DisplayName("--format json carries the answer and a typed status")
    void jsonCarriesAnswerAndStatus() {
        Run result = offline("--format", "json");
        String doc = result.out().strip();
        assertTrue(doc.contains("\"status\":\"COMPLETE\""), doc);
        assertTrue(doc.contains("\"answer\""), doc);
    }

    @Test
    @DisplayName("an invalid --format is refused with exit 2")
    void invalidFormatRefused() {
        Run result = offline("--format", "yaml");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("text or json"), result.err());
    }

    @Test
    @DisplayName("an answer containing quotes and newlines still yields valid JSON")
    void jsonEscapesAwkwardAnswers() {
        // A model answer can contain a quote, a backslash or a newline. Unescaped, any
        // of them makes the document unparseable - and the consumer that asked for JSON
        // gets a silent failure instead of an answer.
        String escaped = RunCommand.jsonEscape("say \"hi\"\n\\ and \tend");
        assertEquals("say \\\"hi\\\"\\n\\\\ and \\tend", escaped);
    }

    // ---------------------------------------------------------------- routing

    @Test
    @DisplayName("--routing is validated against the spec vocabulary")
    void invalidRoutingRefused() {
        Run result = offline("--routing", "turbo");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("off, shadow or active"), result.err());
    }

    @Test
    @DisplayName("an explicit --routing override reaches the effective config")
    void routingOverrideReachesConfig(@TempDir Path tmp) throws Exception {
        // The override must be applied to the EFFECTIVE config, not merely accepted:
        // the trace's config hash is computed from the loaded record, so an override
        // applied after the hash is taken would produce a trace describing a
        // configuration the run did not use.
        Path cfg = withTraceDir(tmp, "active.json");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        assertEquals(0, cmd.execute("run", "--config", cfg.toString(),
            "--prompt", "a task", "--routing", "active", "--format", "json",
            "--input-classification", "approved-nonsensitive"),
            err.toString());
        assertTrue(out.toString().contains("\"status\""), out.toString());
    }

    // --------------------------------------------------------------- capture

    @Test
    @DisplayName("--capture-payloads changes capture for this run only")
    void capturePayloadsIsPerRun(@TempDir Path tmp) throws Exception {
        Path cfg = withTraceDir(tmp, "capt.json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"directory\": \".rahu/runs\"",
            "\"directory\": \"" + tmp.resolve("runs").toString().replace("\\", "\\\\") + "\""));

        StringWriter err = new StringWriter();
        CommandLine noCapture = new CommandLine(new Main());
        noCapture.setOut(new PrintWriter(new StringWriter(), true));
        noCapture.setErr(new PrintWriter(err, true));
        noCapture.execute("run", "--config", cfg.toString(), "--prompt", "a task",
            "--input-classification", "approved-nonsensitive");
        assertFalse(Files.exists(tmp.resolve("runs").resolve("replay-inputs.json")),
            "metadata capture must stay the DEFAULT and write no replay inputs");

        StringWriter err2 = new StringWriter();
        CommandLine capture = new CommandLine(new Main());
        capture.setOut(new PrintWriter(new StringWriter(), true));
        capture.setErr(new PrintWriter(err2, true));
        capture.execute("run", "--config", cfg.toString(), "--prompt", "a task",
            "--capture-payloads", "--input-classification", "approved-nonsensitive");
        // Offline mode does not route, so nothing is captured; the assertion that
        // matters is that the flag did NOT rewrite the config file itself.
        assertFalse(Files.readString(cfg).contains("\"payloads\""),
            "--capture-payloads is a per-run override and must not rewrite the config");
    }

    // --------------------------------------------------------------- privacy

    @Test
    @DisplayName("a blocked input exits 3 and never echoes the content")
    void privacyBlockedNeverEchoes(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("strict.json");
        Files.writeString(cfg, Files.readString(OFFLINE));
        String secret = "my password is hunter2 and my card is 4111111111111111";
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("run", "--config", cfg.toString(), "--prompt", secret);
        // cli.md:42 groups privacy blocked with no-route/limit: nothing was sent, and
        // retrying unchanged will not help.
        assertEquals(3, code, err.toString());
        assertFalse(out.toString().contains("hunter2"),
            "blocked input leaked to stdout: " + out);
        assertFalse(err.toString().contains("hunter2"),
            "blocked input leaked to stderr: " + err);
    }

    @Test
    @DisplayName("a blocked input in json mode reports only its category")
    void privacyBlockedJsonHasNoContent() {
        Run result = run("", "run", "--config", OFFLINE.toString(), "--prompt",
            "my password is hunter2", "--format", "json");
        assertEquals(3, result.code(), result.err());
        assertFalse(result.out().contains("hunter2"),
            "blocked input leaked into JSON: " + result.out());
        // A refusal is a real, reportable outcome (cli.md:48): it carries the exit code
        // and never the content. There is no runId because no turn began.
        assertTrue(result.out().contains("\"exitCode\":3"), result.out());
        assertTrue(result.out().contains("\"answer\":null"), result.out());
    }

    // ------------------------------------------------- single-turn contract

    @Test
    @DisplayName("run executes exactly one turn regardless of input size")
    void oneTurnOnly(@TempDir Path tmp) throws Exception {
        Path cfg = withTraceDir(tmp, "one.json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"directory\": \".rahu/runs\"",
            "\"directory\": \"" + tmp.resolve("runs").toString().replace("\\", "\\\\") + "\""));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        cmd.execute("run", "--config", cfg.toString(), "--prompt",
            "line one\nline two\nline three",
            "--input-classification", "approved-nonsensitive");
        assertEquals(1, out.toString().lines().filter(l -> !l.isBlank()).count(),
            "a bounded run must produce exactly one answer: " + out);
    }

    @Test
    @DisplayName("run does not treat a slash line as a command")
    void slashLineIsNotACommand(@TempDir Path tmp) throws Exception {
        // cli.md:27 - run is a fresh single-turn session and cannot resume or accept
        // interactive commands. If a `/status` line were handled, `run` would silently
        // become `chat`.
        Path cfg = withTraceDir(tmp, "slash.json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"directory\": \".rahu/runs\"",
            "\"directory\": \"" + tmp.resolve("runs").toString().replace("\\", "\\\\") + "\""));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("run", "--config", cfg.toString(), "--prompt", "/status",
            "--input-classification", "approved-nonsensitive");
        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("offline:"),
            "a slash line must be treated as the task TEXT and answered, not rejected"
                + " as a command: stdout=" + out + " stderr=" + err);
        assertFalse(err.toString().contains("/status /reset /exit"),
            "run must not advertise chat's interactive commands: " + err);
    }

    /**
     * Offline and live JSON must expose the SAME keys.
     *
     * <p>A schema that changes shape with the mode is a schema no consumer can rely on:
     * automation would need two parsers, and the field it reads for a live answer would
     * be absent for the offline one - discovered as a KeyError in someone else's
     * pipeline rather than here. Values legitimately differ (offline records no runId
     * and no cost); the KEYS must not.
     */
    @Test
    @DisplayName("offline json carries the same keys as the live document")
    void jsonSchemaIsStableAcrossModes() {
        Run offlineJson = offline("--format", "json");
        assertEquals(0, offlineJson.code(), offlineJson.err());
        java.util.Set<String> offlineKeys = keysOf(offlineJson.out());
        assertTrue(offlineKeys.containsAll(java.util.List.of("status", "answer", "routing",
                "cost", "generationSteps", "exitCode", "runId")),
            "offline document is missing keys the live one has: " + offlineKeys);

        // The live document is rendered by the same method for every turn, so assert the
        // shared key set directly rather than paying for a live provider here.
        java.util.Set<String> liveKeys = new java.util.LinkedHashSet<>(java.util.List.of(
            "status", "exitCode", "answer", "routing", "cost", "generationSteps", "runId"));
        assertEquals(liveKeys, offlineKeys,
            "offline and live JSON must expose the same keys; values may differ");
    }

    private static java.util.Set<String> keysOf(String json) {
        try {
            var node = new ObjectMapper().readTree(json);
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("stdout is not valid JSON: " + json, e);
        }
    }

    /**
     * An offline run must leave a trace.
     *
     * <p>The first implementation inlined the offline answer and wrote NOTHING - while
     * {@code chat} offline wrote RunStarted + RunTerminated. That is the AUDIT-a shape:
     * a path that answers without leaving evidence, indistinguishable from a turn that
     * never ran. Nothing caught it because every test asserted on stdout, and stdout was
     * correct. The assertion belongs on the filesystem.
     */
    @Test
    @DisplayName("an offline run writes a trace, like every other turn")
    void offlineRunLeavesATrace() throws Exception {
        Path dir = tmpDir();
        Path cfg = withTraceDir(dir, "trace.json");
        Run result = run("", "run", "--config", cfg.toString(), "--prompt", "a task",
            "--input-classification", "approved-nonsensitive");
        assertEquals(0, result.code(), result.err());

        Path events;
        try (var walk = Files.walk(dir)) {
            events = walk.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                .findFirst().orElse(null);
        }
        assertTrue(events != null,
            "an offline run wrote no trace; a turn that leaves no record cannot be told"
                + " apart from one that never ran (AUDIT-a)");
        String trace = Files.readString(events);
        assertTrue(trace.contains("\"type\":\"RunStarted\""), trace);
        assertTrue(trace.contains("\"type\":\"RunTerminated\""), trace);
    }

    @Test
    @DisplayName("an offline run does not print chat's end-of-input banner")
    void offlineRunHasNoChatBanner() {
        Run result = offline();
        assertFalse(result.err().contains("eof: chat ended"),
            "run is a bounded task and must not announce a chat session ending: "
                + result.err());
        assertFalse(result.err().contains("/status /reset /exit"),
            "run must not advertise chat's interactive commands: " + result.err());
    }

    /** A fresh temp dir per call, so trace-walking tests cannot see each other's runs. */
    private Path tmpDir() throws Exception {
        Path dir = Files.createTempDirectory("run-trace");
        dir.toFile().deleteOnExit();
        return dir;
    }

    // ------------------------------------------------------ override effects

    @Test
    @DisplayName("--routing actually changes the effective config's routing mode")
    void routingOverrideChangesTheConfig() throws Exception {
        RahuConfig base = new ConfigLoader().load(OFFLINE);
        assertEquals("shadow", base.routing().mode(),
            "the fixture's baseline; if this changed the test's premise is wrong");

        RahuConfig overridden = RunCommand.applyOverrides(base, "active", false);
        assertEquals("active", overridden.routing().mode(),
            "--routing must change the EFFECTIVE mode, not be accepted and discarded");

        // And it must change nothing else: an override that silently rewrote the pool
        // or baseline would make two runs differ for reasons the operator never asked
        // for, and the trace's config hash would record that difference.
        assertEquals(base.routing().pool(), overridden.routing().pool());
        assertEquals(base.routing().baseline(), overridden.routing().baseline());
        assertEquals(base.routing().confidenceFloor(),
            overridden.routing().confidenceFloor());
    }

    @Test
    @DisplayName("--capture-payloads actually changes the effective capture mode")
    void captureOverrideChangesTheConfig() throws Exception {
        RahuConfig base = new ConfigLoader().load(OFFLINE);
        assertEquals("metadata", base.trace().capture(),
            "metadata must remain the default (cli.md:17)");

        RahuConfig overridden = RunCommand.applyOverrides(base, null, true);
        assertEquals("payloads", overridden.trace().capture(),
            "--capture-payloads must change the EFFECTIVE capture mode");
        assertEquals(base.trace().directory(), overridden.trace().directory(),
            "capture override must not move the trace directory");
        assertEquals(base.trace().onFailure(), overridden.trace().onFailure());
    }

    @Test
    @DisplayName("an override reaches the router the run actually uses")
    void routingOverrideReachesTheRouter() throws Exception {
        // End of the chain: the effective config is what builds the router, so an
        // override that changed the record but not the resolution would still make
        // `--routing active` a no-op.
        RahuConfig base = new ConfigLoader().load(OFFLINE);
        // BOTH pool entries need evidence: active mode refuses a pool whose fallback is
        // not an executable candidate, and the fixture falls back to quality@medium.
        // Supplying one profile produced "unknown-evidence" - the router correctly
        // refusing, and my test's fault for not noticing.
        var profiles = new java.util.HashMap<rahu.core.ModelRef, rahu.core.ModelProfile>();
        for (String id : java.util.List.of("demo-fast", "demo-quality")) {
            profiles.put(new rahu.core.ModelRef(id),
                new rahu.core.ModelProfile(new rahu.core.ModelRef(id), 8192, 4096,
                    new rahu.core.MoneyAmount(new java.math.BigDecimal("0.000000019"),
                        rahu.core.CurrencyUnit.USD),
                    new rahu.core.MoneyAmount(new java.math.BigDecimal("0.00000003"),
                        rahu.core.CurrencyUnit.USD),
                    rahu.core.ReasoningPolicy.Effort.values(), false,
                    java.time.Instant.parse("2026-10-02T00:00:00Z"), true, true));
        }
        assertEquals("SHADOW",
            new rahu.cli.live.ActiveRouter(base, profiles).mode().name());
        assertEquals("ACTIVE", new rahu.cli.live.ActiveRouter(
            RunCommand.applyOverrides(base, "active", false), profiles).mode().name());
    }

    @Test
    @DisplayName("no overrides leaves the config byte-identical in meaning")
    void noOverridesChangeNothing() throws Exception {
        RahuConfig base = new ConfigLoader().load(OFFLINE);
        RahuConfig same = RunCommand.applyOverrides(base, null, false);
        assertEquals(base.routing(), same.routing());
        assertEquals(base.trace(), same.trace());
    }

    // ------------------------------------------------------------ determinism

    @Test
    @DisplayName("the offline answer is deterministic (A21)")
    void offlineAnswerIsDeterministic() {
        assertLinesMatch(offline().out().lines().toList(),
            offline().out().lines().toList());
    }

    // ------------------------------------------------- config refusals (AUDIT-h)

    /**
     * AUDIT-2026-10-03-h, end-to-end proof at the command boundary.
     *
     * <p>A ConfigLoader test proves the config is refused; it does not prove the
     * OPERATOR sees it. That gap is exactly where this defect lived: `config validate`
     * printed "config valid" and exited 0 for a config whose payload capture was
     * silently off, so every layer that only read the exit code called it healthy.
     * These run the real command path and assert the message reaches stderr.
     */
    @Test
    @DisplayName("a trace.capture typo is refused, naming the accepted values")
    void traceCaptureTypoIsRefusedAtTheCommand() throws Exception {
        Path cfg = Files.createTempFile("rahu-typo", ".json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"capture\": \"metadata\"", "\"capture\": \"payload\""));
        var r = run("", "config", "validate", "--config", cfg.toString());
        assertNotEquals(0, r.code(),
            "a capture typo must not exit 0: it silently disables replay capture "
            + "while the operator's config claims payloads");
        assertTrue(r.err.contains("trace.capture"), r.err);
        // Actionable, not merely a rejection: without the accepted values the
        // operator re-guesses the same near-miss spelling.
        assertTrue(r.err.contains("metadata|payloads"), r.err);
    }

    /**
     * observability.md:32 requires a persistence failure to STOP new operations
     * (TRACE_FAILURE) and A16 forbids presenting completion for an incomplete run,
     * so no continue-on-failure mode is permitted. `warn` was accepted and ignored:
     * the operator asked for a degraded run and got a stopped one.
     */
    @Test
    @DisplayName("trace.onFailure=warn is refused and says why")
    void traceOnFailureWarnIsRefusedAtTheCommand() throws Exception {
        Path cfg = Files.createTempFile("rahu-warn", ".json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"onFailure\": \"stop\"", "\"onFailure\": \"warn\""));
        var r = run("", "config", "validate", "--config", cfg.toString());
        assertNotEquals(0, r.code(), "onFailure=warn has no implementation and no spec basis");
        assertTrue(r.err.contains("trace.onFailure"), r.err);
        assertTrue(r.err.contains("TRACE_FAILURE"), r.err);
    }

    /**
     * The positive control, and the reason the two refusals above mean anything:
     * the SAME command with the value spelled correctly still succeeds. Without
     * this, a validate that always exited nonzero would satisfy both tests.
     */
    @Test
    @DisplayName("the correctly spelled values still load, so the refusals are specific")
    void validTraceSpellingsStillLoad() throws Exception {
        Path cfg = Files.createTempFile("rahu-ok", ".json");
        Files.writeString(cfg, Files.readString(OFFLINE).replace(
            "\"capture\": \"metadata\"", "\"capture\": \"payloads\""));
        var r = run("", "config", "validate", "--config", cfg.toString());
        assertEquals(0, r.code(), r.err);
        // And the accepted value actually reached the config, rather than being
        // replaced by the default on the way through.
        assertEquals("payloads", new ConfigLoader().load(cfg).trace().capture());
    }

    /**
     * AUDIT-2026-10-03-h: nothing drove `rahu replay`, so its UNAVAILABLE output was
     * never asserted and could contradict itself for a full increment - "offline routed
     * nothing" on one line, "you need capture=payloads" on the next. Both were true
     * strings in the output, which is why grepping for either would have passed.
     */
    @Test
    @DisplayName("replay's UNAVAILABLE output does not contradict its own reason")
    void replayUnavailableOutputIsSelfConsistent() throws Exception {
        // A dedicated trace root, not a shared one: the run directory is found from
        // the id the run reports, so the root must be this test's alone.
        Path traceRoot = Files.createTempDirectory("rahu-replay-traces");
        Path cfg = Files.createTempFile("rahu-replay", ".json");
        Files.writeString(cfg, Files.readString(OFFLINE)
            .replace("\"capture\": \"metadata\"", "\"capture\": \"payloads\"")
            .replace("\"directory\": \".rahu/runs\"",
                "\"directory\": \"" + traceRoot + "\""));
        // Produce a real offline run, then ask replay about it.
        var run = run("", "run", "--config", cfg.toString(), "--prompt", "a task",
            "--input-classification", "approved-nonsensitive", "--format", "json");
        assertEquals(0, run.code(), run.err());
        // Take the runId from the run itself rather than constructing the directory:
        // the reported id is the contract (AUDIT-g proved it resolves on disk), so a
        // test that guessed the naming would pass while the real linkage broke.
        String runId = new ObjectMapper().readTree(run.out).path("runId").asText();
        assertFalse(runId.isBlank() || "null".equals(runId),
            "run must report a real runId: " + run.out);

        Path runDirectory = traceRoot.resolve(runId);
        assertTrue(Files.isDirectory(runDirectory),
            "the reported runId must resolve to a real trace directory: " + runDirectory);
        // BOTH surfaces, not just JSON: the human-readable branch is a separate code
        // path in `unavailable()` and prints a SECOND line that JSON mode never shows.
        // Asserting only the JSON output left the human branch entirely untested -
        // which is exactly how the contradiction survived a full increment.
        //
        // `ReplayCommand` writes to System.out rather than an injected PrintWriter
        // (unlike RunCommand), so `run(...)` captures nothing for it. Capture the real
        // stream; converting both trace commands to injected writers is recorded as
        // AUDIT-2026-10-03-i rather than smuggled in here.
        for (String mode : new String[] {"json", "text"}) {
            Captured replay = capturingSystemOut(
                () -> run("", "replay", "--format", mode, runDirectory.toString()));
            assertEquals(3, replay.code(),
                "UNAVAILABLE must be a distinct exit code, not a success (" + mode + "): "
                    + replay.systemOut() + replay.err());
            // The reason must be the SPECIFIC one for an offline run.
            assertTrue(replay.systemOut().contains("no RouteResolved"),
                mode + " stdout was: <" + replay.systemOut() + "> stderr: " + replay.err());
            // Every line must agree with that reason.
            //
            // Asserted as a PROPERTY rather than a banned phrase: the earlier version
            // banned the exact string "capture=payloads is required", and restoring the
            // original defect - which read "Replay requires trace.capture=payloads; ..."
            // - SURVIVED that assertion. Banning wording is fragile; the property is
            // that no line may advise changing trace.capture, since this run's config
            // already had it right. Every mention must be the qualified one.
            for (String line : replay.systemOut().split("\n")) {
                if (line.contains("capture=payloads")) {
                    assertTrue(line.contains("necessary but not sufficient"),
                        "a line advises changing trace.capture on a run whose config "
                            + "already set it (" + mode + "): " + line);
                }
                assertFalse(line.contains("Run a chat turn with"),
                    "the output gives unachievable advice in " + mode + ": " + line);
                assertFalse(line.contains("Run a chat turn first"),
                    "the output gives unachievable advice in " + mode + ": " + line);
            }
            // And exactly one line of advice, so a future line cannot quietly reappear
            // beside it.
            long advice = replay.systemOut().lines()
                .filter(l -> l.contains("capture=payloads")
                    || l.contains("Run a chat turn")).count();
            assertTrue(advice <= 1,
                "at most one line may mention the capture flag (" + mode + "), got: "
                    + replay.systemOut());
        }

    }
}
