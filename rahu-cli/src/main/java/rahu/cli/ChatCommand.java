package rahu.cli;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;
import rahu.core.context.SessionState;
import rahu.core.model.ChatMessage;

/**
 * In-process follow-up chat (cli.md): one turn per line, /status /reset /exit,
 * EOF clean, Ctrl-C exits 130; no background paid work; memory-only history.
 * Offline mode answers with a deterministic canned response (A22 path).
 */
@Command(name = "chat",
    description = "In-process follow-up conversation with aggregate limits.")
public final class ChatCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "Config JSON path")
    Path config;

    @Option(names = "--input-classification",
        description = "unknown | approved-nonsensitive (records assessment; never a bypass)")
    String inputClassification;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        RahuConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigError e) {
            spec.commandLine().getErr().println("config invalid: " + e.getMessage());
            return 2;
        }
        if (!"offline".equals(cfg.mode())) {
            spec.commandLine().getErr().println(
                "chat supports offline configs until live adapters are gated (S10/S12)");
            return 2;
        }

        var session = new SessionState("chat-" + System.nanoTime(),
            cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));
        var err = spec.commandLine().getErr();
        var out = spec.commandLine().getOut();

        err.println("rahu chat (offline) — /status /reset /exit, EOF to end");
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line == null || line.isBlank()) {
                continue;
            }
            if (line.startsWith("/")) {
                Integer code = handleSlash(line.strip(), session);
                if (code != null) {
                    return code;
                }
                continue;
            }
            SessionState.RunHandle turn;
            try {
                turn = session.beginTurn();
            } catch (IllegalStateException e) {
                err.println("session limit: " + e.getMessage());
                return 3;
            }
            turn.recordUser(ChatMessage.user(line));
            String answer = "offline: composed a bounded read-only answer for \""
                + line.strip() + "\". (No model was called; fake provider path.)";
            turn.recordAssistant(ChatMessage.assistant(answer));
            out.println(answer);
            turn.complete();
        }
        err.println("eof: chat ended; history is memory-only and does not survive exit");
        return 0;
    }

    /** Returns an exit code to stop, or null to continue. */
    private Integer handleSlash(String line, SessionState session) {
        var err = spec.commandLine().getErr();
        switch (line) {
            case "/status" -> err.println("turns=" + session.turnCount()
                + ", settled=" + session.ledger().settled().amount()
                + " " + session.ledger().settled().currency()
                + ", uncertain=" + session.ledger().uncertain().amount()
                + ", maxTurns not reached: " + !session.maxTurnsReached());
            case "/reset" -> {
                try {
                    session.resetConversation();
                    err.println("reset: conversation cleared; ledger and counts retained");
                } catch (IllegalStateException e) {
                    err.println("reset refused: " + e.getMessage());
                }
            }
            case "/exit" -> {
                return 0;
            }
            default -> {
                err.println("unsupported command " + line
                    + "; supported: /status /reset /exit");
                return 2;
            }
        }
        return null;
    }
}
