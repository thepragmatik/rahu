package rahu.cli.live;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;

import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;
import rahu.core.context.PromptAssembler;
import rahu.core.context.SessionState;
import rahu.core.decision.DecisionResult;
import rahu.core.decision.TurnProfile;
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
 * Owns one live chat loop. Coordination only: the collaborator classes hold the
 * decision, tool and compaction behaviour, so each can be developed in its own
 * worktree without editing this file.
 */
public final class LiveTurnDriver {

    private static final int DEFAULT_CONTEXT_ALLOWANCE = 8192;
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 2048;

    /** The read-only workspace tools whose relevance the profile decision judges. */
    private static final List<String> PERMITTED_TOOLS =
        List.of("workspace.list", "workspace.read", "workspace.search");

    private final RahuConfig cfg;
    private final ModelProvider provider;
    private final DecisionEngine decision;
    private final SessionState session;
    private final Provenance provenance;
    private final ToolLoop toolLoop;
    private final Function<String, Integer> slashHandler;
    private final PrintWriter out;
    private final PrintWriter err;

    public LiveTurnDriver(RahuConfig cfg, ModelProvider provider, DecisionEngine decision,
        SessionState session, Provenance provenance, ToolLoop toolLoop,
        Function<String, Integer> slashHandler, PrintWriter out, PrintWriter err) {
        this.cfg = cfg;
        this.provider = provider;
        this.decision = decision;
        this.session = session;
        this.provenance = provenance;
        this.toolLoop = toolLoop;
        this.slashHandler = slashHandler;
        this.out = out;
        this.err = err;
    }

    /** Runs the loop to EOF or a slash-command exit; returns the process exit code. */
    public int run() {
        var gate = new PrivacyGate();
        var assembler = new PromptAssembler();
        int allowance = cfg.context().maxPromptTokens() == null
            ? DEFAULT_CONTEXT_ALLOWANCE : cfg.context().maxPromptTokens();
        int maxTokens = cfg.agent().maxCompletionTokens() == null
            ? DEFAULT_MAX_COMPLETION_TOKENS : cfg.agent().maxCompletionTokens();
        BigDecimal perRunCap = cfg.agent().maxCostUsd() == null
            ? BigDecimal.ZERO : cfg.agent().maxCostUsd();

        Scanner scanner = new Scanner(System.in);
        boolean interactive = System.console() != null;
        while (scanner.hasNextLine()) {
            prompt(interactive);
            String line = scanner.nextLine();
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

            // 1. Initial admission: provenance + protected-content scan, before
            //    any classification, reservation or transport.
            var view = SafeView.of("turn-" + session.turnCount(), provenance, line);
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

            // 2b. Batched profile decision (classification + per-tool relevance) in
            //     ONE askAll dispatch. Advisory only: code owns all control flow.
            //     It must never block the baseline; any failure degrades closed.
            TurnProfile profile = null;
            try {
                // Context pressure estimation arrives with the compaction track;
                // until then the honest value is 0.0 (fresh, small history).
                profile = new ProfileDecider(decision, PERMITTED_TOOLS).decide(line, 0.0);
            } catch (RuntimeException e) {
                err.println("profile: unavailable (" + e.getClass().getSimpleName() + ")");
            }
            if (profile != null) {
                err.println("profile: task=" + profile.taskClass().name().toLowerCase(Locale.ROOT)
                    + " tools=" + String.join(",", profile.relevantTools())
                    + " degraded=" + profile.degraded());
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
            long started = System.nanoTime();
            ModelOutcome outcome = toolLoop.generate(new ModelRef(baseline().id()),
                ReasoningPolicy.ProviderDefault.INSTANCE, plan.messages(), maxTokens);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            for (String executed : toolLoop.executedCalls()) {
                err.println("tool: " + executed);
            }

            if (outcome instanceof ModelOutcome.Failed failed) {
                turn.fail(TerminalReason.PROVIDER_FAILURE);
                err.println("generation failed: " + failed.kind() + " — " + failed.safeReason());
                return 4;
            }

            ModelOutcome.Completed done = (ModelOutcome.Completed) outcome;
            out.println(done.answer());
            turn.recordAssistant(ChatMessage.assistant(done.answer()));
            account(session, turn, done.usage(), perRunCap);
            err.println("model=" + baseline().id() + " · " + usageLine(done.usage())
                + " · " + elapsedMs + " ms · ledger=" + session.ledger().settled().amount()
                + " " + session.ledger().settled().currency());
            turn.complete();
        }
        err.println("eof: chat ended; history is memory-only and does not survive exit");
        return 0;
    }

    /** Baseline alias ("nemo@default") resolves to a pool entry; first entry is the fallback. */
    private RahuConfig.PoolEntry baseline() {
        List<RahuConfig.PoolEntry> pool = cfg.pools().get(cfg.routing().pool());
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

    /** Prompt only on a real terminal; piped input keeps stderr clean. */
    private void prompt(boolean interactive) {
        if (interactive) {
            err.print("> ");
            err.flush();
        }
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
}
