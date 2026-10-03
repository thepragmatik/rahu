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

    public OfflineTurnDriver(rahu.cli.config.RahuConfig cfg, SessionState session,
        Provenance provenance, Function<String, Integer> slashHandler, PrintWriter out,
        PrintWriter err, Supplier<java.util.Optional<String>> lines, boolean interactive) {
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
        var gate = new PrivacyGate();
        var traceConfig = TurnTrace.forSession(Path.of(cfg.trace().directory()), cfg, session);

        err.println("rahu chat (offline) — /status /reset /exit, EOF to end");
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
            if (line.startsWith("/")) {
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
                return 4;
            }

            SessionState.RunHandle turn;
            try {
                turn = session.beginTurn();
            } catch (IllegalStateException e) {
                // No run handle, so no run began and nothing is traced: a trace
                // here would claim a turn that never started.
                err.println("session limit: " + e.getMessage());
                return 3;
            }

            // The answer must not echo the input. An admitted input may still be
            // sensitive, and printing it back is a second disclosure that the
            // privacy gate never got a chance to judge.
            String answer = "offline: composed a bounded read-only answer."
                + " (No model was called; fake provider path.)";
            try (var trace = traceConfig.begin(turn.runId(), session.turnCount())) {
                turn.recordUser(ChatMessage.user(line));
                turn.recordAssistant(ChatMessage.assistant(answer));
                out.println(answer);
                turn.complete();
                trace.runTerminated("ANSWER_COMPLETE", 0);
            } catch (rahu.cli.trace.TraceFailureException e) {
                // A16: a broken sink stops the turn rather than leaving a partial
                // trace that reads as a complete one.
                err.println("trace write failed (A16); turn stopped");
                return 5;
            }
        }
        err.println("eof: chat ended; history is memory-only and does not survive exit");
        return 0;
    }

    /** Adapts a {@link Scanner} over stdin to the line supplier the loop consumes. */
    public static Supplier<java.util.Optional<String>> fromStdin() {
        var scanner = new Scanner(System.in);
        return () -> scanner.hasNextLine()
            ? java.util.Optional.of(scanner.nextLine()) : java.util.Optional.<String>empty();
    }
}
