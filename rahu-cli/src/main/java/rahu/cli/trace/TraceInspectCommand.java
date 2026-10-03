package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.PrintWriter;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import rahu.cli.live.ExitCode;

/**
 * {@code rahu trace inspect RUN_PATH} (cli.md:13): inspect run metadata and
 * completeness.
 *
 * <p>AUDIT-2026-10-03-f. cli.md has documented this since the specs were written.
 * It did not exist — the same finding as {@code replay} (AUDIT-e), one line apart
 * in the same table of the spec.
 *
 * <p>This is the read-only view of a run's <em>evidence</em>, as distinct from the
 * surfaces that answer questions about the <em>run</em>. It reports which events
 * a trace holds, whether the record is complete, and whether a routing outcome
 * was left behind — so an operator can tell "the run degraded" apart from "the
 * trace never recorded what the run did" before trusting anything downstream.
 * That distinction is the reason the command earns its place: both look like a
 * missing answer at the terminal.
 *
 * <h2>What it deliberately does not do</h2>
 * <ul>
 *   <li><b>Dump payloads.</b> cli.md:50 requires that local inspection "must not
 *       dump protected payloads by default". Payloads are read only to lift a
 *       small allowlist of scalars out of them — route ids, terminal reason,
 *       token counts, hashes — and no free-text field is ever printed. There is no
 *       {@code --dump-payloads} option, and adding one later is a privacy
 *       decision, not a convenience.</li>
 *   <li><b>No network, no tools.</b> Reading a run directory is the only I/O. The
 *       same guarantee A11 places on {@code replay}, and the same structural
 *       proof: no provider client, dispatch or tool invocation appears here.</li>
 *   <li><b>Report, do not judge.</b> An incomplete trace exits 5 — per
 *       {@link ExitCode#TRACE_INTEGRITY_FAILURE}, the answer may have been
 *       correct while its evidence is not auditable. The command does not claim
 *       the run failed; only that this file cannot support a claim about the
 *       whole run.</li>
 * </ul>
 */
@Command(name = "inspect",
    mixinStandardHelpOptions = true,
    description = "Inspect a run trace's metadata and completeness. Read-only; makes no"
        + " network or tool calls and never dumps payloads.")
public final class TraceInspectCommand implements java.util.concurrent.Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "RUN_PATH",
        description = "Run directory (containing events.jsonl), that file, or its parent")
    Path runPath;

    @Option(names = "--format", description = "text or json (default text)")
    String format = "text";

    @Option(names = "--verbose",
        description = "Add the per-event timeline (types, sequence, elapsed ms). Still no"
            + " payloads.")
    boolean verbose;

    @Spec
    CommandSpec spec;

    /**
     * Where this command's output goes: picocli's writers, not {@code System.out}.
     *
     * <p>AUDIT-2026-10-03-i. Printing to the real stream meant the test harness
     * captured nothing here either, so a completeness report - the entire point of
     * {@code trace inspect} - could change arbitrarily and stay green.
     */
    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private PrintWriter err() {
        return spec.commandLine().getErr();
    }

    @Override
    public Integer call() {
        Optional<Path> located = RunLocator.locate(runPath, err());
        if (located.isEmpty()) {
            err().println("no run trace under " + runPath + "; expected a run directory"
                + " containing " + TraceFiles.EVENTS + ".");
            if ("json".equals(format)) {
                out().println("{\"status\":\"NOT_FOUND\",\"path\":"
                    + quote(runPath.toString()) + "}");
            }
            return ExitCode.INVALID_INPUT;
        }
        Path directory = located.get();

        List<JsonNode> events;
        try {
            events = readEvents(directory);
        } catch (IOException | RuntimeException e) {
            err().println("trace integrity: " + TraceFiles.EVENTS + " is unreadable: "
                + e.getMessage());
            if ("json".equals(format)) {
                out().println("{\"status\":\"INTEGRITY_FAILURE\",\"reason\":"
                    + quote(e.getMessage()) + "}");
            }
            return ExitCode.TRACE_INTEGRITY_FAILURE;
        }

        // The SAME predicate `rahu replay` uses. Deliberately shared: if inspect
        // called a trace complete that replay refuses (or the reverse), the two
        // commands would disagree about whether the same evidence exists, and the
        // operator would have no way to tell which one to believe.
        Optional<String> integrity = RunLocator.integrityProblem(directory);
        Map<String, Object> report = summarise(directory, events, integrity);

        if ("json".equals(format)) {
            out().println(toJson(report));
        } else {
            out().println(toText(report, events));
        }
        return integrity.isPresent() ? ExitCode.TRACE_INTEGRITY_FAILURE : ExitCode.OK;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Parses the events it can, stopping at the first unparseable line.
     *
     * <p>Deliberately tolerant: a truncated or corrupt line is a finding to
     * <em>report</em>, not a reason to abort the report. Throwing here would mean a
     * trace with one bad line could never be inspected, which is exactly when an
     * operator most needs to look at it. {@link RunLocator#integrityProblem}
     * independently classifies the same line, and this command's exit code comes
     * from that check - so the tolerance cannot make a damaged trace look sound.
     *
     * <p>A sequence gap is <em>not</em> tolerated here: those lines all parse, and
     * the gap is judged by the same shared predicate.
     */
    private List<JsonNode> readEvents(Path directory) throws IOException {
        Path events = directory.resolve(TraceFiles.EVENTS);
        List<JsonNode> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(MAPPER.readTree(line));
            } catch (IOException | RuntimeException e) {
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- summarising

    /**
     * Builds the report from an explicit allowlist of payload fields.
     *
     * <p>Written as an allowlist rather than a filter because a blacklist has to be
     * updated every time a payload grows a new field, and the failure mode of
     * forgetting is printing someone's prompt into a terminal. Here a new field is
     * simply not printed until someone decides it should be.
     */
    private Map<String, Object> summarise(Path directory, List<JsonNode> events,
        Optional<String> integrity) {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runPath", directory.toString());
        report.put("status", integrity.isPresent() ? "INCOMPLETE" : "COMPLETE");
        integrity.ifPresent(reason -> report.put("integrityProblem", reason));
        report.put("eventCount", events.size());

        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode event : events) {
            counts.merge(event.path("type").asText("UNKNOWN"), 1, Integer::sum);
        }
        report.put("eventTypes", counts);

        if (!events.isEmpty()) {
            JsonNode first = events.get(0);
            JsonNode last = events.get(events.size() - 1);
            report.put("runId", first.path("runId").asText(""));
            report.put("schemaVersion", first.path("schemaVersion").asInt(0));
            report.put("startedAt", first.path("timestamp").asText(""));
            report.put("elapsedMs", last.path("elapsedMs").asLong(0));
        }

        // Allowlisted scalars only. Everything here is an identifier, an enum or a
        // count; none of it is content.
        firstOf(events, "RouteResolved").ifPresent(node -> {
            JsonNode payload = node.path("payload");
            Map<String, Object> route = new LinkedHashMap<>();
            put(route, "suggested", payload.path("suggested").textValue());
            put(route, "executed", payload.path("executed").textValue());
            route.put("mode", payload.path("mode").textValue());
            route.put("degraded", payload.path("degraded").asBoolean(false));
            put(route, "fallbackCause", payload.path("fallbackCause").textValue());
            put(route, "terminalReason", payload.path("terminalReason").textValue());
            report.put("route", route);
        });
        firstOf(events, "RunTerminated").ifPresent(node -> {
            JsonNode payload = node.path("payload");
            Map<String, Object> terminated = new LinkedHashMap<>();
            put(terminated, "reason", payload.path("reason").textValue());
            terminated.put("generationSteps", payload.path("generationSteps").asInt(0));
            report.put("terminated", terminated);
        });
        firstOf(events, "ModelCompleted").ifPresent(node -> {
            JsonNode usage = node.path("payload").path("usage");
            Map<String, Object> usageOut = new LinkedHashMap<>();
            // AUDIT-2026-10-03-j. These used to be asInt(0), which silently reported
            // UNOBSERVED usage as a measured zero. A reader cannot tell a free request
            // from a provider that never returned counts, and a cost report that
            // flatters itself is worse than one that admits ignorance. A JSON null is
            // now emitted for "not recorded", so the absence is explicit.
            usageOut.put("promptTokens", intOrNull(usage.path("promptTokens")));
            usageOut.put("completionTokens", intOrNull(usage.path("completionTokens")));
            report.put("usage", usageOut);
        });

        // Whether a replay is even possible. Reported as a fact about the run
        // directory, not an attempt at the replay itself.
        report.put("capturePresent", Files.isRegularFile(
            directory.resolve(TraceFiles.CAPTURE)));
        return report;
    }

    private static Optional<JsonNode> firstOf(List<JsonNode> events, String type) {
        for (JsonNode event : events) {
            if (type.equals(event.path("type").asText(""))) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    /** A recorded count, or null when the run never observed one. */
    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    // -------------------------------------------------------------------- output

    private String toText(Map<String, Object> report, List<JsonNode> events) {
        StringBuilder out = new StringBuilder();
        out.append("trace: ").append(report.get("status")).append('\n');
        out.append("  run ").append(report.get("runPath")).append('\n');
        if (report.containsKey("integrityProblem")) {
            out.append("  problem: ").append(report.get("integrityProblem")).append('\n');
        }
        out.append("  ").append(report.get("eventCount")).append(" event(s)");
        out.append("  capture=").append(report.get("capturePresent")).append('\n');
        @SuppressWarnings("unchecked")
        Map<String, Integer> types = (Map<String, Integer>) report.get("eventTypes");
        if (!types.isEmpty()) {
            out.append("  types: ");
            types.forEach((type, count) -> out.append(type).append('=').append(count).append(' '));
            out.append('\n');
        }
        if (report.containsKey("route")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> route = (Map<String, Object>) report.get("route");
            out.append("  route: ").append(route).append('\n');
        }
        if (report.containsKey("terminated")) {
            out.append("  terminated: ").append(report.get("terminated")).append('\n');
        }
        if (verbose) {
            out.append("  timeline:\n");
            for (JsonNode event : events) {
                out.append("    #").append(event.path("sequence").asInt(0))
                    .append(' ').append(event.path("type").asText("?"))
                    .append(" +").append(event.path("elapsedMs").asLong(0)).append("ms\n");
            }
        }
        return out.toString().stripTrailing();
    }

    private String toJson(Map<String, Object> report) {
        var node = MAPPER.createObjectNode();
        report.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                var child = node.putObject(key);
                map.forEach((k, v) -> child.set(String.valueOf(k),
                    MAPPER.valueToTree(v instanceof String s && "null".equals(s) ? null : v)));
            } else {
                node.set(key, MAPPER.valueToTree(value));
            }
        });
        return node.toString();
    }

    private static String quote(String raw) {
        // See ReplayCommand.quote: an escaping failure must never become an
        // unhandled throw inside error reporting, where it masks the real error.
        String value = raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}