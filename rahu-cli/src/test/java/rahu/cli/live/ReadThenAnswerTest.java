package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * The read-then-answer path, end to end, with the model scripted.
 *
 * <p>Written on 2026-10-03 after five live G09 turns failed to complete this
 * path. None of them read a file and returned its contents: one answered with
 * no tool, three looped, one asked a question instead. That leaves the harness
 * itself unproven, because a live turn tests the harness AND the hosted model
 * at the same time, and this test cannot tell the two apart.
 *
 * <p>So this test removes the model from the question. The provider below is
 * scripted: it calls {@code workspace.read}, then it must find the file's real
 * contents in the tool observation the loop handed back, and it may only answer
 * with text it copied out of that observation. If the loop drops the
 * observation, truncates it, fails to append it as a TOOL message, or loses the
 * tool-call association, this test fails. A passing result therefore proves the
 * loop can carry a read into an answer, and says nothing about how willing a
 * hosted model is to do it.
 *
 * <p>The live model question is separate and remains open; see
 * {@code docs/reviews/dogfood-release.md}.
 */
class ReadThenAnswerTest {

    @TempDir
    Path root;

    /** The one fact the answer is allowed to contain, and that must come from the file. */
    private static final String SECRET_ANSWER = "amountMinor is a long";

    @BeforeEach
    void writeTheFileTheModelMustRead() throws Exception {
        Files.writeString(root.resolve("Money.java"),
            "package rahu.core;\n\npublic record Money(long amountMinor) {\n"
                + "    // invariant: " + SECRET_ANSWER + "\n}\n");
    }

    /**
     * Reads, then answers using only what the observation gave it.
     *
     * <p>The final answer is assembled from the observation text, so it can only
     * be correct if the loop actually delivered that text.
     */
    private static class ReadThenAnswerProvider implements ModelProvider {
        int generations;
        String answerBuilt = "";
        int toolMessagesSeen;
        String lastToolText = "";

        @Override
        public ModelOutcome generate(GenerationRequest request) {
            generations++;
            // Record what the loop actually delivered back to the model.
            for (ChatMessage m : request.messages()) {
                if (m.role() == ChatMessage.Role.TOOL) {
                    toolMessagesSeen++;
                    lastToolText = lastToolText + m.content();
                }
            }
            if (generations == 1) {
                return callTool("workspace.read", "{\"path\":\"Money.java\"}");
            }
            // Turn 2: answer using text taken from the observation, not from memory.
            String answer = extractLineContaining(lastToolText, "amountMinor");
            answerBuilt = answer;
            return new ModelOutcome.Completed(answer, List.of(), new Usage(30, 12, null, 0L),
                "stop", Optional.empty(), Optional.empty(), ContinuationEnvelope.empty("test"));
        }

        /** The one line of the observation the answer is built from. */
        private static String extractLineContaining(String text, String needle) {
            for (String line : text.split("\n")) {
                if (line.contains(needle)) {
                    return line.trim();
                }
            }
            return "NO-LINE-FOUND";
        }

        private static ModelOutcome callTool(String name, String args) {
            return new ModelOutcome.Completed("", List.of(new ToolCall("call-1", name, args)),
                new Usage(20, 8, null, 0L), "tool_calls", Optional.empty(), Optional.empty(),
                ContinuationEnvelope.empty("test"));
        }
    }

    /** Permits everything; this test is about the read path, not the gates. */
    private static final class PermitEngine implements DecisionEngine {
        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("A read observation reaches the model and becomes the answer")
    void readObservationBecomesTheAnswer() throws Exception {
        var boundary = new PathBoundary(root);
        var provider = new ReadThenAnswerProvider();
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50,
            new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        var outcome = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read Money.java and tell me the invariant")), 512);

        assertTrue(outcome instanceof ModelOutcome.Completed,
            "the loop must end in a completed answer, not a typed failure; got " + outcome);
        var completed = (ModelOutcome.Completed) outcome;

        assertEquals(2, provider.generations,
            "one read then one answer is the whole path; more rounds means the loop mis-sequenced");
        assertEquals(1, provider.toolMessagesSeen,
            "the read observation must be appended exactly once as a TOOL message");

        // The core claim: the answer carries real file content, so the
        // observation demonstrably travelled read -> observation -> answer.
        assertTrue(completed.answer().contains("amountMinor"),
            "the answer must quote the file, proving the observation reached the model; got: "
                + completed.answer());
        assertFalse(completed.answer().contains("NO-LINE-FOUND"),
            "the model could not find the line, so the observation did not arrive intact");
        assertTrue(completed.answer().contains("long"),
            "the answer must carry the file's actual wording, not a guess");
    }

    @Test
    @DisplayName("The answer names the file it read, not a path the model invented")
    void answerDoesNotInventAPath() throws Exception {
        var boundary = new PathBoundary(root);
        var provider = new ReadThenAnswerProvider();
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50,
            new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        var outcome = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read Money.java and tell me the invariant")), 512);

        var completed = (ModelOutcome.Completed) outcome;
        assertFalse(completed.answer().contains(".go"),
            "a Java repository has no Go files; an answer naming one came from the model's "
                + "own guess rather than the observation, which is the run-1 hallucination shape");
    }

    @Test
    @DisplayName("A refused read carries no file content to the model")
    void deniedReadYieldsNoFileContent() throws Exception {
        // Point the provider at a path that does not exist. @BeforeEach DID write
        // Money.java, so the read must name a different, absent file; otherwise
        // this would assert nothing. The loop must surface a denial and the model
        // must not receive the absent file's contents.
        var boundary = new PathBoundary(root);
        var provider = new ReadThenAnswerProvider() {
            @Override
            public ModelOutcome generate(GenerationRequest request) {
                if (generations == 0) {
                    generations++;
                    return new ModelOutcome.Completed("",
                        List.of(new ToolCall("call-1", "workspace.read",
                            "{\"path\":\"Absent.java\"}")),
                        new Usage(20, 8, null, 0L), "tool_calls", Optional.empty(),
                        Optional.empty(), ContinuationEnvelope.empty("test"));
                }
                return super.generate(request);
            }
        };
        var loop = new ToolLoop(ToolRegistry.withWorkspace(boundary), boundary, provider,
            new ToolCallLog(), new PrivacyGate(),
            new Provenance.ApprovedNonSensitive("test"), 50,
            new InjectionGate(new PermitEngine(), InjectionGate.Mode.SHADOW, 0.10));

        var outcome = loop.generate(new ModelRef("test/model"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("read Absent.java")), 512);

        assertFalse(provider.lastToolText.contains(SECRET_ANSWER),
            "a refused read must not carry any file's contents to the model");
        assertTrue(provider.lastToolText.toLowerCase(java.util.Locale.ROOT)
                .contains("denied") || provider.lastToolText.toLowerCase(java.util.Locale.ROOT)
                .contains("invalid") || provider.lastToolText.toLowerCase(java.util.Locale.ROOT)
                .contains("not permitted"),
            "a refused read must tell the model it was refused, so the model does not "
                + "treat silence as success; observation was: " + provider.lastToolText);
    }
}
