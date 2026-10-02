package rahu.cli.live;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Function;

import rahu.cli.config.RahuConfig;
import rahu.core.CurrencyUnit;
import rahu.core.ExecutionCandidate;
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
    private final ActiveRouter router;
    private final SessionState session;
    private final Provenance provenance;
    private final ToolLoop toolLoop;
    private final Function<String, Integer> slashHandler;
    private final PrintWriter out;
    private final PrintWriter err;

    public LiveTurnDriver(RahuConfig cfg, ModelProvider provider, DecisionEngine decision,
        ActiveRouter router, SessionState session, Provenance provenance, ToolLoop toolLoop,
        Function<String, Integer> slashHandler, PrintWriter out, PrintWriter err) {
        this.cfg = cfg;
        this.provider = provider;
        this.decision = decision;
        this.router = router;
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

            // 2a. Deterministic prompt assembly and context pressure (the
            //     documented conservative estimate over history + this request).
            var plan = assembler.assemble(List.of(), session.history(),
                ChatMessage.user(line), allowance);
            double pressure = Math.min(1.0,
                plan.estimatedTokens() / (double) plan.contextAllowanceTokens());

            // 2. Routing decision. The labels offered are the executable candidate
            //    ids, so a decision the resolver can accept actually changes the
            //    executed model in active mode. In shadow mode the same call
            //    records the suggestion while the baseline still executes.
            var routing = routeDecision(decision, line);
            err.println(ActiveRouter.describe(routing.resolution()));
            if (routing.note() != null) {
                err.println("decision (" + router.mode() + ", " + cfg.decision().model()
                    + "): " + routing.note());
            }
            if (routing.resolution().terminalReason().isPresent()) {
                err.println("routing terminal: " + routing.resolution().terminalReason().get()
                    + " — nothing was sent");
                return 3;
            }

            // 2b. Batched profile decision (classification + per-tool relevance) in
            //     ONE askAll dispatch. Advisory only: code owns all control flow.
            //     It must never block the baseline; any failure degrades closed.
            TurnProfile profile = null;
            try {
                profile = new ProfileDecider(decision, PERMITTED_TOOLS).decide(line, pressure);
            } catch (RuntimeException e) {
                err.println("profile: unavailable (" + e.getClass().getSimpleName() + ")");
            }
            if (profile != null) {
                err.println("profile: task=" + profile.taskClass().name().toLowerCase(Locale.ROOT)
                    + " tools=" + String.join(",", profile.relevantTools())
                    + " degraded=" + profile.degraded());
            }

            // 2c. COMPACTION_POLICY at the 80% trigger (docs/specs/context.md).
            //     Code owns control flow: the decision only picks the policy, the
            //     deterministic fit check overrides, and context is never dropped
            //     without an executed summary (summary execution is the
            //     summarisation track; this records the decision and the plan).
            //     The consult sees the candidate NEXT-REQUEST list (history +
            //     pending line) so the fit check sees what the next request
            //     actually needs, not just what history already holds.
            if (pressure >= 0.80) {
                var candidate = new java.util.ArrayList<>(session.history());
                candidate.add(ChatMessage.user(line));
                var consult = new CompactionPolicyDecider(decision)
                    .consult(candidate, allowance);
                err.println("compaction: policy=" + consult.policy().name().toLowerCase(Locale.ROOT)
                    + " pressure=" + String.format(Locale.ROOT, "%.2f", pressure)
                    + " — " + consult.safeNote());
            }

            // 3. Re-check the exact outbound text (assembled above).
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
            var candidate = executed(routing.resolution());

            // The cost gate is a PRE-dispatch gate. Reserving after the provider call
            // would let the turn run (and be billed) with an exhausted allowance, and a
            // refused reservation would leave the spend unrecorded instead of stopping the
            // turn. Reserve the per-run cap first; refuse the turn (exit 3) if refused.
            var reservation = session.ledger().tryReserve(
                new MoneyAmount(perRunCap, CurrencyUnit.USD), turn.runId());
            if (reservation.isEmpty()) {
                turn.fail(TerminalReason.PROVIDER_FAILURE);
                err.println("cost: session cost allowance exhausted — nothing was sent"
                    + " (committed=" + session.ledger().settled().amount() + " "
                    + perRunCap + " per-run cap, "
                    + cfg.session().maxCostUsd() + " session allowance)");
                return 3;
            }

            // The advisory relevance judgment is only advisory if it narrows what the
            // model is offered. Before this call the profile was computed, printed and
            // discarded, so a judged-irrelevant tool was still advertised.
            if (profile != null) {
                toolLoop.narrowTo(profile.relevantTools());
            }

            long started = System.nanoTime();
            ModelOutcome outcome = toolLoop.generate(candidate.model(), candidate.reasoningPolicy(),
                plan.messages(), maxTokens);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            for (String executed : toolLoop.executedCalls()) {
                err.println("tool: " + executed);
            }
            for (var judged : toolLoop.injectionJudgments()) {
                err.println(injectionLine(judged));
            }

            if (outcome instanceof ModelOutcome.Failed failed) {
                // The reservation was taken BEFORE dispatch, so a failed generation
                // still holds money. It must be accounted for on this path: a read
                // timeout means the provider may well have processed and billed the
                // request, which is the charter's definition of UNCERTAIN. Leaving
                // the reservation open recorded it as neither, which both leaked the
                // reservation forever and hid a probable charge from the allowance.
                account(session, failed.usage(), reservation.get());
                turn.fail(TerminalReason.PROVIDER_FAILURE);
                err.println("generation failed: " + failed.kind() + " — " + failed.safeReason()
                    + " (ledger settled=" + session.ledger().settled().amount()
                    + " uncertain=" + session.ledger().uncertain().amount() + ")");
                return 4;
            }

            ModelOutcome.Completed done = (ModelOutcome.Completed) outcome;
            out.println(done.answer());
            turn.recordAssistant(ChatMessage.assistant(done.answer()));
            account(session, done.usage(), reservation.get());
            err.println("model=" + candidate.model().providerNeutralId()
                + " (" + candidate.id() + ") · " + usageLine(done.usage())
                + " · " + elapsedMs + " ms · ledger=" + session.ledger().settled().amount()
                + " " + session.ledger().settled().currency());
            turn.complete();
        }
        err.println("eof: chat ended; history is memory-only and does not survive exit");
        return 0;
    }

    /** Prompt only on a real terminal; piped input keeps stderr clean. */
    private void prompt(boolean interactive) {
        if (interactive) {
            err.print("> ");
            err.flush();
        }
    }

    /** One turn's routing outcome: the resolution plus the raw decision evidence. */
    /**
     * One shadow/enforce judgment for the stderr trail: the observation id, the
     * verdict, the score, and whether anything was withheld. Never the text.
     */
    private static String injectionLine(ToolLoop.InjectionJudgment judged) {
        var d = judged.disposition();
        return "injection: " + judged.observationId() + " verdict=" + d.verdict()
            + " score=" + d.probability().map(String::valueOf).orElse("absent")
            + (d.wouldHaveWithheld() ? (d.withholds() ? " withheld" : " would-withhold") : "");
    }

    private record Routing(rahu.core.routing.RouteResolution resolution, String note) {
    }

    /**
     * Asks the decision plane which executable candidate to run, then resolves it.
     *
     * <p>The criteria come from the candidate set, never from the raw pool: a decision
     * whose labels the resolver does not recognise degrades to the baseline by
     * construction, which would make active routing a silent no-op.
     */
    private Routing routeDecision(DecisionEngine engine, String prompt) {
        Map<String, String> criteria = router.criteria();
        if (criteria.size() < 2) {
            return new Routing(router.resolve(Optional.empty()),
                criteria.isEmpty() ? null : "single candidate; no routing decision asked");
        }
        try {
            var state = new DecisionEngine.State("route", prompt, 0.0);
            DecisionResult result = engine.ask(state,
                List.of(new DecisionEngine.ChoiceQuestion("route", criteria)));
            if (result instanceof DecisionResult.ValidChoice choice) {
                return new Routing(router.resolve(Optional.of(result)),
                    choice.chosenLabel() + " (confidence "
                        + choice.rawConfidence().map(Object::toString).orElse("unknown") + ")");
            }
            if (result instanceof DecisionResult.Failure failure) {
                return new Routing(router.resolve(Optional.empty()),
                    "unavailable (" + failure.safeReason() + ")");
            }
            return new Routing(router.resolve(Optional.empty()), "unavailable");
        } catch (RuntimeException e) {
            return new Routing(router.resolve(Optional.empty()),
                "failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The candidate the resolver selected for execution. */
    private ExecutionCandidate executed(rahu.core.routing.RouteResolution resolution) {
        String id = resolution.executedId().orElseThrow(() -> new IllegalStateException(
            "resolution has no executed candidate"));
        return router.candidates().candidates().stream()
            .filter(candidate -> candidate.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "resolved candidate is not in the executable set: " + id));
    }

    /**
     * Settles the pre-dispatch reservation with the billed cost, or marks it uncertain
     * when the cost was unreported (A10).
     *
     * <p>The reservation was taken BEFORE dispatch so an exhausted allowance could refuse
     * the turn; this method only settles what was already reserved.
     */
    private void account(SessionState session, Usage usage,
        rahu.core.runtime.Ledger.Reservation reservation) {
        var micros = usage.totalCostMicrosOpt();
        if (micros.isPresent()) {
            session.ledger().settle(reservation,
                new MoneyAmount(BigDecimal.valueOf(micros.get(), 6), CurrencyUnit.USD), true);
        } else {
            // No reported cost on a dispatched request is not evidence of no cost.
            session.ledger().markUncertain(reservation);
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
