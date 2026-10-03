package rahu.cli.live;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.function.Function;
import java.util.function.Supplier;

import rahu.cli.trace.TurnTrace;
import rahu.core.context.SessionState;
import rahu.core.model.ChatMessage;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.privacy.SafeView;

/**
 * The offline chat loop: fake provider, no network, no model call.
 *
 * <p>Audit finding AUDIT-2026-10-03-b. Two spec violations lived in
 * {@code ChatCommand.runOffline}, both verified against a packaged build rather
 * than inferred from reading it:
 *
 * <ol>
 * <li><b>No privacy gate.</b> The loop echoed the raw input straight back to
 * stdout. Piping {@code my email is bob@example.com and my card is
 * 4111111111111111} printed both values verbatim and exited 0, while the live
 * loop blocked the same input. cli.md is explicit that "safe diagnostics and
 * provenance checks apply even when no model call is planned", and
 * configuration.md makes offline the DEFAULT mode - so the least protected path
 * was the default one, and the one a new user meets first.
 *
 * <li><b>No trace at all.</b> product.md makes a complete
 * route/tool/termination trace the first thing a new user sees, in offline mode,
 * specifically so the concepts can be learned without API keys. The first-run
 * experience the spec describes was unreachable under the default
 * configuration.
 * </ol>
 *
 * <p>Extracted from {@code ChatCommand} so the wiring is directly testable. A
 * loop built inline in a command body reads stdin from {@code System.in} and
 * cannot be driven from a test, which is how both defects survived a green
 * suite: {@link RunTraceWiringTest} covered the live driver and nothing covered
 * this one. Line source is injected so a test supplies its own input.
 *
 * <p>What the trace deliberately does NOT claim: offline mode resolves no route
 * and calls no model, so it records no {@code RouteResolved} and no
 * {@code ModelRequested}. Writing a synthetic route would reproduce exactly the
 * {@code "configHash":"synthetic"} events this audit used to discredit the four
 * demo traces. The events recorded are the ones that are true.
 */
public final class OfflineTurnDriver {

    private final rahu.cli.config.RahuConfig cfg;
    private final SessionState session;
    private final Provenance provenance;
    private final Function<String, Integer> slashHandler;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Supplier<java.util.Optional<String>> lines;
    private final boolean interactive;

    /**
     * Whether the answer is printed to stdout.
     *
     * <p>AUDIT-2026-10-03-g: {@code run --format json} requires one document on stdout
     * and nothing else. The answer line cannot be filtered back out of a stream after it
     * is printed, so suppression has to happen where the print happens.
     */
    private final boolean printAnswer;

    /**
     * When false, no input is ever treated as a slash command.
     *
     * <p>AUDIT-2026-10-03-g: {@code run} passed {@code line -> null} to mean "no
     * commands", but a non-null handler still entered the slash branch, and the
     * driver's null-means-continue path then SKIPPED the turn. {@code rahu run --prompt
     * /status} therefore exited 0 having answered nothing, printed nothing and written
     * no trace - indistinguishable from a run that never happened, and invisible
     * because exit 0 reads as success.
     *
     * <p>An explicit flag rather than a null check: a null handler is a value a caller
     * can supply by accident, and the accident is silent.
     */
    private final boolean slashCommandsEnabled;

    public OfflineTurnDriver(rahu.cli.config.RahuConfig cfg, SessionState session,
        Provenance provenance, Function<String, Integer> slashHandler, PrintWriter out,
        PrintWriter err, Supplier<java.util.Optional<String>> lines, boolean interactive) {
        this(cfg, session, provenance, slashHandler, out, err, lines, interactive, true);
    }

    /** As above, with explicit control over whether the answer reaches stdout. */
    public OfflineTurnDriver(rahu.cli.config.RahuConfig cfg, SessionState session,
        Provenance provenance, Function<String, Integer> slashHandler, PrintWriter out,
        PrintWriter err, Supplier<java.util.Optional<String>> lines, boolean interactive,
        boolean printAnswer) {
        this.printAnswer = printAnswer;
        this.slashCommandsEnabled = slashHandler != null;
        this.cfg = cfg;
        this.session = session;
        this.provenance = provenance;
        this.slashHandler = slashHandler;
        this.out = out;
        this.err = err;
        this.lines = lines;
        this.interactive = interactive;
    }

    /** Runs to end-of-input or a slash-command exit; returns the process exit code. */
    public int run() {
        return runLoop(true);
    }

    /**
     * The run id this driver actually wrote a trace under, once a turn has begun.
     *
     * <p>Reported rather than reconstructed: {@code run --format json} needs the trace
     * reference an operator will use to inspect the run, and inventing one from the
     * session id would produce an id that resolves to nothing. Empty before the first
     * turn, which is honest - nothing has been written yet.
     */
    public java.util.Optional<String> writtenRunId() {
        return java.util.Optional.ofNullable(lastRunId);
    }

    private String lastRunId;

    /**
     * The result of the last turn this driver ran, as data rather than an exit code.
     *
     * <p>AUDIT-2026-10-03-k. This driver returned a bare {@code int} from every exit
     * point, so {@code runOffline} could not reach {@code footer()} and had to
     * hardcode {@code routing:null,cost:null} as literal constants. That is honest by
     * accident rather than by observation: the moment this path resolves a route or
     * records usage, the document would still claim null. Mirrors
     * {@link LiveTurnDriver#lastOutcome()}.
     */
    public java.util.Optional<TurnOutcome> lastOutcome() {
        return java.util.Optional.ofNullable(lastOutcome);
    }

    private TurnOutcome lastOutcome;
    private String lastAnswer;
    private int completedTurns;

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * Records the outcome and returns the exit code, so every return site carries its
     * own result and none can forget to.
     */
    private int outcome(int exitCode, String terminalReason, String answer,
        String runId, int generationSteps, long elapsedMs) {
        lastOutcome = new TurnOutcome(exitCode, terminalReason,
            java.util.Optional.ofNullable(answer),
            java.util.Optional.ofNullable(runId),
            java.util.Optional.empty(),   // routing: resolved by the live path only
            java.util.Optional.empty(),   // usage: no model is called offline
            generationSteps, elapsedMs);
        return exitCode;
    }

    /**
     * The same loop without chat's end-of-input banner.
     *
     * <p>{@code run} is a bounded task (cli.md:15), so it must not print
     * "eof: chat ended..." or accept further lines. The banner is a chat affordance
     * about a session that is ending, and printing it after one task implies a
     * conversation that did not happen.
     */
    public int runWithoutEndOfInputBanner() {
        return runLoop(false);
    }

    private int runLoop(boolean announceEnd) {
        long startedAtNanos = System.nanoTime();
        var gate = new PrivacyGate();
        var traceConfig = TurnTrace.forSession(Path.of(cfg.trace().directory()), cfg, session);

        if (announceEnd) {
            err.println("rahu chat (offline) — /status /reset /exit, EOF to end");
        }
        while (true) {
            if (interactive) {
                err.print("> ");
                err.flush();
            }
            // Read the line ONCE. An earlier draft called lines.get() twice - once
            // for hasNextLine-style probing and once for the value - which consumed
            // two lines per iteration and silently dropped every other input.
            java.util.Optional<String> next = lines.get();
            if (!next.isPresent()) {
                break;
            }
            String line = next.get();
            if (line == null || line.isBlank()) {
                continue;
            }
            // A slash line is a COMMAND only when this driver was given a handler.
            // `run` supplies none (cli.md:27 - a bounded task has no interactive
            // commands), and must treat `/status` as the task TEXT. Returning null from
            // a handler used to mean "keep going", which made the driver `continue`
            // past the turn entirely: `rahu run --prompt /status` exited 0 having
            // answered nothing and printed nothing. A no-handler driver must not enter
            // this branch at all.
            if (slashCommandsEnabled && line.startsWith("/")) {
                Integer code = slashHandler.apply(line.strip());
                if (code != null) {
                    return code;
                }
                continue;
            }

            // Privacy first: before the input is recorded in history, printed, or
            // traced. cli.md requires a block to return a safe reason code and
            // corrective action and never the content. Exit 4 matches the live
            // driver so a caller cannot tell the modes apart by exit code alone.
            var admitted = gate.admitForDecision(
                SafeView.of("turn-" + session.turnCount(), provenance, line));
            if (admitted instanceof PrivacyGate.Decision.Blocked blocked) {
                if (!traceConfig.recordRefusal(session.turnCount(), blocked.category())) {
                    err.println("trace write failed while recording a refusal (A16); the "
                        + "input was still blocked");
                }
                err.println("privacy blocked (" + blocked.category()
                    + "); nothing was sent. Confirm the input is free of protected data, then"
                    + " re-run with --input-classification approved-nonsensitive");
                // cli.md:42: privacy blocked shares a code with no-feasible-route
                // and limit-reached. This matched the live driver's WRONG 4 rather
                // than the spec - AUDIT-2026-10-03-d. Agreeing with a sibling bug is
                // not conformance.
                return outcome(ExitCode.PRIVACY_BLOCKED, "PRIVACY_BLOCKED", null,
                    null, 0, 0);
            }

            SessionState.RunHandle turn;
            try {
                turn = session.beginTurn();
            } catch (IllegalStateException e) {
                // No run handle, so no run began and nothing is traced: a trace
                // here would claim a turn that never started.
                err.println("session limit: " + e.getMessage());
                return outcome(ExitCode.NO_ROUTE_OR_LIMIT_OR_PRIVACY,
                    "LIMIT_REACHED", null, null, 0, 0);
            }

            // The answer must not echo the input. An admitted input may still be
            // sensitive, and printing it back is a second disclosure that the
            // privacy gate never got a chance to judge.
            lastRunId = turn.runId();
            String answer = "offline: composed a bounded read-only answer."
                + " (No model was called; fake provider path.)";
            try (var trace = traceConfig.begin(turn.runId(), session.turnCount())) {
                turn.recordUser(ChatMessage.user(line));
                turn.recordAssistant(ChatMessage.assistant(answer));
                if (printAnswer) {
                    out.println(answer);
                }
                turn.complete();
                lastAnswer = answer;
                completedTurns++;
                trace.runTerminated("ANSWER_COMPLETE", 0);
            } catch (rahu.cli.trace.TraceFailureException e) {
                // A16: a broken sink stops the turn rather than leaving a partial
                // trace that reads as a complete one.
                err.println("trace write failed (A16); turn stopped");
                return outcome(ExitCode.TRACE_INTEGRITY_FAILURE, "TRACE_WRITE_FAILED",
                    null, lastRunId, 0, 0);
            }
        }
        if (announceEnd) {
            err.println("eof: chat ended; history is memory-only and does not survive exit");
        }
        // No turn ran at all (empty input), so there is no answer and no steps. The
        // elapsed time is real; the zero steps are what was observed.
        return outcome(ExitCode.OK, "ANSWER_COMPLETE", lastAnswer, lastRunId,
            completedTurns, elapsedMs(startedAtNanos));
    }

    /** Adapts a {@link Scanner} over stdin to the line supplier the loop consumes. */
    public static Supplier<java.util.Optional<String>> fromStdin() {
        var scanner = new Scanner(System.in);
        return () -> scanner.hasNextLine()
            ? java.util.Optional.of(scanner.nextLine()) : java.util.Optional.<String>empty();
    }
}
