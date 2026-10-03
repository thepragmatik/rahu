package rahu.cli.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.model.ChatMessage;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.ToolCall;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.privacy.SafeView;
import rahu.core.tools.CanonicalJson;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.Tool;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;
import rahu.core.tools.ToolResult;
import rahu.core.tools.WorkspaceTools;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;

/**
 * Bounded read-only tool loop: generate, execute any proposed calls, feed the
 * observations back, repeat until the model answers or the step cap is hit.
 * Every call goes through PathBoundary inside the tool executor, so the model
 * cannot name its way outside the workspace root. Repeated call IDs follow the
 * A09 dedup contract via WorkspaceTools/ToolCallLog; arguments and observations
 * are privacy-scanned before they re-enter model-visible context (privacy.md),
 * with the session's operator-established provenance — unknown fails closed. An
 * admitted observation is then judged for prompt-injection risk before it becomes
 * model-visible; that overlay never widens what the privacy gate allows.
 */
public final class ToolLoop {

    private final ToolRegistry registry;
    private final PathBoundary boundary;
    private final ModelProvider provider;
    private final ToolCallLog callLog;
    private final PrivacyGate gate;
    private final WorkspaceTools workspace;
    private final int resultBytes;
    private final Provenance provenance;
    private final InjectionGate injection;
    private final SearchReranker reranker;
    private final int maxCallsPerStep;
    private final rahu.core.runtime.NoProgressDetector noProgress =
        new rahu.core.runtime.NoProgressDetector();
    private final List<String> executedCalls = new ArrayList<>();
    private final List<InjectionJudgment> injectionJudgments = new ArrayList<>();
    private SearchReranker.Result lastRerank;
    /** The user's request for this turn; relevance is judged against it. */
    private String queryText = "";
    /**
     * Per-turn advisory narrowing from the tool-relevance judgment. Null or empty means
     * the full registry, because an empty judgment is indistinguishable from an
     * unjudgeable one and must not remove every tool.
     */
    private Set<String> permittedThisTurn;

    public ToolLoop(ToolRegistry registry, PathBoundary boundary, ModelProvider provider,
        ToolCallLog callLog, PrivacyGate gate, Provenance provenance, int maxCallsPerStep,
        InjectionGate injection) {
        this(registry, boundary, provider, callLog, gate, provenance, maxCallsPerStep,
            injection, new SearchReranker(UNREACHABLE_DECISION, SearchReranker.Mode.OFF,
                DEFAULT_RERANK_CANDIDATES));
    }

    public ToolLoop(ToolRegistry registry, PathBoundary boundary, ModelProvider provider,
        ToolCallLog callLog, PrivacyGate gate, Provenance provenance, int maxCallsPerStep,
        InjectionGate injection, SearchReranker reranker) {
        this(registry, boundary, provider, callLog, gate, provenance, maxCallsPerStep,
            injection, reranker, WorkspaceTools.DEFAULT_RESULT_BYTES);
    }

    /**
     * @param resultBytes the {@code tools.resultBytes} cap. It reaches the workspace
     *     executor here because that is where results are actually produced; the
     *     descriptors carry the policy but the executor is what truncates.
     */
    public ToolLoop(ToolRegistry registry, PathBoundary boundary, ModelProvider provider,
        ToolCallLog callLog, PrivacyGate gate, Provenance provenance, int maxCallsPerStep,
        InjectionGate injection, SearchReranker reranker, int resultBytes) {
        this.registry = registry;
        this.boundary = boundary;
        this.provider = provider;
        this.callLog = callLog;
        this.gate = gate;
        this.workspace = new WorkspaceTools(boundary, resultBytes);
        this.resultBytes = resultBytes;
        this.provenance = provenance;
        this.injection = injection;
        this.reranker = reranker == null
            ? new SearchReranker(UNREACHABLE_DECISION, SearchReranker.Mode.OFF,
                DEFAULT_RERANK_CANDIDATES)
            : reranker;
        this.maxCallsPerStep = maxCallsPerStep;
    }

    /**
     * Candidates scored per search when no explicit cap is configured. The cap keeps the
     * batch inside the 16 KiB state bound; it limits how much is SCORED, never how much
     * is returned.
     */
    public static final int DEFAULT_RERANK_CANDIDATES = 20;

    /**
     * A decision engine that refuses every question, for the wiring that does not
     * rerank. Rerank in OFF never asks, so this is unreachable in practice — it exists
     * so a caller that does not want a decision engine still gets a well-formed loop
     * rather than a null field, and so nothing can silently reach a default engine.
     */
    private static final DecisionEngine UNREACHABLE_DECISION = new DecisionEngine() {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            throw new IllegalStateException("no decision engine wired for rerank");
        }
    };

    /** The rerank applied to the current turn's last search, for the observability trail. */
    public SearchReranker.Result lastRerank() {
        return lastRerank;
    }

    /**
     * One injection judgment for the current turn: what was judged and what the
     * configured mode did with it. Carries no observation text, so the trail is
     * safe to print verbatim.
     */
    public record InjectionJudgment(String observationId, InjectionGate.Disposition disposition) {
    }

    /** Injection judgments for the current turn, in observation order. */
    public List<InjectionJudgment> injectionJudgments() {
        return List.copyOf(injectionJudgments);
    }

    /**
     * Narrows this turn's advertised tools to an advisory relevance judgment
     * (tools.md line 7). Narrowing only: an empty or null set keeps the full registry,
     * because {@code TurnProfile.from} maps an empty judgment back to the permitted
     * set for exactly that reason. A judged-irrelevant tool therefore stops being
     * advertised, and a model that calls it anyway gets a typed unknown-tool denial.
     */
    public void narrowTo(Set<String> relevantTools) {
        this.permittedThisTurn = relevantTools == null || relevantTools.isEmpty()
            ? null : Set.copyOf(relevantTools);
    }

    /** The tool schemas the generation request advertises. */
    public List<rahu.core.model.ToolDescriptor> descriptorsForThisTurn() {
        return descriptors(permittedThisTurn == null ? registry
            : registry.restrictedTo(permittedThisTurn));
    }


    /**
     * The effective {@code tools.resultBytes} cap this loop was built with.
     *
     * <p>Exposed so the wiring is OBSERVABLE. Asserting only {@code
     * LiveAssembly.resultBytes(cfg)} proved the helper correct while leaving the call
     * site unverified: a mutation replacing {@code resultBytes(cfg)} with the default
     * passed every test, because nothing could see what assembly handed the loop.
     * Reading the cap off a built loop closes that gap without needing credentials.
     */
    public int resultBytes() {
        return resultBytes;
    }

    /** The tool schemas the generation request advertises. */
    public static List<rahu.core.model.ToolDescriptor> descriptors(ToolRegistry registry) {
        List<rahu.core.model.ToolDescriptor> mapped = new ArrayList<>();
        for (Tool tool : registry.all()) {
            var descriptor = tool.descriptor();
            mapped.add(new rahu.core.model.ToolDescriptor(
                descriptor.name(), descriptor.description(), descriptor.jsonSchema()));
        }
        return mapped;
    }

    /**
     * Reasons about the messages; returns the first answer with no pending tool
     * calls. The executed-call trail resets at the start of each turn so callers
     * only see this turn's activity.
     */
    public ModelOutcome generate(ModelRef model, ReasoningPolicy policy,
        List<ChatMessage> messages, int maxCompletionTokens) {

        executedCalls.clear();
        injectionJudgments.clear();
        lastRerank = null;
        // The NO_PROGRESS streak is per-TURN, not per-session, so it resets here with
        // the rest of the per-turn state. It was not, and that was a real defect
        // (AUDIT-2026-10-03-z): `rahu chat` reuses ONE ToolLoop for the whole session,
        // so a two-batch streak left by turn 1 was still standing when turn 2 began,
        // and the first repeat in turn 2 hit three and killed a turn that was doing
        // fresh work. A27 requires "a new user turn resets the streak".
        //
        // The detector is stateful by design - it must survive CONTEXT COMPACTION
        // within a turn, which is why it lives outside the conversation - but the turn
        // boundary is a real boundary. Those are different lifetimes and conflating them
        // breaks the acceptance clause; a later run can no longer be no-progress merely
        // because an earlier, unrelated one repeated itself.
        noProgress.newTurn();
        // permittedThisTurn is deliberately NOT reset here: the driver narrows before
        // calling generate, so clearing it here would discard the judgment the turn
        // just made. A turn with no judgment leaves it null, which means the full set.
        queryText = lastUserText(messages);
        List<ChatMessage> conversation = new ArrayList<>(messages);
        for (int round = 0; round <= maxCallsPerStep; round++) {
            var request = new GenerationRequest(model, policy, conversation,
                descriptorsForThisTurn(), maxCompletionTokens);
            ModelOutcome outcome = provider.generate(request);
            if (outcome instanceof ModelOutcome.Failed) {
                return outcome;
            }
            ModelOutcome.Completed completed = (ModelOutcome.Completed) outcome;
            if (completed.proposedToolCalls().isEmpty()) {
                return completed;
            }

            conversation.add(ChatMessage.assistantWithToolCalls(completed.proposedToolCalls()));
            // Execute every call first, then judge the resulting observations in ONE
            // dispatch. Judging per observation meant a decision-plane call (and its
            // latency and spend) for every tool result in the turn; the questions are
            // independent, so they share a request the way ProfileDecider already does.
            List<Observation> judged = observeAll(completed.proposedToolCalls());
            for (int i = 0; i < completed.proposedToolCalls().size(); i++) {
                conversation.add(ChatMessage.tool(completed.proposedToolCalls().get(i).id(),
                    judged.get(i).text()));
            }

            // NO_PROGRESS guard. Found by a live dogfood turn that re-issued one
            // failing read 14 times: the batch fingerprint never matched itself
            // because the rest of the batch varied, so the loop only stopped at
            // maxCallsPerStep, having spent real tokens on a call that could never
            // succeed. Each call's digest is its OWN observation text, not the
            // batch digest, so a single stuck call is caught even when batched
            // with changing work.
            var perCall = new java.util.LinkedHashMap<String, String>();
            var batchText = new StringBuilder();
            for (int i = 0; i < judged.size(); i++) {
                String text = judged.get(i).text();
                perCall.put(completed.proposedToolCalls().get(i).id(), text);
                batchText.append(text).append('\u0000');
            }
            if (noProgress.recordBatch(completed.proposedToolCalls(), perCall,
                    batchText.toString())) {
                return new ModelOutcome.Failed(ModelOutcome.Failed.FailureKind.NO_PROGRESS,
                    "no progress: a tool call repeated with an unchanged outcome", null);
            }
        }
        noProgress.newTurn();
        return new ModelOutcome.Failed(ModelOutcome.Failed.FailureKind.INVALID_REQUEST,
            "tool step limit reached without a final answer", null);
    }

    /**
     * Tools executed for the current turn, in order ("name argSummary"); stderr
     * observability, safe values only.
     */
    public List<String> executedCalls() {
        return List.copyOf(executedCalls);
    }

    /**
     * Executes every call of a turn, then judges the observations that survived the
     * privacy gate in ONE batched dispatch. Two phases on purpose: the decision plane
     * must see the whole turn's observations at once to batch them, and an observation
     * the privacy gate refused is never judged (the overlay must not become a
     * disclosure bypass, and a refused observation has no text to judge).
     *
     * <p>A call that needs no judgment — unknown tool, refused arguments, refused
     * observation — passes through with its denial metadata and is left out of the
     * batch. The returned list is one entry per input call, in order.
     */
    private List<Observation> observeAll(List<ToolCall> calls) {
        // Phase 1: execute and run the privacy gate, collecting judgeable text.
        var pending = new ArrayList<InjectionGate.Observation>();
        var texts = new ArrayList<String>();
        var judgeable = new boolean[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            String observationId = "tool-obs-" + call.id();
            var tool = registry.find(call.name());
            if (tool.isEmpty()) {
                texts.add("denied: unknown tool " + call.name());
                continue;
            }
            String canonical = CanonicalJson.canonicalize(call.argumentsJson());
            if (gate.admitForDecision(SafeView.of("tool-arg-" + call.id(), provenance, canonical))
                instanceof PrivacyGate.Decision.Blocked blocked) {
                texts.add("denied: arguments not admitted (privacy: " + blocked.category() + ")");
                continue;
            }
            String observation = observationOf(call, canonical);
            if (gate.admitForDecision(SafeView.of(observationId, provenance, observation))
                instanceof PrivacyGate.Decision.Blocked blocked) {
                texts.add("denied: observation withheld (privacy: " + blocked.category() + ")");
                continue;
            }
            judgeable[i] = true;
            pending.add(new InjectionGate.Observation(observationId, observation));
            texts.add(observation);
        }

        // Phase 2: one dispatch for the whole turn. In ENFORCE a would-withhold
        // observation is replaced with denial metadata before it becomes model-visible;
        // in SHADOW the judgment is only recorded.
        List<InjectionGate.Disposition> dispositions =
            injection.assessAll(pending);
        var byId = new LinkedHashMap<String, InjectionGate.Disposition>();
        for (int i = 0; i < pending.size(); i++) {
            byId.put(pending.get(i).id(), dispositions.get(i));
        }

        // Phase 3: apply each observation's own consequence, in call order.
        var out = new ArrayList<Observation>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            if (!judgeable[i]) {
                out.add(new Observation(texts.get(i)));
                continue;
            }
            String observationId = "tool-obs-" + calls.get(i).id();
            var disposition = byId.get(observationId);
            injectionJudgments.add(new InjectionJudgment(observationId, disposition));
            String text = disposition.withholds()
                ? "denied: observation withheld (injection risk)"
                : texts.get(i);
            // Rerank runs LAST, on the text the gates already decided, and only
            // reorders it. It is handed `text`, never `texts.get(i)`: in ENFORCE the two
            // differ, and reranking the raw observation would re-deliver exactly the
            // content the injection gate withheld. Withheld text is a single denial
            // line, which has no hits to rank, so rerank leaves it untouched.
            out.add(new Observation(
                "workspace.search".equals(calls.get(i).name())
                    ? rerank(text)
                    : text));
        }
        return List.copyOf(out);
    }

    /**
     * Reorders a search observation's hit lines by relevance, leaving the trailing
     * truncation notice last and any non-hit line (a denial, an error) untouched. The
     * rendered body mixes hit lines with a possible "[truncated: ...]" marker, so only
     * the hit prefix is ranked.
     */
    private String rerank(String observation) {
        var lines = new ArrayList<>(List.of(observation.split("\n", -1)));
        int hitCount = lines.size();
        while (hitCount > 0 && lines.get(hitCount - 1).startsWith("[truncated:")) {
            hitCount--;
        }
        if (hitCount < 2) {
            lastRerank = null;
            return observation;
        }
        var hits = List.copyOf(lines.subList(0, hitCount));
        var tail = List.copyOf(lines.subList(hitCount, lines.size()));
        SearchReranker.Result result = reranker.rerank(queryText, hits);
        lastRerank = result;
        var out = new ArrayList<String>(result.ordered());
        out.addAll(tail);
        return String.join("\n", out);
    }

    /**
     * The most recent user message, which is what relevance is judged against: a hit is
     * relevant to what was ASKED, not to the tool call's arguments. Falls back to empty
     * rather than guessing from another role.
     */
    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role() == ChatMessage.Role.USER) {
                return m.content() == null ? "" : m.content();
            }
        }
        return "";
    }

    /** One call's model-visible observation text. */
    private record Observation(String text) {
    }

    /**
     * Executes one validated call through WorkspaceTools with the A09 call-ID
     * contract: identical-ID-identical-arguments replays the recorded outcome;
     * identical-ID-different-arguments is a protocol error surfaced as a typed
     * failure observation, never an exception escaping the loop.
     */
    private String observationOf(ToolCall call, String canonical) {
        ToolResult result;
        try {
            result = workspace.executeToolCall(call.id(), call.name(), canonical, callLog,
                registry);
        } catch (ToolCallLog.ProtocolError e) {
            result = ToolResult.failed("protocol violation: " + e.getMessage());
        }
        executedCalls.add(call.name() + " " + summarize(canonical));
        // Non-success results carry their text in `safeReason`, never in `content`
        // (which is "" by construction for denied/invalid/failed). Reading
        // `content` here produced the bare strings "denied: ", "invalid: " and
        // "failed: " -- the model was told a call was refused and given no reason,
        // so it could not correct course. PathBoundary writes careful, safe,
        // actionable denial text and this switch was throwing it away one layer up.
        String reason = result.safeReason() == null || result.safeReason().isBlank()
            ? "no reason provided" : result.safeReason();
        return switch (result.status()) {
            case SUCCESS -> result.content();
            case DENIED -> "denied: " + reason;
            case INVALID -> "invalid: " + reason;
            case FAILED -> "failed: " + reason;
        };
    }

    /** Short single-line argument echo for the stderr trail; safe/conservative. */
    private static String summarize(String canonicalArgs) {
        String flat = canonicalArgs.replace('\n', ' ').strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }
}
