package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import rahu.core.model.ContinuationEnvelope;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.ToolCall;
import rahu.core.model.Usage;
import rahu.core.decision.DecisionResult;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.systemone.DecisionEngine;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;

/** The live loop must expose exactly the shipped read-only workspace tools. */
class ToolLoopTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("Registry bound to a workspace exposes list/read/search only")
    void exposesThreeReadOnlyTools() {
        ToolRegistry registry = ToolRegistry.withWorkspace(new PathBoundary(root));
        var names = registry.all().stream().map(t -> t.name()).sorted().toList();
        assertEquals(java.util.List.of("workspace.list", "workspace.read", "workspace.search"),
            names);
    }

    @Test
    @DisplayName("Descriptors map onto the generation-facing tool schema")
    void descriptorsMap() {
        ToolRegistry registry = ToolRegistry.withWorkspace(new PathBoundary(root));
        var descriptors = ToolLoop.descriptors(registry);
        assertEquals(3, descriptors.size());
        assertTrue(descriptors.get(0).jsonSchema().contains("properties"));
    }

    /** Answers every question as safe/unsupported; the gate is OFF, so only shape matters. */
    private static final class SafeEngine implements DecisionEngine {
        @Override
        public java.util.Map<String, DecisionResult> askAll(State state,
            List<Question> questions) {
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

    /** One tool-calling turn, then an answer. Records what the model would read. */
    private static final class ScriptedProvider implements ModelProvider {
        private final String toolName;
        private final String argumentsJson;
        List<ChatMessage> lastConversation;

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
            return new ModelOutcome.Completed("done", List.of(),
                new Usage(20, 5, null, 0L), "stop", Optional.empty(),
                Optional.empty(), ContinuationEnvelope.empty("test"));
        }
    }

    @Test
    @DisplayName("tools.resultBytes reaches the executor through the real tool loop")
    void configuredResultBytesReachesTheExecutor() throws Exception {
        // The cap was never MISSING - WorkspaceTools enforced a hardcoded 64 KiB.
        // The defect was that tools.resultBytes never REACHED it, and no test in the
        // repository mentioned resultBytes at all. So this drives an actual tool call
        // through the actual loop and reads the actual observation the model would
        // see, which is the only place the wiring can be proven end to end.
        Files.writeString(root.resolve("big.txt"), "z".repeat(9000));

        var boundary = new PathBoundary(root);
        var provider = new ScriptedProvider("workspace.read", "{\"path\":\"big.txt\"}");
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(), new Provenance.Synthetic("test-fixture"), 4,
            new InjectionGate(new SafeEngine(), InjectionGate.Mode.OFF, 0.5), null, 1500);
        loop.generate(new ModelRef("test/model"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read big.txt")), 256);

        String observation = provider.lastConversation.stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .reduce("", (a, b) -> a + b);
        int bytes = observation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        assertTrue(observation.contains("[truncated:"),
            "the observation the model reads must disclose truncation; it was: "
                + observation.substring(0, Math.min(200, observation.length())));
        assertTrue(observation.contains("1500"),
            "and it must name the CONFIGURED limit, not the built-in 64 KiB one: "
                + observation);
        assertTrue(bytes <= 1500 + 256,
            "the observation must respect tools.resultBytes=1500, got " + bytes + " bytes");
    }

    @Test
    @DisplayName("the default limit still applies when resultBytes is absent")
    void defaultLimitUnchanged() throws Exception {
        // The other direction: a cap that only ever shrinks would silently change
        // what every default deployment returns, so the no-arg path must still be
        // 64 KiB and must not truncate a 9000-byte file.
        Files.writeString(root.resolve("big.txt"), "z".repeat(9000));

        var boundary = new PathBoundary(root);
        var provider = new ScriptedProvider("workspace.read", "{\"path\":\"big.txt\"}");
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(), new Provenance.Synthetic("test-fixture"), 4,
            new InjectionGate(new SafeEngine(), InjectionGate.Mode.OFF, 0.5));
        loop.generate(new ModelRef("test/model"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read big.txt")), 256);

        String observation = provider.lastConversation.stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .reduce("", (a, b) -> a + b);
        assertTrue(!observation.contains("[truncated:"),
            "a 9000-byte file is inside the default 64 KiB limit and must come back whole");
    }
}
