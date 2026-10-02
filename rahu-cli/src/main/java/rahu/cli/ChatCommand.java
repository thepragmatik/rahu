package rahu.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.cli.live.LiveTurnDriver;
import rahu.core.CurrencyUnit;
import rahu.core.MoneyAmount;
import rahu.core.context.SessionState;
import rahu.core.model.ChatMessage;
import rahu.core.model.ModelProvider;
import rahu.core.privacy.Provenance;
import rahu.systemone.DecisionEngine;

/**
 * In-process follow-up chat (cli.md): one turn per line, /status /reset /exit,
 * EOF clean. Offline mode answers deterministically with the fake path; live
 * mode composes the real decision plane (shadow) and a real generation call,
 * gated by the outbound privacy check before anything leaves the process.
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
        return "offline".equals(cfg.mode()) ? runOffline(cfg) : runLive(cfg);
    }

    // --------------------------------------------------------------- offline

    private Integer runOffline(RahuConfig cfg) {
        var session = newSession(cfg, "offline");
        var err = spec.commandLine().getErr();
        var out = spec.commandLine().getOut();

        err.println("rahu chat (offline) — /status /reset /exit, EOF to end");
        Scanner scanner = new Scanner(System.in);
        boolean interactive = System.console() != null;
        while (scanner.hasNextLine()) {
            prompt(interactive);
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

    // ------------------------------------------------------------------- live

    private Integer runLive(RahuConfig cfg) {
        var err = spec.commandLine().getErr();
        var out = spec.commandLine().getOut();

        ModelProvider provider;
        DecisionEngine decision;
        try {
            provider = LiveWiring.generation(cfg);
            decision = LiveWiring.decision(cfg);
        } catch (RuntimeException e) {
            err.println("live wiring failed: " + e.getMessage());
            return 2;
        }
        if (!LiveWiring.keySupplier(cfg.generation().apiKeyEnv()).get().isPresent()) {
            err.println("generation credential " + cfg.generation().apiKeyEnv()
                + " is not set; export it or put it in .env (gitignored)");
            return 2;
        }

        // Paid admission is evidence-gated: the pool is reduced to candidates the
        // catalog can prove, and an inadmissible baseline or fallback refuses here
        // rather than degrading every turn later.
        rahu.cli.live.ActiveRouter router;
        try {
            router = new rahu.cli.live.ActiveRouter(cfg,
                rahu.cli.live.ProfileEvidence.load(cfg,
                    java.nio.file.Path.of("docs/generated/model-profiles.json")));
        } catch (IOException e) {
            err.println("profile evidence unavailable: " + e.getMessage()
                + " — run `rahu config validate --live-check` to fetch it");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("profile evidence lookup interrupted");
            return 2;
        } catch (IllegalStateException e) {
            err.println("routing refused: " + e.getMessage());
            return 2;
        }

        var session = newSession(cfg, "live");

        var boundary = new rahu.core.tools.PathBoundary(
            java.nio.file.Path.of(cfg.tools().root()));
        var registry = rahu.core.tools.ToolRegistry.withWorkspace(boundary);
        var loop = new rahu.cli.live.ToolLoop(registry, boundary, provider,
            new rahu.core.tools.ToolCallLog(), new rahu.core.privacy.PrivacyGate(),
            provenance(cfg),
            cfg.tools().maxCallsPerStep() == null ? 8 : cfg.tools().maxCallsPerStep(),
            new rahu.cli.live.InjectionGate(decision, injectionMode(cfg.injection()),
                cfg.injection().thresholdOrDefault()));

        err.println("rahu chat (live) — injection " + injectionMode(cfg.injection())
            + " — routing " + router.mode()
            + ", pool " + cfg.routing().pool() + ", candidates "
            + router.candidates().candidates().size() + ", decision "
            + cfg.decision().model() + " — /status /reset /exit");

        return new LiveTurnDriver(cfg, provider, decision, router, session,
            provenance(cfg), loop, line -> handleSlash(line, session), out, err).run();
    }

    // ---------------------------------------------------------------- helpers

    /** Config mode to gate mode; ConfigLoader has already refused anything else. */
    private static rahu.cli.live.InjectionGate.Mode injectionMode(
        RahuConfig.InjectionConfig cfg) {
        return switch (cfg.modeOrDefault()) {
            case "shadow" -> rahu.cli.live.InjectionGate.Mode.SHADOW;
            case "enforce" -> rahu.cli.live.InjectionGate.Mode.ENFORCE;
            default -> rahu.cli.live.InjectionGate.Mode.OFF;
        };
    }

    /** Prompt only on a real terminal; piped input keeps stderr clean. */
    private void prompt(boolean interactive) {
        if (interactive) {
            var err = spec.commandLine().getErr();
            err.print("> ");
            err.flush();
        }
    }

    private SessionState newSession(RahuConfig cfg, String label) {
        return new SessionState("chat-" + label + "-" + System.nanoTime(),
            cfg.session().maxTurns(),
            new MoneyAmount(cfg.session().maxCostUsd(), CurrencyUnit.USD));
    }

    /** Operator-recorded input provenance; anything but an explicit assessment fails closed. */
    private Provenance provenance(RahuConfig cfg) {
        String classification = inputClassification != null
            ? inputClassification : cfg.privacy().inputClassification();
        return "approved-nonsensitive".equals(classification)
            ? new Provenance.ApprovedNonSensitive("operator-classification")
            : Provenance.Unknown.INSTANCE;
    }

    /** Baseline alias ("nemo@default") resolves to a pool entry; first entry is the fallback. */
    private RahuConfig.PoolEntry baselineEntry(RahuConfig cfg) {
        List<RahuConfig.PoolEntry> pool = cfg.pools().get(cfg.routing().pool());
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        String alias = cfg.routing().baseline();
        if (alias != null && alias.contains("@")) {
            alias = alias.substring(0, alias.indexOf('@'));
        }
        for (RahuConfig.PoolEntry entry : pool) {
            if (entry.alias().equals(alias)) {
                return entry;
            }
        }
        return pool.get(0);
    }

    /** Returns an exit code to stop, or null to continue. */
    private Integer handleSlash(String line, SessionState session) {
        var err = spec.commandLine().getErr();
        switch (line) {
            case "/status" -> err.println("turns=" + session.turnCount()
                + ", settled=" + session.ledger().settled().amount()
                + " " + session.ledger().settled().currency()
                + ", uncertain=" + session.ledger().uncertain().amount()
                + ", remaining=" + session.ledger().remaining().amount()
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
