package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.decision.DecisionResult;
import rahu.core.decision.TurnProfile;
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
import rahu.systemone.DecisionEngine;

/**
 * Is the advisory tool-relevance judgment actually ACTIVE?
 *
 * <p>Audit finding F-2. The decision plane is asked which tools are relevant every
 * turn, {@link TurnProfile} carries the narrowed set, and the driver PRINTS it. This
 * test asks the only question that matters: does a tool the decision judged
 * IRRELEVANT stop being advertised to the model?
 *
 * <p>tools.md line 7 requires it: "The generation model receives only the permitted
 * relevant tools." The relevance judgment is advisory in the sense that it may only
 * NARROW — it can never widen and it can never grant execution rights. Narrowing is
 * the whole of its effect, so if the narrowed set never reaches the registry, the
 * decision is computed, paid for, printed, and discarded.
 *
 * <p>Wired with the unrestricted workspace registry plus {@code narrowTo}, which is
 * how {@code LiveTurnDriver} now passes the profile's relevance set. Before the
 * narrowing existed this test was red: all three tools were advertised regardless of
 * the judgment.
 */
class ToolRelevanceReachabilityTest {

    @TempDir
    Path root;

    /** Records the tool names the model was actually offered. */
    private static final class DescriptorRecorder implements ModelProvider {

        List<String> offeredNames;

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            if (offeredNames == null) {
                offeredNames = request.tools().stream()
                    .map(rahu.core.model.ToolDescriptor::name).toList();
                return new ModelOutcome.Completed("", List.of(
                        new ToolCall("call-1", "workspace.search", "{\"text\":\"x\"}")),
                    new Usage(10, 5, null, 0L), "tool_calls", Optional.empty(), Optional.empty(),
                    rahu.core.model.ContinuationEnvelope.empty("test"));
            }
            return new ModelOutcome.Completed("done", List.of(), new Usage(20, 5, null, 0L),
                "stop", Optional.empty(), Optional.empty(),
                rahu.core.model.ContinuationEnvelope.empty("test"));
        }
    }

    /**
     * Judges search IRRELEVANT and the other two relevant. Not "everything
     * irrelevant": TurnProfile.from maps an empty included set back to the full
     * permitted set, so an all-irrelevant judgment is indistinguishable from an
     * unjudgeable one and cannot express "narrow to nothing" at all.
     */
    private static final class SearchIrrelevant implements DecisionEngine {

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            Map<String, DecisionResult> out = new LinkedHashMap<>();
            for (Question q : questions) {
                String id = DecisionEngine.questionId(q);
                if (id.startsWith("tool:")) {
                    boolean irrelevant = id.equals("tool:workspace.search");
                    out.put(id, new DecisionResult.ValidNoul(id, !irrelevant,
                        Optional.of(irrelevant ? 0.05 : 0.9)));
                } else if (id.equals("taskClass")) {
                    Map<String, Double> dist = new LinkedHashMap<>();
                    for (String label : List.of("answer", "coding", "analysis",
                        "classification", "summarisation", "unknown")) {
                        dist.put(label, "answer".equals(label) ? 0.9 : 0.02);
                    }
                    out.put(id, new DecisionResult.ValidChoice(id, "answer", dist,
                        Optional.of(0.9), "concentration"));
                } else {
                    out.put(id, new DecisionResult.Failure(
                        DecisionResult.FailureKind.UNSUPPORTED, "not scripted"));
                }
            }
            return out;
        }
    }

    @Test
    @DisplayName("A tool the decision judged irrelevant is NOT advertised to the model")
    void judgedIrrelevantToolIsNotOffered() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "content\n");
        var engine = new SearchIrrelevant();

        // The decision plane narrows the set.
        TurnProfile profile = new ProfileDecider(engine,
            List.of("workspace.list", "workspace.read", "workspace.search"))
            .decide("what does this project do?", 0.0);
        assertFalse(profile.relevantTools().contains("workspace.search"),
            "precondition: the decision judged workspace.search irrelevant, so the "
                + "profile must not carry it");
        assertTrue(profile.relevantTools().contains("workspace.read"),
            "precondition: the other tools stay relevant, so the narrowing is partial");

        // Now the real loop, wired the way ChatCommand wires it: the full workspace
        // registry, with profile.relevantTools() available but unused.
        var boundary = new PathBoundary(root);
        var provider = new DescriptorRecorder();
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(), new Provenance.ApprovedNonSensitive("test"),
            8, new InjectionGate(engine, InjectionGate.Mode.OFF, 0.5),
            new SearchReranker(engine, SearchReranker.Mode.OFF, 20));

        loop.narrowTo(profile.relevantTools());
        loop.generate(new ModelRef("test/model"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("what does this project do?")), 256);

        assertTrue(provider.offeredNames != null, "the model was never offered any tool");
        assertFalse(provider.offeredNames.contains("workspace.search"),
            "the decision judged workspace.search irrelevant, yet it is still advertised "
                + "to the model: " + provider.offeredNames);
    }
}