package rahu.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.cli.live.ExitCode;
import rahu.cli.live.LiveAssembly;
import rahu.cli.live.LiveTurnDriver;
import rahu.cli.live.TurnOutcome;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.privacy.SafeView;
import rahu.core.model.ChatMessage;
import rahu.core.context.PromptAssembler;

/**
 * One bounded user task (cli.md:11).
 *
 * <p>Audit finding AUDIT-2026-10-03-g: this command was documented but absent - the
 * last gap in the command table, which is why {@code G06} could not honestly be marked
 * PASSED. Everything below is spec-mandated behaviour rather than design freedom:
 *
 * <ul>
 *   <li>{@code --prompt -} reads the task from stdin (cli.md:11).
 *   <li>Routing inherits shadow unless {@code --routing active} (cli.md:13).
 *   <li>{@code --capture-payloads} opts THIS run into bounded private capture;
 *       metadata remains the default (cli.md:17).
 *   <li>Answer to stdout, route/warnings/progress to stderr (cli.md:13).
 *   <li>{@code --format json} emits ONE final structured document on stdout and never
 *       mixes progress lines into it (cli.md:13). This is the reason the live driver
 *       returns a {@link TurnOutcome} instead of an {@code int}: scraping the human
 *       stderr trail to build machine output would make the JSON a function of
 *       phrasing.
 *   <li>A fresh single-turn session that cannot implicitly resume a prior run path
 *       (cli.md:27).
 * </ul>
 *
 * <p>The single-turn contract is enforced by construction, not by convention: the
 * command builds a driver with no slash handler and calls the turn directly. There is
 * no loop here for a second turn to enter.
 */
@Command(name = "run",
    mixinStandardHelpOptions = true,
    description = "One bounded user task; supports stdin via `--prompt -`.")
public final class RunCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "Config JSON path")
    Path config;

    @Option(names = "--prompt", required = true,
        description = "Task text, or `-` to read the task from stdin")
    String prompt;

    @Option(names = "--routing",
        description = "off | shadow | active (default: the config's mode)")
    String routing;

    @Option(names = "--capture-payloads",
        description = "Enable bounded private routing-input capture for THIS run")
    boolean capturePayloads;

    @Option(names = "--format", description = "text | json (default text)")
    String format;

    @Option(names = "--input-classification",
        description = "unknown | approved-nonsensitive (records assessment; never a bypass)")
    String inputClassification;

    @Spec
    CommandSpec spec;

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private PrintWriter err() {
        return spec.commandLine().getErr();
    }

    @Override
    public Integer call() {
        if (!List.of("text", "json").contains(format == null ? "text" : format)) {
            err().println("--format must be text or json, not \"" + format + "\"");
            return ExitCode.INVALID_INPUT;
        }
        if (routing != null && !List.of("off", "shadow", "active").contains(routing)) {
            err().println("--routing must be off, shadow or active, not \"" + routing + "\"");
            return ExitCode.INVALID_INPUT;
        }

        String task;
        try {
            task = readTask();
        } catch (IOException e) {
            err().println("could not read the task from stdin: " + e.getMessage());
            return ExitCode.INVALID_INPUT;
        }
        if (task.isBlank()) {
            // A blank task is not a run. Accepting it would produce an empty answer that
            // looks like a completed turn and burn a paid call to do it.
            err().println("the task is empty; supply text via --prompt or `--prompt -`");
            return ExitCode.INVALID_INPUT;
        }

        RahuConfig cfg;
        try {
            cfg = applyOverrides(new ConfigLoader().load(config), routing, capturePayloads);
        } catch (ConfigError e) {
            err().println("config invalid: " + e.getMessage());
            return ExitCode.INVALID_INPUT;
        }

        return "offline".equals(cfg.mode())
            ? runOffline(cfg, task) : runLive(cfg, task);
    }

    /** The task text; `-` means stdin, and stdin is read exactly once. */
    private String readTask() throws IOException {
        if (!"-".equals(prompt)) {
            return prompt;
        }
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Applies the per-run overrides to the loaded config.
     *
     * <p>Static and parameterised so a test can call it directly: an override's only
     * observable effect is on the returned config, so a test that cannot reach the
     * method cannot tell a working override from one that is accepted and discarded -
     * which is exactly what {@code confidenceField} and {@code tools.enabled} did.
     *
     * <p>Overrides are applied HERE, after loading and before any assembly, so every
     * consumer - router, trace writer, config hash - sees one consistent effective
     * configuration. Applying them later would let the trace's config hash describe a
     * configuration the run did not use (AUDIT-b's config-hash rationale: two runs are
     * distinguishable exactly when their EFFECTIVE configuration differs).
     */
    static RahuConfig applyOverrides(RahuConfig cfg, String routing, boolean capturePayloads) {
        RahuConfig result = cfg;
        if (routing != null) {
            var r = cfg.routing();
            result = new RahuConfig(cfg.schemaVersion(), cfg.mode(), cfg.decision(),
                cfg.generation(),
                new RahuConfig.RoutingConfig(routing, r.pool(), r.baseline(), r.fallback(),
                    r.confidenceField(), r.confidenceFloor(), r.maximumCandidates()),
                cfg.pools(), cfg.summarisation(), cfg.agent(), cfg.catalog(), cfg.tools(),
                cfg.trace(), cfg.context(), cfg.session(), cfg.orchestration(), cfg.privacy(),
                cfg.injection(), cfg.search());
        }
        if (capturePayloads) {
            // Opt-in for this run only. Default stays metadata, and this does NOT grant
            // any outbound clearance (cli.md:48): it permits local storage of routing
            // inputs, which are already gated by the privacy checks.
            var t = cfg.trace();
            result = new RahuConfig(cfg.schemaVersion(), cfg.mode(), cfg.decision(),
                cfg.generation(), cfg.routing(), cfg.pools(), cfg.summarisation(), cfg.agent(),
                cfg.catalog(), cfg.tools(),
                new RahuConfig.TraceConfig(t.directory(), "payloads", t.onFailure()),
                cfg.context(), cfg.session(), cfg.orchestration(), cfg.privacy(),
                cfg.injection(), cfg.search());
        }
        return result;
    }

    // ----------------------------------------------------------------- offline

    /**
     * Offline mode drives {@link rahu.cli.live.OfflineTurnDriver} with a single
     * in-memory line, rather than composing an answer here.
     *
     * <p>AUDIT-2026-10-03-g: the first version inlined the offline answer, which
     * produced NO trace at all while {@code chat} offline wrote RunStarted +
     * RunTerminated. That is the AUDIT-a shape exactly - a code path that answers
     * without leaving evidence - and it was invisible because nothing asserted that an
     * offline {@code run} leaves a trace. Reusing the driver makes the privacy gate,
     * the trace and the fixed answer impossible to omit, because they live in the one
     * place both commands already share.
     *
     * <p>One line, no EOF banner: {@code run} is a bounded task (cli.md:15), so it must
     * not print chat's end-of-session message or accept a second line.
     */
    private Integer runOffline(RahuConfig cfg, String task) {
        var session = new rahu.core.context.SessionState("rahu-run-offline-" + System.nanoTime(),
            cfg.session().maxTurns(),
            new rahu.core.MoneyAmount(cfg.session().maxCostUsd(),
                rahu.core.CurrencyUnit.USD));
        // One line, then end-of-input: the driver reads until the supplier is empty,
        // which is how a bounded task is expressed through a loop built for many.
        java.util.Iterator<String> single = java.util.List.of(task).iterator();
        java.util.function.Supplier<java.util.Optional<String>> oneLine =
            () -> single.hasNext() ? java.util.Optional.of(single.next())
                : java.util.Optional.empty();

        // A genuinely NULL slash handler: no interactive commands in a bounded run
        // (cli.md:27), so a `/`-prefixed task is the task TEXT. Passing `line -> null`
        // looked equivalent and was not - the driver saw a handler, entered the slash
        // branch, and skipped the turn entirely.
        var driver = new rahu.cli.live.OfflineTurnDriver(cfg, session, provenance(cfg),
            null, out(), err(), oneLine, false, !json());

        int code = driver.runWithoutEndOfInputBanner();
        if (json()) {
            // The answer is suppressed at the driver, so this document is the ONLY thing
            // on stdout. runId comes from the driver, which actually wrote the trace -
            // reconstructing it here would report an id that resolves to nothing.
            out().println(renderOfflineJson(driver.writtenRunId().orElse(null), code));
        }
        return code;
    }

    /**
     * The offline JSON document, with the SAME keys as the live one.
     *
     * <p>A schema that changes shape with the mode is a schema no consumer can rely on:
     * automation would need two parsers and would discover the difference as a KeyError
     * in someone else's pipeline. Values differ honestly - {@code runId} is null because
     * offline writes no run trace, and {@code answer} is null because the fixed text
     * went to stdout in text mode.
     */
    private String renderOfflineJson(String runId, int exitCode) {
        String status = exitCode == ExitCode.OK ? "COMPLETE" : "BLOCKED";
        return "{\"status\":\"" + status + "\",\"exitCode\":" + exitCode
            + ",\"answer\":null,\"routing\":null,\"cost\":null,"
            + "\"generationSteps\":0,\"runId\":" + (runId == null ? "null" : "\"" + runId + "\"")
            + "}";
    }

    // -------------------------------------------------------------------- live

    private Integer runLive(RahuConfig cfg, String task) {
        var writers = new LiveAssembly.PrintWriters(out(), err());
        var built = LiveAssembly.build(cfg, provenance(cfg), "run", writers);
        if (built instanceof LiveAssembly.Result.Failure failure) {
            err().println(failure.refusal().operation() + " failed: "
                + failure.refusal().message());
            return ExitCode.INVALID_INPUT;
        }
        var stack = ((LiveAssembly.Result.Built) built).assembly();
        LiveTurnDriver driver = stack.driver(writers);

        var gate = new PrivacyGate();
        var assembler = new PromptAssembler();
        int allowance = cfg.context().maxPromptTokens() == null
            ? 8192 : cfg.context().maxPromptTokens();
        int maxTokens = cfg.agent().maxCompletionTokens() == null
            ? 2048 : cfg.agent().maxCompletionTokens();
        BigDecimal perRunCap = cfg.agent().maxCostUsd() == null
            ? BigDecimal.ZERO : cfg.agent().maxCostUsd();

        // JSON mode sends the human trail to stderr only, so stdout carries exactly one
        // document. The answer is suppressed at the source in that mode rather than
        // printed then filtered, because filtering a stream cannot un-print it.
        if (json()) {
            err().println("rahu run — routing " + stack.routerMode()
                + ", candidates " + stack.router().candidates().candidates().size()
                + ", decision " + cfg.decision().model());
            TurnOutcome outcome = driver.turnSuppressingAnswer(task, gate, assembler,
                allowance, maxTokens, perRunCap);
            out().println(renderJson(outcome));
            return outcome.exitCode();
        }

        TurnOutcome outcome = driver.turn(task, gate, assembler, allowance, maxTokens,
            perRunCap);
        footer(outcome);
        return outcome.exitCode();
    }

    /**
     * The stderr footer (cli.md:29-33).
     *
     * <p>Shows {@code cost unavailable} rather than a zero when no cost was reported: an
     * unreported cost on a dispatched request is UNCERTAIN (A10), and rendering it as
     * {@code $0.00} asserts the provider billed nothing. The sample values in cli.md are
     * formatting examples and must never be printed as measurements (cli.md:35).
     */
    private void footer(TurnOutcome outcome) {
        var route = outcome.routing();
        if (route.isPresent()) {
            err().println("Route " + route.get().executedId()
                + " · " + route.get().mode().toLowerCase(java.util.Locale.ROOT)
                + (route.get().suggestedId() == null ? "" : " (suggested "
                    + route.get().suggestedId() + ")")
                + (route.get().degraded() ? " · degraded: "
                    + (route.get().fallbackCause() == null ? "unknown"
                        : route.get().fallbackCause()) : ""));
        }
        err().println("Cost " + (outcome.costUnobserved() ? "unavailable"
            : "$" + outcome.usage().get().totalCostMicrosOpt().map(
                micros -> BigDecimal.valueOf(micros, 6).toPlainString()).orElse("unavailable"))
            + " · " + outcome.generationSteps() + " generation step"
            + (outcome.generationSteps() == 1 ? "" : "s")
            + " · " + (outcome.elapsedMs() / 1000.0) + " s · trace "
            + outcome.runId().orElse("unwritten"));
    }

    /**
     * The single JSON document (cli.md:13).
     *
     * <p>Includes the typed status, and never content or snippets of a blocked input
     * (cli.md:48). A privacy block reports its category only.
     */
    // Package-private (not private) so RunCommandTest can render a document with an
    // OBSERVED cost. The offline engine records no usage, so the "reported as a
    // number" half of the unobserved-vs-zero property is otherwise unreachable -
    // and an encoding that always emits null would pass the other half while
    // destroying the feature.
    String renderJson(TurnOutcome outcome) {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"").append(jsonEscape(outcome.terminalReason()))
            .append("\",\"exitCode\":").append(outcome.exitCode())
            .append(",\"answer\":");
        if (outcome.answer().isPresent()) {
            json.append('"').append(jsonEscape(outcome.answer().get())).append('"');
        } else {
            json.append("null");
        }
        json.append(",\"routing\":");
        if (outcome.routing().isPresent()) {
            var r = outcome.routing().get();
            json.append("{\"suggested\":").append(quoted(r.suggestedId()))
                .append(",\"executed\":").append(quoted(r.executedId()))
                .append(",\"mode\":").append(quoted(r.mode()))
                .append(",\"degraded\":").append(r.degraded())
                .append(",\"fallbackCause\":").append(quoted(r.fallbackCause()))
                .append(",\"excludedCandidates\":").append(r.excludedCandidates())
                .append('}');
        } else {
            json.append("null");
        }
        json.append(",\"cost\":");
        if (outcome.costUnobserved()) {
            json.append("null");
        } else {
            // AUDIT-2026-10-03-j: same unobserved-as-zero conflation as the inspect
            // report. summary.json is consumed by other tools, so a fabricated 0 here
            // propagates further than the human-facing one did.
            json.append("{\"micros\":")
                .append(orNull(outcome.usage().get().totalCostMicrosOpt()))
                .append(",\"promptTokens\":")
                .append(orNull(outcome.usage().get().promptTokensOpt()))
                .append(",\"completionTokens\":")
                .append(orNull(outcome.usage().get().completionTokensOpt()))
                .append('}');
        }
        json.append(",\"generationSteps\":").append(outcome.generationSteps())
            .append(",\"runId\":").append(quoted(outcome.runId().orElse(null)))
            .append('}');
        return json.toString();
    }

    /** The value, or the JSON literal null when the provider never reported one. */
    private static String orNull(java.util.Optional<?> value) {
        return value.map(String::valueOf).orElse("null");
    }

    private static String quoted(String value) {
        return value == null ? "null" : '"' + jsonEscape(value) + '"';
    }

    /**
     * Escapes for a JSON string literal.
     *
     * <p>Written by hand rather than pulled in because the only JSON this project emits
     * is this document and the alternatives are a dependency or an unsafe hand-rolled
     * concatenation - and a model answer containing a quote or a newline must not be
     * able to produce invalid JSON for the automation that requested it.
     */
    /**
     * Package-visible so its contract can be asserted directly. A private escaper
     * would only be reachable by reflection, and a test that reflects is a test that
     * keeps passing after the method is renamed out from under it.
     */
    static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private boolean json() {
        return "json".equals(format);
    }

    private Provenance provenance(RahuConfig cfg) {
        return LiveAssembly.provenance(cfg, inputClassification);
    }
}
