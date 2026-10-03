package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.authority.EffectClass;
import rahu.core.model.ChatMessage;
import rahu.core.model.ContinuationEnvelope;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ModelProvider;
import rahu.core.model.ToolCall;
import rahu.core.model.Usage;
import rahu.core.privacy.PrivacyGate;
import rahu.core.privacy.Provenance;
import rahu.core.tools.PathBoundary;
import rahu.core.tools.Tool;
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolDescriptor;
import rahu.core.tools.ToolRegistry;
import rahu.core.tools.ToolResult;
import rahu.systemone.DecisionEngine;

/**
 * AUDIT-2026-10-03-x, A26. The wiring test the core tests could not provide.
 *
 * <p>Mutation testing on the fix is what demanded it. Deleting the effect-class gate,
 * or the registry fallback in {@code WorkspaceTools.invoke}, both turned the suite
 * red — but making {@code ToolLoop.observationOf} stop passing its registry
 * <em>survived, all 524 tests</em>.
 *
 * <p>That survival is exactly the AUDIT-2026-10-03-w defect reintroduced one layer up:
 * a registered tool is still advertised to the model from the registry, and the loop
 * still runs privacy and injection gates over it, but dispatch no longer consults the
 * registry, so the model is told a tool exists and then gets "unknown tool". Every
 * core-level test still passed because they call {@code WorkspaceTools} directly with
 * a registry supplied by hand. Nothing exercised the production wiring.
 */
class ToolLoopExtensionWiringTest {

    @TempDir
    Path root;

    /** One tool-calling turn by name, then an answer. Records what the model reads. */
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
                return new ModelOutcome.Completed("", List.of(
                    new ToolCall("call-1", toolName, argumentsJson)),
                    new Usage(10, 5, null, 0L), "tool_calls", Optional.empty(),
                    Optional.empty(), ContinuationEnvelope.empty("test"));
            }
            return new ModelOutcome.Completed("done", List.of(), new Usage(20, 5, null, 0L),
                "stop", Optional.empty(), Optional.empty(), ContinuationEnvelope.empty("test"));
        }
    }

    /** Answers every question as safe/unsupported; the gate is OFF, so only shape matters. */
    private static final class SafeEngine implements DecisionEngine {
        @Override
        public java.util.Map<String, rahu.core.decision.DecisionResult> askAll(State state,
            List<Question> questions) {
            var out = new java.util.LinkedHashMap<String, rahu.core.decision.DecisionResult>();
            for (Question q : questions) {
                String id = DecisionEngine.questionId(q);
                out.put(id, q instanceof DecisionEngine.NoulQuestion
                    ? new rahu.core.decision.DecisionResult.ValidNoul(id, false,
                        Optional.of(0.01))
                    : new rahu.core.decision.DecisionResult.Failure(
                        rahu.core.decision.DecisionResult.FailureKind.UNSUPPORTED, "n/a"));
            }
            return out;
        }
    }

    private static Tool thirdPartyReadOnly(AtomicBoolean ran) {
        return new Tool("acme.metrics", "0.1.0",
            new ToolDescriptor("acme.metrics", "A third-party read-only tool.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            (args, boundary) -> {
                ran.set(true);
                return ToolResult.success("answer-42", false);
            });
    }

    private String observeThroughLoop(ToolRegistry registry, PathBoundary boundary,
        ScriptedProvider provider) {
        var loop = new ToolLoop(registry, boundary, provider, new ToolCallLog(),
            new PrivacyGate(), new Provenance.Synthetic("test-fixture"), 4,
            new InjectionGate(new SafeEngine(), InjectionGate.Mode.OFF, 0.5), null,
            64 * 1024);
        loop.generate(new ModelRef("test/model"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("call the extension tool")), 256);
        return provider.lastConversation.stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .reduce("", (a, b) -> a + b);
    }

    @Test
    @DisplayName("A registered third-party tool executes end to end through the live tool loop")
    void registeredToolReachesTheModelThroughTheLoop() {
        // The assertion that fails if ToolLoop stops passing its registry to the
        // dispatcher -- the mutation that survived. It drives the real loop, the real
        // privacy gate and the real ToolCallLog, so the only way to pass is for the
        // registry to actually be consulted at dispatch time.
        var boundary = new PathBoundary(root);
        var ran = new AtomicBoolean();

        var observation = observeThroughLoop(ToolRegistry.of(thirdPartyReadOnly(ran)),
            boundary, new ScriptedProvider("acme.metrics", "{}"));

        assertTrue(ran.get(),
            "the registered tool's executor never ran. ToolLoop is almost certainly not "
                + "passing its registry to WorkspaceTools.executeToolCall, so a registered "
                + "tool is advertised to the model and then refused -- the "
                + "AUDIT-2026-10-03-w defect, restored at the CLI layer");
        assertTrue(observation.contains("answer-42"),
            "the extension tool's content must reach the model. Observed: " + observation);
        assertTrue(!observation.contains("unknown tool"),
            "the model must not be told the registered tool does not exist: " + observation);
    }

    @Test
    @DisplayName("A tool absent from the registry is still refused at dispatch")
    void unregisteredToolIsStillRefusedThroughTheLoop() {
        // The negative half, and what keeps the positive half honest: if the loop
        // dispatched by name alone, this would succeed.
        var boundary = new PathBoundary(root);
        var ran = new AtomicBoolean();
        var registry = ToolRegistry.of(thirdPartyReadOnly(new AtomicBoolean()));

        var observation = observeThroughLoop(registry, boundary,
            new ScriptedProvider("acme.never_registered", "{}"));

        assertTrue(!ran.get());
        assertTrue(observation.contains("unknown tool"),
            "an unregistered tool must be refused, not executed: " + observation);
    }

    @Test
    @DisplayName("The shipped workspace tools still work after the dispatch change")
    void builtinsStillDispatchThroughTheLoop() throws Exception {
        // The registry fallback sits BEHIND the built-in switch. If someone reorders it
        // so the registry answers first, or if the fallback shadowed a built-in, the
        // shipped tools must still behave.
        var boundary = new PathBoundary(root);
        Files.writeString(root.resolve("hello.txt"), "hello world");

        var observation = observeThroughLoop(ToolRegistry.withWorkspace(boundary), boundary,
            new ScriptedProvider("workspace.read", "{\"path\":\"hello.txt\"}"));

        assertTrue(observation.contains("hello world"),
            "workspace.read must still return file content through the loop: " + observation);
        assertTrue(!observation.contains("unknown tool"),
            "a built-in tool must never hit the registry fallback: " + observation);
    }

    @Test
    @DisplayName("An effectful tool cannot reach the loop, because registration refuses it")
    void effectfulToolNeverReachesDispatch() {
        // The two halves of the fix, joined. Registration is the only way into the
        // registry, so refusing an effectful tool there is sufficient; this asserts the
        // join holds from the loop's side.
        var boundary = new PathBoundary(root);
        var ran = new AtomicBoolean();
        var destructive = new Tool("acme.delete", "0.1.0",
            new ToolDescriptor("acme.delete", "Deletes things.", "{}"),
            (args, b) -> {
                ran.set(true);
                return ToolResult.success("deleted", false);
            },
            EffectClass.DESTRUCTIVE);

        try {
            ToolRegistry.of(destructive);
            fail("a DESTRUCTIVE tool must not be registrable; A26 requires registration to "
                + "reject non-read-only effect classes");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("DESTRUCTIVE"),
                "the refusal must name the effect class: " + expected.getMessage());
        }

        // With no registry obtainable, the loop cannot be handed the tool at all.
        var observation = observeThroughLoop(ToolRegistry.of(thirdPartyReadOnly(ran)),
            boundary, new ScriptedProvider("acme.delete", "{}"));

        assertTrue(!ran.get(), "the destructive executor must never run");
        assertTrue(observation.contains("unknown tool"),
            "an unregistrable tool is not dispatchable: " + observation);
    }
}