package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import rahu.cli.live.ExitCode;

/**
 * {@code rahu replay RUN_PATH} (cli.md:14): offline policy replay of a captured
 * run.
 *
 * <p>AUDIT-2026-10-03-e. cli.md has documented this command since the specs were
 * written, {@link ReplayEngine} existed and was unit-tested, and the command did
 * not. Nothing in {@code src/main} referenced {@code ReplayEngine} or
 * {@code ReplayOutcome}; there was also no {@code trace inspect}. G06 was
 * recorded PASSED on tests whose subject production code never called, which is
 * how a subsystem can be green and absent at the same time.
 *
 * <p>What this command is, per observability.md:34-38 — <em>policy</em> replay.
 * The frozen routing inputs are read back and pushed through the same
 * {@link rahu.core.routing.RouteResolver} the live turn used. The resolution is
 * re-derived, never read from a stored answer, so a replay can disagree with the
 * live trace and say so. This is emphatically not live rerun (which is billable)
 * and not crash recovery (which replay cannot do).
 *
 * <h2>Guarantees this command makes</h2>
 * <ul>
 *   <li><b>No network, no tools.</b> There is no provider client, no dispatch and
 *       no filesystem write anywhere in this class or in {@link ReplayEngine}. The
 *       only I/O is reading the run directory. A11 requires this and it holds by
 *       construction rather than by discipline.</li>
 *   <li><b>UNAVAILABLE is a real outcome.</b> With no capture — the default
 *       metadata capture — the command reports replay unavailable and exits 3. It
 *       never fabricates a resolution, because a fabricated replay is worse than
 *       no replay: it looks like evidence.</li>
 *   <li><b>No protected payloads.</b> The capture holds only routing inputs, so
 *       there is nothing protected to dump. cli.md:50's "must not dump protected
 *       payloads by default" is satisfied structurally.</li>
 *   <li><b>A disagreeing replay is not a failure.</b> It exits 0 and reports the
 *       difference. Exit 5 is reserved for integrity problems with the trace or
 *       capture itself (A16 territory), per cli.md:42.</li>
 * </ul>
 */
@Command(name = "replay",
    mixinStandardHelpOptions = true,
    description = "Offline policy replay of a captured run. Makes no network or tool"
        + " calls; reports unavailable when the run captured no routing inputs.")
public final class ReplayCommand implements java.util.concurrent.Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "RUN_PATH",
        description = "Run directory (containing events.jsonl) or its parent")
    Path runPath;

    @Option(names = "--format", description = "text or json (default text)")
    String format = "text";

    @Override
    public Integer call() {
        Optional<Path> runDirectory = resolveRunDirectory();
        if (runDirectory.isEmpty()) {
            System.err.println("no run trace under " + runPath
                + "; expected a run directory containing events.jsonl."
                + " Run a chat turn with trace.capture=payloads first.");
            return ExitCode.NO_ROUTE_OR_LIMIT_OR_PRIVACY;
        }
        Path directory = runDirectory.get();

        // Integrity first: a replay over a trace that failed to write, or that has
        // no terminal record, would re-derive a policy decision from an incomplete
        // record and report it as if the run had completed. That is a different
        // claim from what happened (A16, cli.md:42 exit 5).
        Optional<String> integrity = integrityProblem(directory);
        if (integrity.isPresent()) {
            System.err.println("trace integrity: " + integrity.get()
                + "; refusing to replay an incomplete run");
            if ("json".equals(format)) {
                System.out.println("{\"status\":\"INTEGRITY_FAILURE\",\"reason\":"
                    + quote(integrity.get()) + "}");
            }
            return ExitCode.TRACE_INTEGRITY_FAILURE;
        }

        ReplayCapture.Frozen frozen;
        try {
            frozen = ReplayCapture.read(directory);
        } catch (IOException | IllegalArgumentException e) {
            // Missing capture is the expected default-metadata case and gets the
            // UNAVAILABLE shape. A malformed capture is a different thing: someone
            // edited a file that claims to be evidence.
            if (e instanceof java.io.FileNotFoundException) {
                return unavailable(e.getMessage());
            }
            System.err.println("capture unusable: " + e.getMessage());
            if ("json".equals(format)) {
                System.out.println("{\"status\":\"CAPTURE_UNUSABLE\",\"reason\":"
                    + quote(e.getMessage()) + "}");
            }
            return ExitCode.TRACE_INTEGRITY_FAILURE;
        }

        ReplayOutcome outcome = ReplayEngine.replay(frozen);

        if (outcome.status() == ReplayOutcome.ReplayStatus.UNAVAILABLE) {
            return unavailable(outcome.unavailableReason());
        }

        // Compare against what the live trace recorded, so the operator sees
        // agreement or disagreement rather than a bare re-derivation.
        Optional<RouteResolvedRecord> live = recordedRoute(directory);
        boolean agrees = live.map(r -> agreesWith(r, outcome)).orElse(true);

        if ("json".equals(format)) {
            System.out.println(json(outcome, live, agrees));
        } else {
            System.out.println(text(outcome, live, agrees));
        }
        return ExitCode.OK;
    }

    private int unavailable(String reason) {
        if ("json".equals(format)) {
            System.out.println("{\"status\":\"UNAVAILABLE\",\"reason\":" + quote(reason) + "}");
        } else {
            System.out.println("replay unavailable: " + reason);
            System.out.println("Replay requires trace.capture=payloads; the default"
                + " metadata capture records no routing inputs. Nothing was"
                + " reconstructed or guessed.");
        }
        return ExitCode.NO_ROUTE_OR_LIMIT_OR_PRIVACY;
    }

    // ------------------------------------------------------------------ locate

    /**
     * Finds the run directory from either a run directory or the trace root.
     *
     * <p>Accepting the parent is convenience, not guesswork: the id is resolved by
     * requiring a directory that actually contains events.jsonl, and if several
     * qualify the caller is told which rather than being handed one of them.
     */
    private Optional<Path> resolveRunDirectory() {
        return RunLocator.locate(runPath);
    }

    // --------------------------------------------------------------- integrity

    /** What the live trace recorded for RouteResolved, for the agreement report. */
    private record RouteResolvedRecord(String suggested, String executed, boolean degraded,
        String terminalReason) {
    }

    /**
     * Detects a trace that cannot support a faithful replay.
     *
     * <p>Two checks, both about completeness rather than content: a RunStarted
     * must exist (an empty file is not a run) and a terminal record must exist (a
     * run that stopped mid-turn has no final routing outcome to compare against).
     * A trace written by a failed sink is caught by the terminal check, which is
     * why A16's "stop without a terminal record" matters here.
     */
    private Optional<String> integrityProblem(Path directory) {
        return RunLocator.integrityProblem(directory);
    }

    private Optional<RouteResolvedRecord> recordedRoute(Path directory) {
        try {
            for (String line : Files.readAllLines(directory.resolve(TraceFiles.EVENTS),
                StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                if (!"RouteResolved".equals(node.path("type").asText(""))) {
                    continue;
                }
                JsonNode payload = node.path("payload");
                return Optional.of(new RouteResolvedRecord(
                    payload.path("suggested").textValue(),
                    payload.path("executed").textValue(),
                    payload.path("degraded").asBoolean(false),
                    payload.path("fallbackCause").textValue()));
            }
        } catch (IOException | RuntimeException e) {
            // A missing or unreadable live record is not fatal: the replay itself is
            // still valid, it just cannot be compared. Say so rather than implying
            // agreement.
            System.err.println("note: could not read the recorded route for comparison: "
                + e.getMessage());
        }
        return Optional.empty();
    }

    private boolean agreesWith(RouteResolvedRecord live, ReplayOutcome outcome) {
        return java.util.Objects.equals(live.suggested(), outcome.suggestedId().orElse(null))
            && java.util.Objects.equals(live.executed(), outcome.executedId().orElse(null))
            && live.degraded() == outcome.degraded();
    }

    // ------------------------------------------------------------------ output

    private String text(ReplayOutcome outcome, Optional<RouteResolvedRecord> live,
        boolean agrees) {

        StringBuilder out = new StringBuilder("replay: REPLAYED\n");
        out.append("  suggested=").append(orDash(outcome.suggestedId()))
            .append(" executed=").append(orDash(outcome.executedId()))
            .append(" degraded=").append(outcome.degraded());
        outcome.fallbackCause().ifPresent(c -> out.append(" fallback=").append(c));
        outcome.terminalReason().ifPresent(t -> out.append(" terminal=").append(t));
        out.append('\n');
        if (live.isEmpty()) {
            out.append("  no recorded RouteResolved to compare; replay is not an"
                + " agreement claim\n");
        } else {
            out.append("  live recorded suggested=").append(orDash(java.util.Optional.ofNullable(
                    live.get().suggested())))
                .append(" executed=").append(orDash(java.util.Optional.ofNullable(
                    live.get().executed())))
                .append(" degraded=").append(live.get().degraded()).append('\n');
            out.append("  agrees=").append(agrees).append(agrees
                ? ""
                : "  (policy changed, or the capture and the trace disagree)");
        }
        out.append("  no network or tool calls were made; this re-derived the routing"
            + " policy only");
        return out.toString();
    }

    private String json(ReplayOutcome outcome, Optional<RouteResolvedRecord> live,
        boolean agrees) {
        var node = MAPPER.createObjectNode();
        node.put("status", outcome.status().name());
        node.put("suggested", outcome.suggestedId().orElse(null));
        node.put("executed", outcome.executedId().orElse(null));
        node.put("degraded", outcome.degraded());
        node.put("fallbackCause", outcome.fallbackCause().orElse(null));
        node.put("terminalReason", outcome.terminalReason().orElse(null));
        node.put("agreesWithRecordedRoute", live.isEmpty() ? null : agrees);
        node.put("networkCalls", 0);
        node.put("toolCalls", 0);
        return node.toString();
    }

    private static String orDash(Optional<String> value) {
        return value.orElse("-");
    }

    private static String quote(String raw) {
        // writeValueAsString on a String cannot fail in practice, but it declares a
        // checked exception. Building the escaped literal by hand keeps the helper
        // total: an escaping failure must never turn into an unhandled throw inside
        // error reporting, where it would mask the error being reported.
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