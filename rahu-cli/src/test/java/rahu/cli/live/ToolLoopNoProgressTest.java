package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.decision.DecisionResult;
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
import rahu.core.tools.ToolCallLog;
import rahu.core.tools.ToolRegistry;
import rahu.systemone.DecisionEngine;

/**
 * The NO_PROGRESS guard as the model actually meets it.
 *
 * <p>Written from a live G09 dogfood turn on 2026-10-03: the model re-issued one
 * failing {@code workspace.read} of a path that does not exist, 14 times, while
 * the surrounding batch varied. The batch-level fingerprint therefore never
 * matched itself and the loop only stopped at {@code maxCallsPerStep}, having
 * spent real tokens on a call that could never succeed.
 *
 * <p>The provider here never stops calling, so this asserts the guard terminates
 * the step rather than the step cap doing it by accident.
 */
class ToolLoopNoProgressTest {

    @TempDir
    Path root;

    /** Never answers: proposes the same impossible read forever. */
    private static final class StuckProvider implements ModelProvider {
        final String argumentsJson;
        int generations;

        StuckProvider(String argumentsJson) {
            this.argumentsJson = argumentsJson;
        }

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            generations++;
            return new ModelOutcome.Completed("", List.of(new ToolCall("call-" + generations,
                "workspace.read", argumentsJson)), new Usage(10, 5, null, 0L), "tool_calls",
                Optional.empty(), Optional.empty(), ContinuationEnvelope.empty("test"));
        }
    }

    /** Permits everything; this test is about termination, not the gates. */
    private static final class PermitEngine implements DecisionEngine {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("A tool call repeated with an unchanged outcome terminates as NO_PROGRESS")
    void repeatedCallTerminatesBeforeTheStepCap() {
        // A path that does not exist: the observation is identical every time.
        var boundary = new PathBoundary(root);
        var provider = new StuckProvider("{\"path\":\"does-not-exist.md\"}");
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50, new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        var outcome = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read the missing file")), 256);

        assertTrue(outcome instanceof ModelOutcome.Failed,
            "a stuck loop must terminate as a typed failure, not keep spending");
        var failed = (ModelOutcome.Failed) outcome;
        assertEquals(ModelOutcome.Failed.FailureKind.NO_PROGRESS, failed.kind(),
            "the failure must name NO_PROGRESS so the reason is visible, "
                + "not look like a provider or step-limit error");

        // The step cap was 50. The guard must fire far before it.
        assertTrue(provider.generations <= 5,
            "guard must stop the loop early, not let the step cap do it; generations="
                + provider.generations);
    }

    @Test
    @DisplayName("The guard does not fire while the observation keeps changing")
    void changingObservationsAreProgress() {
        // Reads a file that exists and is rewritten between generations, so each
        // observation genuinely differs and the turn must be allowed to continue.
        var boundary = new PathBoundary(root);
        var provider = new ChangingProvider(root);
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50, new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        var outcome = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("keep reading")), 256);

        assertTrue(outcome instanceof ModelOutcome.Completed,
            "a call whose observation changes is real progress and must not be cut off");
        assertTrue(provider.generations > 3,
            "the loop should have kept going while the content changed");
    }

    /** Same call every turn, but the file content changes, so the digest differs. */
    private static final class ChangingProvider implements ModelProvider {
        private final Path root;
        int generations;

        ChangingProvider(Path root) {
            this.root = root;
        }

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            generations++;
            // Rewrite the target so the next observation genuinely differs.
            try {
                java.nio.file.Files.writeString(
                    root.resolve("changing.md"), "revision " + generations + "\n");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            // Four changing reads, then a real final answer.
            if (generations > 4) {
                return new ModelOutcome.Completed("I read it four times and it changed each time.",
                    List.of(), new Usage(20, 5, null, 0L), "stop", Optional.empty(),
                    Optional.empty(), ContinuationEnvelope.empty("test"));
            }
            return new ModelOutcome.Completed("", List.of(new ToolCall("c" + generations,
                "workspace.read", "{\"path\":\"changing.md\"}")), new Usage(10, 5, null, 0L),
                "tool_calls", Optional.empty(), Optional.empty(),
                ContinuationEnvelope.empty("test"));
        }
    }

    /**
     * AUDIT-2026-10-03-z: a new user turn must reset the NO_PROGRESS streak.
     *
     * <p>acceptance.md A27 requires three clauses. Two are covered: NO_PROGRESS after the
     * third identical batch, and a changed observation resetting the streak. The third -
     * "a new user turn resets" - was implemented and unit-tested inside
     * {@link rahu.core.runtime.NoProgressDetector} but never exercised through the loop.
     *
     * <p>That gap mattered because {@code ToolLoop.generate()} resets every other
     * per-turn field ({@code executedCalls}, {@code injectionJudgments}, {@code
     * lastRerank}, {@code queryText}) and pointedly does NOT reset {@code noProgress}.
     * {@code noProgress.newTurn()} is called from exactly one place, the step-limit
     * exit. In {@code rahu chat} a single {@code ToolLoop} serves the whole session, so
     * the streak accumulated in one user turn survived into the next.
     *
     * <p>The shape below is a real conversation, not a contrived one: turn 1 leaves a
     * two-batch streak behind, then the user asks something unrelated and turn 2
     * legitimately makes the same call twice. Those two are NOT three repeats - the
     * user spoke in between - so turn 2 must complete.
     */
    @Test
    @DisplayName("A new user turn resets the streak, so an unrelated turn is not NO_PROGRESS")
    void newUserTurnResetsTheStreak() {
        var boundary = new PathBoundary(root);
        var provider = new TwoBatchThenAnswerProvider("does-not-exist.md");
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50,
            new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        // Turn 1: two identical batches, then the model answers. Streak ends at 2.
        var first = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read the missing file")), 256);
        assertTrue(first instanceof ModelOutcome.Completed,
            "turn 1 must complete; two repeats is not yet three");
        assertEquals(2, provider.batch,
            "turn 1 should have produced exactly two identical batches before answering, "
                + "leaving a streak of 2 to carry over");

        // Turn 2: a DIFFERENT user question. The model makes the same call twice and
        // then answers. Across the session that call has now appeared four times, but
        // only twice in this turn.
        provider.batch = 0;
        provider.generations = 0;
        var second = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("now tell me about the project layout")), 256);

        assertTrue(second instanceof ModelOutcome.Completed,
            "the guard fired on turn 2 because turn 1's streak was never cleared. A new "
                + "user turn is new work: A27 requires the streak to reset, so two "
                + "repeats in a fresh turn must NOT be reported as no progress.");
        assertEquals(2, provider.batch,
            "turn 2 should have run its own two batches and then answered, not been "
                + "cut off mid-turn");
    }

    /**
     * Two identical batches per turn, then a real answer. Reset by the driver between
     * generations so each turn is independently driven, exactly as a chat session is.
     */
    private static final class TwoBatchThenAnswerProvider implements ModelProvider {
        private final String argumentsJson;
        /** Generations issued, including the final answering one. */
        int generations;
        /** Tool batches proposed, which is what the detector actually sees. */
        int batch;

        TwoBatchThenAnswerProvider(String argumentsJson) {
            this.argumentsJson = argumentsJson;
        }

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            generations++;
            if (batch >= 2) {
                return new ModelOutcome.Completed("answered after two reads.",
                    List.of(), new Usage(20, 5, null, 0L), "stop", Optional.empty(),
                    Optional.empty(), ContinuationEnvelope.empty("test"));
            }
            batch++;
            return new ModelOutcome.Completed("", List.of(new ToolCall("c" + generations,
                "workspace.read", "{\"path\":\"" + argumentsJson + "\"}")),
                new Usage(10, 5, null, 0L), "tool_calls", Optional.empty(), Optional.empty(),
                ContinuationEnvelope.empty("test"));
        }
    }
}
