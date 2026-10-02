package rahu.cli;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.context.PromptAssembler;
import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.model.ChatMessage;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.Usage;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.privacy.SafeView;
import rahu.core.routing.TerminalReason;
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

    private static final int DEFAULT_CONTEXT_ALLOWANCE = 8192;
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 2048;

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

    // ---------------------------------------------------------------- offline

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

        RahuConfig.PoolEntry baseline = baselineEntry(cfg);
        if (baseline == null) {
            err.println("routing.pool \"" + cfg.routing().pool()
                + "\" has no models; fix the config before a live run");
            return 2;
        }

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

        var session = newSession(cfg, "live");
        var gate = new PrivacyGate();
        var assembler = new PromptAssembler();
        int allowance = cfg.context().maxPromptTokens() == null
            ? DEFAULT_CONTEXT_ALLOWANCE : cfg.context().maxPromptTokens();
        int maxTokens = cfg.agent().maxCompletionTokens() == null
            ? DEFAULT_MAX_COMPLETION_TOKENS : cfg.agent().maxCompletionTokens();
        BigDecimal perRunCap = cfg.agent().maxCostUsd() == null
            ? BigDecimal.ZERO : cfg.agent().maxCostUsd();

        err.println("rahu chat (live) — model " + baseline.id() + ", decision "
            + cfg.decision().model() + ", shadow routing — /status /reset /exit");

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

            // 1. Initial admission: provenance + protected-content scan, before
            //    any classification, reservation or transport.
            var view = SafeView.of("turn-" + session.turnCount(), provenance(cfg), line);
            var admitted = gate.admitForDecision(view);
            if (admitted instanceof PrivacyGate.Decision.Blocked blocked) {
                err.println("privacy blocked (" + blocked.category()
                    + "); nothing was sent. Confirm the input is free of protected data, then"
                    + " re-run with --input-classification approved-nonsensitive");
                return 4;
            }

            SessionState.RunHandle turn;
            try {
                turn = session.beginTurn();
            } catch (IllegalStateException e) {
                err.println("session limit: " + e.getMessage());
                return 3;
            }

            // 2. Decision plane (shadow): recorded, never allowed to block the
            //    baseline, and its own dispatch is re-checked.
            var decisionDispatch = gate.admitDispatch(line, cfg.decision().baseUrl());
            if (decisionDispatch instanceof PrivacyGate.Decision.Blocked blocked) {
                err.println("privacy blocked at decision dispatch (" + blocked.category()
                    + "); nothing was sent");
                return 4;
            }
            String note = shadowDecision(decision, cfg, line);
            if (note != null) {
                err.println("decision (shadow, " + cfg.decision().model() + "): " + note);
            }

            // 3. Assemble the real prompt and re-check the exact outbound text.
            var plan = assembler.assemble(List.of(), session.history(),
                ChatMessage.user(line), allowance);
            var outbound = new StringBuilder();
            for (ChatMessage message : plan.messages()) {
                outbound.append(message.content()).append('\n');
            }
            var dispatch = gate.admitDispatch(outbound.toString(), cfg.generation().baseUrl());
            if (dispatch instanceof PrivacyGate.Decision.Blocked blocked) {
                err.println("privacy blocked at generation dispatch (" + blocked.category()
                    + "); nothing was sent");
                return 4;
            }

            turn.recordUser(ChatMessage.user(line));
            var request = new GenerationRequest(new ModelRef(baseline.id()),
                ReasoningPolicy.ProviderDefault.INSTANCE, plan.messages(), List.of(), maxTokens);
            long started = System.nanoTime();
            ModelOutcome outcome = provider.generate(request);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            if (outcome instanceof ModelOutcome.Failed failed) {
                turn.fail(TerminalReason.PROVIDER_FAILURE);
                err.println("generation failed: " + failed.kind() + " — " + failed.safeReason());
                return 4;
            }

            ModelOutcome.Completed done = (ModelOutcome.Completed) outcome;
            out.println(done.answer());
            turn.recordAssistant(ChatMessage.assistant(done.answer()));
            account(session, turn, done.usage(), perRunCap);
            err.println("model=" + baseline.id() + " · " + usageLine(done.usage())
                + " · " + elapsedMs + " ms · ledger=" + session.ledger().settled().amount()
                + " " + session.ledger().settled().currency());
            turn.complete();
        }
        err.println("eof: chat ended; history is memory-only and does not survive exit");
        return 0;
    }

    // ---------------------------------------------------------------- helpers

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

    /** Shadow-mode decision: recorded for evidence, never allowed to block the baseline. */
    private String shadowDecision(DecisionEngine decision, RahuConfig cfg, String prompt) {
        Map<String, String> criteria = new LinkedHashMap<>();
        List<RahuConfig.PoolEntry> pool = cfg.pools().get(cfg.routing().pool());
        if (pool != null) {
            for (RahuConfig.PoolEntry entry : pool) {
                String effort = entry.reasoning().isEmpty() ? "default" : entry.reasoning().get(0);
                criteria.put(entry.alias() + "@" + effort,
                    entry.description() == null ? entry.id() : entry.description());
            }
        }
        if (criteria.size() < 2) {
            return null;
        }
        try {
            var state = new DecisionEngine.State("route", prompt, 0.0);
            DecisionResult result = decision.ask(state,
                List.of(new DecisionEngine.ChoiceQuestion("route", criteria)));
            if (result instanceof DecisionResult.ValidChoice choice) {
                return choice.chosenLabel() + " (confidence "
                    + choice.rawConfidence().map(Object::toString).orElse("unknown") + ")";
            }
            if (result instanceof DecisionResult.Failure failure) {
                return "unavailable (" + failure.safeReason() + ")";
            }
            return "unavailable";
        } catch (RuntimeException e) {
            return "failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Settles the ledger with the billed cost, or marks it uncertain when unreported (A10). */
    private void account(SessionState session, SessionState.RunHandle turn, Usage usage,
        BigDecimal perRunCap) {
        if (perRunCap.signum() <= 0) {
            return;
        }
        var reservation = session.ledger().tryReserve(
            new MoneyAmount(perRunCap, CurrencyUnit.USD), turn.runId());
        if (reservation.isEmpty()) {
            return;
        }
        var micros = usage.totalCostMicrosOpt();
        if (micros.isPresent()) {
            session.ledger().settle(reservation.get(),
                new MoneyAmount(BigDecimal.valueOf(micros.get(), 6), CurrencyUnit.USD), true);
        } else {
            session.ledger().markUncertain(reservation.get());
        }
    }

    private static String usageLine(Usage usage) {
        return "tokens in=" + usage.promptTokensOpt().map(String::valueOf).orElse("unknown")
            + " out=" + usage.completionTokensOpt().map(String::valueOf).orElse("unknown")
            + " cost=" + usage.totalCostMicrosOpt()
                .map(micros -> "$" + BigDecimal.valueOf(micros, 6).toPlainString())
                .orElse("unavailable");
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
