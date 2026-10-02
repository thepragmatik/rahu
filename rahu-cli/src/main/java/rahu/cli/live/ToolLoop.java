package rahu.cli.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
    private final Provenance provenance;
    private final InjectionGate injection;
    private final int maxCallsPerStep;
    private final List<String> executedCalls = new ArrayList<>();
    private final List<InjectionJudgment> injectionJudgments = new ArrayList<>();

    public ToolLoop(ToolRegistry registry, PathBoundary boundary, ModelProvider provider,
        ToolCallLog callLog, PrivacyGate gate, Provenance provenance, int maxCallsPerStep,
        InjectionGate injection) {
        this.registry = registry;
        this.boundary = boundary;
        this.provider = provider;
        this.callLog = callLog;
        this.gate = gate;
        this.workspace = new WorkspaceTools(boundary);
        this.provenance = provenance;
        this.injection = injection;
        this.maxCallsPerStep = maxCallsPerStep;
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
        List<ChatMessage> conversation = new ArrayList<>(messages);
        for (int round = 0; round <= maxCallsPerStep; round++) {
            var request = new GenerationRequest(model, policy, conversation,
                descriptors(registry), maxCompletionTokens);
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
        }
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
            out.add(new Observation(disposition.withholds()
                ? "denied: observation withheld (injection risk)"
                : texts.get(i)));
        }
        return List.copyOf(out);
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
            result = workspace.executeToolCall(call.id(), call.name(), canonical, callLog);
        } catch (ToolCallLog.ProtocolError e) {
            result = ToolResult.failed("protocol violation: " + e.getMessage());
        }
        executedCalls.add(call.name() + " " + summarize(canonical));
        return switch (result.status()) {
            case SUCCESS -> result.content();
            case DENIED -> "denied: " + result.content();
            case INVALID -> "invalid: " + result.content();
            case FAILED -> "failed: " + result.content();
        };
    }

    /** Short single-line argument echo for the stderr trail; safe/conservative. */
    private static String summarize(String canonicalArgs) {
        String flat = canonicalArgs.replace('\n', ' ').strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }
}
