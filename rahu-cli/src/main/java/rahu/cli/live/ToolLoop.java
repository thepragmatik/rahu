package rahu.cli.live;

import java.util.ArrayList;
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
            for (ToolCall call : completed.proposedToolCalls()) {
                conversation.add(ChatMessage.tool(call.id(), observe(call)));
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
     * Executes one call; every failure becomes data the model can read, never an
     * exception. Protected observations yield generic safe-denial metadata, never
     * the offending value. An observation the privacy gate admitted is then judged
     * for injection risk: in ENFORCE a would-withhold observation is replaced with
     * denial metadata before it becomes model-visible, in SHADOW the judgment is
     * only recorded.
     */
    private String observe(ToolCall call) {
        var tool = registry.find(call.name());
        if (tool.isEmpty()) {
            return "denied: unknown tool " + call.name();
        }
        String canonical = CanonicalJson.canonicalize(call.argumentsJson());
        if (gate.admitForDecision(SafeView.of("tool-arg-" + call.id(), provenance, canonical))
            instanceof PrivacyGate.Decision.Blocked blocked) {
            return "denied: arguments not admitted (privacy: " + blocked.category() + ")";
        }
        String observation = observationOf(call, canonical);
        String observationId = "tool-obs-" + call.id();
        if (gate.admitForDecision(SafeView.of(observationId, provenance, observation))
            instanceof PrivacyGate.Decision.Blocked blocked) {
            return "denied: observation withheld (privacy: " + blocked.category() + ")";
        }
        var disposition = injection.assess(observationId, observation);
        injectionJudgments.add(new InjectionJudgment(observationId, disposition));
        if (disposition.withholds()) {
            return "denied: observation withheld (injection risk)";
        }
        return observation;
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
