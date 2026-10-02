package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.model.ChatMessage;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.ToolCall;
import rahu.core.model.Usage;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;
import rahu.core.model.ContinuationEnvelope;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;

/**
 * What the MODEL actually reads when a tool call is refused.
 *
 * <p>The defect these tests cover was invisible from the producing side: the boundary
 * wrote careful, safe, actionable denial text, and the loop's status switch read the
 * wrong field and threw it away. The denial arrived at the model as the bare string
 * {@code "denied: "}. So the assertion is made where the failure was observable — in
 * the text the model is handed — not on the return value of the thing that worked.
 */
class ToolDenialReasonTest {

    @TempDir
    Path root;

    /** Scripted provider: issues one named call, then a final answer; records the conversation. */
    private static final class ScriptedProvider implements ModelProvider {
        private final String toolName;
        private final String argumentsJson;
        List<ChatMessage> lastConversation = new ArrayList<>();

        ScriptedProvider(String toolName, String argumentsJson) {
            this.toolName = toolName;
            this.argumentsJson = argumentsJson;
        }

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            lastConversation = new ArrayList<>(request.messages());
            if (request.messages().stream().noneMatch(m -> m.role() == ChatMessage.Role.TOOL)) {
                return new ModelOutcome.Completed("", List.of(new ToolCall("call-1", toolName,
                    argumentsJson)), new Usage(10, 5, null, 0L), "tool_calls",
                    Optional.empty(), Optional.empty(), ContinuationEnvelope.empty("test"));
            }
            return new ModelOutcome.Completed("done", List.of(), new Usage(20, 5, null, 0L),
                "stop", Optional.empty(), Optional.empty(), ContinuationEnvelope.empty("test"));
        }
    }

    /** Answers every question as safe/unsupported; the gate is OFF, so only shape matters. */
    private static final class SafeEngine implements DecisionEngine {
        @Override
        public java.util.Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            java.util.Map<String, DecisionResult> out = new java.util.LinkedHashMap<>();
            for (Question q : questions) {
                String id = DecisionEngine.questionId(q);
                out.put(id, q instanceof DecisionEngine.NoulQuestion
                    ? new DecisionResult.ValidNoul(id, false, Optional.of(0.01))
                    : new DecisionResult.Failure(DecisionResult.FailureKind.UNSUPPORTED, "n/a"));
            }
            return out;
        }
    }

    private ToolLoop loop(ModelProvider provider) {
        var boundary = new PathBoundary(root);
        return new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(), new Provenance.ApprovedNonSensitive("test"),
            8, new InjectionGate(new SafeEngine(), InjectionGate.Mode.OFF, 0.5));
    }

    private String runAndReadFirstObservation(ScriptedProvider provider) {
        var l = loop(provider);
        l.generate(new ModelRef("test/model"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read something")), 256);
        return provider.lastConversation.stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElse("");
    }

    @Test
    @DisplayName("A refused traversal tells the model WHY, not just that it was refused")
    void refusalReasonReachesTheModel() {
        // A traversal path is refused by PathBoundary. Note the classification:
        // PathBoundary reports this as INVALID (the request is malformed/illegal),
        // not DENIED, so this asserts on "refused" as a class rather than on one
        // label. What matters is that a reason arrives at all.
        var provider = new ScriptedProvider("workspace.read",
            "{\"path\":\"../../etc/passwd\"}");
        String observation = runAndReadFirstObservation(provider);

        String label = observation.contains("denied: ") ? "denied: "
            : observation.contains("invalid: ") ? "invalid: "
            : observation.contains("failed: ") ? "failed: " : null;
        assertTrue(label != null,
            "the call must be refused with a typed label, got: [" + observation + "]");
        String reason = observation.substring(label.length()).trim();
        assertTrue(!reason.isEmpty() && !reason.equals("no reason provided"),
            "the refusal must carry a reason the model can act on, got: [" + observation + "]");
        assertTrue(observation.toLowerCase().contains("traversal")
                || observation.toLowerCase().contains("permitted")
                || observation.toLowerCase().contains("boundary"),
            "the reason should identify the boundary rule that refused it: " + observation);
    }

    @Test
    @DisplayName("An invalid call tells the model WHY, not just that it was invalid")
    void invalidReasonReachesTheModel() throws Exception {
        // An out-of-range line request. This used to return SUCCESS with empty
        // content, which the model could not distinguish from an empty file; now it
        // is a typed invalid result, and that reason must survive the trip to the model.
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
        var provider = new ScriptedProvider("workspace.read",
            "{\"path\":\"a.txt\",\"fromLine\":99,\"toLine\":120}");
        String observation = runAndReadFirstObservation(provider);

        assertTrue(observation.startsWith("invalid: "), "expected an invalid: " + observation);
        String reason = observation.substring("invalid: ".length()).trim();
        assertTrue(!reason.isEmpty() && !reason.equals("no reason provided"),
            "the invalid result must carry a reason: [" + observation + "]");
        assertTrue(observation.contains("99") && observation.contains("line"),
            "the reason should name the offending range: " + observation);
    }
}
