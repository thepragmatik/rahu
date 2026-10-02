package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;

/**
 * The adapter is the ONLY place a typed question becomes wire JSON and a wire answer
 * becomes a typed result (systemone.md). These tests drive the real local recording
 * server, so they assert the bytes on the wire, not the builder's intent.
 */
class ScoreWireMappingTest {

    /** Widens the typed questions to the port type, as production callers do. */
    private static List<DecisionEngine.Question> questions(List<String> candidateIds) {
        return DecisionQuestions.relevance(candidateIds).stream()
            .map(q -> (DecisionEngine.Question) q).toList();
    }

    private static DecisionEngine.State query(String q) {
        return new DecisionEngine.State("RELEVANCE", q, 0.0);
    }

    @Test
    @DisplayName("A score question is sent as wire type score carrying its legend")
    void scoreQuestionSerialisesWithLegend() {
        var engine = new RecordingEngine("""
            {"answers": {"relevance:src/A.java:12": {"type": "score", "level": 3}}}
            """);
        engine.askAll(query("how does routing work"),
            questions(List.of("src/A.java:12")));

        String body = engine.lastRequestBody;
        assertTrue(body.contains("\"type\":\"score\""),
            "the wire type must be score, got: " + body);
        assertTrue(body.contains("legend"),
            "a score question without its legend on the wire is unanswerable, got: " + body);
        assertTrue(body.contains("the single best match for the query"),
            "the legend text must reach the model, got: " + body);
    }

    @Test
    @DisplayName("A valid score answer becomes ValidScore with the level and legend")
    void scoreAnswerBecomesValidScore() {
        var engine = new RecordingEngine("""
            {"answers": {"relevance:src/A.java:12": {"type": "score", "level": 2}}}
            """);
        var results = engine.askAll(query("q"), questions(List.of("src/A.java:12")));

        var score = assertInstanceOf(DecisionResult.ValidScore.class,
            results.get("relevance:src/A.java:12"));
        assertEquals(2, score.level());
        assertEquals(DecisionQuestions.RELEVANCE_LEGEND, score.legend());
    }

    @Test
    @DisplayName("A level outside the legend degrades alone to a typed failure")
    void outOfRangeLevelDegradesAlone() {
        // The dangerous failure here is a level of 9 sorting FIRST and looking like a
        // strong match. It must be a failure, and its siblings must still answer.
        var engine = new RecordingEngine("""
            {"answers": {
              "relevance:a": {"type": "score", "level": 9},
              "relevance:b": {"type": "score", "level": 1}
            }}
            """);
        var results = engine.askAll(query("q"), questions(List.of("a", "b")));

        assertInstanceOf(DecisionResult.Failure.class, results.get("relevance:a"),
            "an out-of-legend level must not become a score");
        assertEquals(1, assertInstanceOf(DecisionResult.ValidScore.class,
            results.get("relevance:b")).level(),
            "one bad answer must not fail its sibling in the same batch");
    }

    @Test
    @DisplayName("A non-integer or missing level is refused")
    void malformedLevelIsRefused() {
        for (String body : List.of(
            """
            {"answers": {"relevance:a": {"type": "score", "level": 1.5}}}
            """,
            """
            {"answers": {"relevance:a": {"type": "score"}}}
            """,
            """
            {"answers": {"relevance:a": {"type": "score", "level": "2"}}}
            """)) {
            var engine = new RecordingEngine(body);
            var results = engine.askAll(query("q"), questions(List.of("a")));
            assertInstanceOf(DecisionResult.Failure.class, results.get("relevance:a"),
                "a malformed level must fail closed, accepted: " + body);
        }
    }

    @Test
    @DisplayName("An answer of the wrong type for a score question is refused")
    void wrongAnswerTypeIsRefused() {
        var engine = new RecordingEngine("""
            {"answers": {"relevance:a": {"type": "noul", "noul": 0.9}}}
            """);
        var results = engine.askAll(query("q"), questions(List.of("a")));
        assertInstanceOf(DecisionResult.Failure.class, results.get("relevance:a"),
            "a noul answer must not be coerced into a relevance level");
    }

    @Test
    @DisplayName("A batch of candidates is asked in ONE dispatch and keyed per candidate")
    void batchOfCandidatesIsOneDispatch() {
        var engine = new RecordingEngine("""
            {"answers": {
              "relevance:a": {"type": "score", "level": 3},
              "relevance:b": {"type": "score", "level": 0},
              "relevance:c": {"type": "score", "level": 2}
            }}
            """);
        var results = engine.askAll(query("q"), questions(List.of("a", "b", "c")));

        assertEquals(1, engine.dispatches,
            "candidates must share one dispatch, or a search costs N decision calls");
        assertEquals(List.of("relevance:a", "relevance:b", "relevance:c"),
            List.copyOf(results.keySet()),
            "results must be keyed by candidate in batch order");
    }

    /** A local recording HTTP server, reused rather than reimplemented per test. */
    private static final class RecordingEngine implements DecisionEngine {
        private final SystemOneHttpAdapter adapter;
        String lastRequestBody = "";
        int dispatches;

        RecordingEngine(String responseJson) {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("recording server", e);
            }
            server.createContext("/v1/systemone", exchange -> {
                dispatches++;
                lastRequestBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
                byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            adapter = new SystemOneHttpAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone",
                "demo-decision", "jev-compatible-v1", 5000, () -> Optional.empty());
        }

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            return adapter.askAll(state, questions);
        }
    }
}