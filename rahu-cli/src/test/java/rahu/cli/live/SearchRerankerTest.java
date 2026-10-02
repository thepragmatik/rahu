package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;
import rahu.systemone.DecisionQuestions;

/**
 * Search rerank: reorder a search observation by decision-model relevance instead of
 * leaving it in filesystem order.
 *
 * <p>The invariant that matters most is that reranking REORDERS and never changes
 * membership. A reranker that drops or invents a line is not a ranking change, it is a
 * disclosure change, and it would sit downstream of the privacy gate where nothing else
 * re-checks it. These tests assert membership exhaustively, not by sampling.
 */
class SearchRerankerTest {

    private static final List<String> HITS = List.of(
        "src/Alpha.java:10: the pool is reduced to candidates the catalog can prove",
        "src/Beta.java:4: an unrelated constant",
        "src/Gamma.java:99: tryReserve refuses when the balance would go negative");

    /** Scripted decision engine: answers each candidate with a canned level. */
    private static final class ScriptedEngine implements DecisionEngine {
        private final Map<String, Integer> levels = new LinkedHashMap<>();
        int dispatches;
        String lastRequest = "";
        RuntimeException failWith;

        ScriptedEngine level(int index, int level) {
            levels.put(DecisionQuestions.relevanceQuestionId("hit-" + index), level);
            return this;
        }

        /** Leaves a candidate unanswered, to model a partial or degraded answer. */
        ScriptedEngine silent(int index) {
            return this;
        }

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            if (failWith != null) {
                throw failWith;
            }
            dispatches++;
            lastRequest = state.request();
            Map<String, DecisionResult> out = new LinkedHashMap<>();
            for (Question q : questions) {
                String id = DecisionEngine.questionId(q);
                Integer level = levels.get(id);
                if (level == null) {
                    out.put(id, new DecisionResult.Failure(
                        DecisionResult.FailureKind.PROTOCOL_ERROR, "no canned answer"));
                    continue;
                }
                out.put(id, new DecisionResult.ValidScore(id, level,
                    DecisionQuestions.RELEVANCE_LEGEND));
            }
            return out;
        }
    }

    @Test
    @DisplayName("Candidates are reordered by descending relevance")
    void reordersByRelevance() {
        var engine = new ScriptedEngine().level(0, 1).level(1, 0).level(2, 3);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        var result = gate.rerank("how does routing reduce the pool", HITS);

        assertEquals(List.of(
            "src/Gamma.java:99: tryReserve refuses when the balance would go negative",
            "src/Alpha.java:10: the pool is reduced to candidates the catalog can prove",
            "src/Beta.java:4: an unrelated constant"),
            result.ordered(),
            "the best-scoring hit must come first");
        assertEquals(1, engine.dispatches,
            "all candidates must be scored in ONE dispatch");
    }

    @Test
    @DisplayName("Reranking changes ORDER only: the same lines come out, none added or lost")
    void membershipIsPreservedExactly() {
        // This is the security-relevant invariant. A reranker that widened or narrowed
        // the observation would change what the model can see, and it runs AFTER the
        // privacy gate has already admitted the text.
        for (int[] levels : new int[][] {
            {3, 2, 1}, {0, 0, 0}, {2, 2, 2}, {1, 3, 2}}) {
            var engine = new ScriptedEngine().level(0, levels[0]).level(1, levels[1])
                .level(2, levels[2]);
            var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

            var result = gate.rerank("q", HITS);

            assertEquals(HITS.size(), result.ordered().size(),
                "rerank changed the NUMBER of lines for levels " + java.util.Arrays.toString(levels));
            assertEquals(new java.util.HashSet<>(HITS), new java.util.HashSet<>(result.ordered()),
                "rerank changed WHICH lines for levels " + java.util.Arrays.toString(levels));
        }
    }

    @Test
    @DisplayName("Equal scores keep filesystem order, so a rerank is stable not arbitrary")
    void equalScoresAreStable() {
        var engine = new ScriptedEngine().level(0, 2).level(1, 2).level(2, 2);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        assertEquals(HITS, gate.rerank("q", HITS).ordered(),
            "ties must not reshuffle results the model already understood");
    }

    @Test
    @DisplayName("OFF never asks the decision plane at all")
    void offNeverAsks() {
        var engine = new ScriptedEngine().level(0, 3).level(1, 3).level(2, 3);
        var gate = new SearchReranker(engine, SearchReranker.Mode.OFF, 20);

        var result = gate.rerank("q", HITS);

        assertEquals(0, engine.dispatches,
            "OFF must cost nothing, or enabling rerank by default would cost a call per search");
        assertEquals(HITS, result.ordered(), "OFF must leave filesystem order untouched");
    }

    @Test
    @DisplayName("SHADOW scores and reports the proposed order without applying it")
    void shadowReportsWithoutApplying() {
        var engine = new ScriptedEngine().level(0, 0).level(1, 3).level(2, 1);
        var gate = new SearchReranker(engine, SearchReranker.Mode.SHADOW, 20);

        var result = gate.rerank("q", HITS);

        assertEquals(1, engine.dispatches, "SHADOW must actually judge, or it proves nothing");
        assertEquals(HITS, result.ordered(),
            "SHADOW must not change what the model sees");
        assertEquals(List.of(
            "src/Beta.java:4: an unrelated constant",
            "src/Gamma.java:99: tryReserve refuses when the balance would go negative",
            "src/Alpha.java:10: the pool is reduced to candidates the catalog can prove"),
            result.proposed(),
            "SHADOW must record what it WOULD have done");
    }

    @Test
    @DisplayName("A decision-plane failure leaves filesystem order rather than guessing")
    void transportFailureFailsToOriginalOrder() {
        var engine = new ScriptedEngine().level(0, 3).level(1, 2).level(2, 1);
        engine.failWith = new IllegalStateException("decision service down");
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        var result = gate.rerank("q", HITS);

        assertEquals(HITS, result.ordered(),
            "an unreachable decision plane must not scramble or empty the results");
        assertEquals(SearchReranker.Verdict.UNJUDGEABLE, result.verdict(),
            "the gap must be visible in the trail, not hidden");
    }

    @Test
    @DisplayName("One unanswered candidate does not discard the others' scores")
    void partialAnswersKeepWhatTheyHave() {
        var engine = new ScriptedEngine().level(0, 3).level(2, 0).silent(1);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        var result = gate.rerank("q", HITS);

        assertEquals("src/Alpha.java:10: the pool is reduced to candidates the catalog can prove",
            result.ordered().get(0),
            "a scored candidate must still rank on its own score");
        assertEquals(new java.util.HashSet<>(HITS),
            new java.util.HashSet<>(result.ordered()),
            "an unanswered candidate must still be PRESENT, just not promoted");
    }

    @Test
    @DisplayName("More candidates than the cap are reranked in order and the tail is kept as-is")
    void candidateCapBoundsTheRequestWithoutLosingResults() {
        // The cap exists because the 16 KiB state bound is on the REQUEST. The failure
        // mode to avoid is silently dropping the tail, which would lose real matches.
        List<String> many = new ArrayList<>();
        ScriptedEngine engine = new ScriptedEngine();
        int legendSize = DecisionQuestions.RELEVANCE_LEGEND.size();
        for (int i = 0; i < 30; i++) {
            many.add("src/File" + i + ".java:1: content " + i);
            // Only the first 20 are scored. Within that prefix the strongest legal
            // level goes LAST, so a cap that silently dropped or reordered the wrong
            // prefix would be obvious here. Levels stay inside the legend.
            engine.level(i, i < 19 ? 0 : legendSize - 1);
        }
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        var result = gate.rerank("q", many);

        assertTrue(engine.lastRequest.length() <= 16 * 1024,
            "the scored batch must fit the state bound, was " + engine.lastRequest.length());
        assertEquals(30, result.ordered().size(),
            "capping the SCORED set must not drop results from the observation");
        assertEquals("src/File19.java:1: content 19", result.ordered().get(0),
            "the best SCORED candidate must lead the prefix");
        assertEquals("src/File20.java:1: content 20", result.ordered().get(20),
            "the unscored tail must follow in filesystem order, not be dropped");
        assertTrue(engine.lastRequest.contains("content 19") && !engine.lastRequest.contains("content 29"),
            "only the capped prefix should be asked about: " + engine.lastRequest);
    }

    @Test
    @DisplayName("Fewer candidates than the cap are all scored, no truncation")
    void underCapScoresEverything() {
        var engine = new ScriptedEngine().level(0, 1).level(1, 2);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        gate.rerank("q", List.of("a:1: x", "b:2: y"));

        assertTrue(engine.lastRequest.contains("a:1: x") && engine.lastRequest.contains("b:2: y"),
            "every candidate under the cap must reach the decision plane: " + engine.lastRequest);
    }

    @Test
    @DisplayName("Nothing to rerank is not an error and costs no dispatch")
    void emptyOrSingleCandidateIsANoOp() {
        var engine = new ScriptedEngine().level(0, 1);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        assertEquals(List.of(), gate.rerank("q", List.of()).ordered());
        assertEquals(List.of("only:1: x"), gate.rerank("q", List.of("only:1: x")).ordered());
        assertEquals(0, engine.dispatches,
            "a search with nothing to reorder must not spend a decision call");
    }

    @Test
    @DisplayName("The query travels with the candidates so relevance has something to judge against")
    void queryTravelsWithTheCandidates() {
        var engine = new ScriptedEngine().level(0, 1).level(1, 2);
        var gate = new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 20);

        gate.rerank("how does the pool get reduced",
            List.of("src/A.java:1: prose", "src/B.java:2: prose"));

        assertTrue(engine.lastRequest.contains("how does the pool get reduced"),
            "without the query the model cannot judge relevance at all: "
                + engine.lastRequest);
        assertTrue(engine.lastRequest.contains("src/A.java:1: prose"),
            "the candidate text must travel too: " + engine.lastRequest);
    }

    @Test
    @DisplayName("A non-positive or oversized cap is refused at construction")
    void unusableCapIsRefused() {
        var engine = new ScriptedEngine();
        assertThrows(IllegalArgumentException.class,
            () -> new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchReranker(engine, SearchReranker.Mode.ENFORCE, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchReranker(engine, SearchReranker.Mode.ENFORCE, 5000),
            "a cap above the ceiling would allow an unbounded batch");
    }

    @Test
    @DisplayName("Reranking cannot be enabled without a decision engine")
    void engineIsRequired() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> new SearchReranker(null, SearchReranker.Mode.ENFORCE, 20));
        assertTrue(e.getMessage().contains("decision engine"));
    }
}