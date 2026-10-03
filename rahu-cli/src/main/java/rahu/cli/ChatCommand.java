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
    mixinStandardHelpOptions = true,
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

    /**
     * The offline loop. The body lives in {@link rahu.cli.live.OfflineTurnDriver}
     * so it can be driven from a test; a loop inlined here reads {@code System.in}
     * and is untestable, which is how AUDIT-2026-10-03-b's missing privacy gate and
     * missing trace survived a green suite.
     */
    private Integer runOffline(RahuConfig cfg) {
        var session = newSession(cfg, "offline");
        return new rahu.cli.live.OfflineTurnDriver(cfg, session, provenance(cfg),
            line -> handleSlash(line, session),
            spec.commandLine().getOut(), spec.commandLine().getErr(),
            rahu.cli.live.OfflineTurnDriver.fromStdin(),
            System.console() != null).run();
    }

    /**
     * The registry for a live turn: the three read-only workspace tools, narrowed to
     * the operator's {@code tools.enabled} list.
     *
     * <p>Audit finding F-4. {@code tools.enabled} and {@code tools.exclusions} were
     * both parsed into {@link RahuConfig.ToolsConfig} and then never read, so an
     * operator who disabled a tool still got it advertised to the model. A config key
     * that is accepted and ignored is a documented capability that is unreachable -
     * the same shape as the unreachable {@code ValidScore}.
     *
     * <p>Package-visible so the wiring is directly testable; wiring built inside a
     * {@code runLive} body is not.
     */
    static rahu.core.tools.ToolRegistry workspaceRegistry(RahuConfig cfg,
        rahu.core.tools.PathBoundary boundary) {
        return rahu.cli.live.LiveAssembly.workspaceRegistry(cfg, boundary);
    }

    // ------------------------------------------------------------------- live

    private Integer runLive(RahuConfig cfg) {
        var err = spec.commandLine().getErr();
        var out = spec.commandLine().getOut();

        // AUDIT-2026-10-03-g: the assembly moved to LiveAssembly so `rahu run` and
        // `rahu chat` cannot drift apart. A tool left enabled in one command and
        // disabled in the other is a privacy difference, not a refactor.
        var built = rahu.cli.live.LiveAssembly.build(cfg, provenance(cfg), "live",
            new rahu.cli.live.LiveAssembly.PrintWriters(out, err));
        if (built instanceof rahu.cli.live.LiveAssembly.Result.Failure failure) {
            err.println(failure.refusal().operation() + " failed: "
                + failure.refusal().message());
            return 2;
        }
        var stack = ((rahu.cli.live.LiveAssembly.Result.Built) built).assembly();

        err.println("rahu chat (live) — routing " + stack.routerMode()
            + ", candidates " + stack.router().candidates().candidates().size()
            + ", decision " + cfg.decision().model()
            + " — /status /reset /exit");

        return stack.driver(new rahu.cli.live.LiveAssembly.PrintWriters(out, err),
            line -> handleSlash(line, stack.session())).run();
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

    /** Config mode to rerank mode; ConfigLoader has already refused anything else. */
    private static rahu.cli.live.SearchReranker.Mode rerankMode(RahuConfig.SearchConfig cfg) {
        return switch (cfg.modeOrDefault()) {
            case "shadow" -> rahu.cli.live.SearchReranker.Mode.SHADOW;
            case "enforce" -> rahu.cli.live.SearchReranker.Mode.ENFORCE;
            default -> rahu.cli.live.SearchReranker.Mode.OFF;
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
        return rahu.cli.live.LiveAssembly.provenance(cfg, inputClassification);
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
                // Signed, not remaining(): an overshot ledger has a NEGATIVE
                // headroom, and MoneyAmount is nonnegative by construction, so
                // remaining() clamps to 0 and loses the size of the overrun. The
                // operator asking here is trying to find out how badly it went.
                + ", remaining=" + session.ledger().overage()
                + (session.ledger().overshoot() ? " (OVERSPENT)" : "")
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
