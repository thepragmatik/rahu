package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;

/**
 * Batched asks (systemone.md line 9: classification and relevance may be
 * batched when independent). askAll returns one typed result per question;
 * a missing answer degrades that question only, never the whole batch.
 */
class DecisionBatchAskTest {

    private HttpServer server;
    private SystemOneHttpAdapter adapter;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> requestCount = new AtomicReference<>(0);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            requestCount.set(requestCount.get() + 1);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
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

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static DecisionEngine.ChoiceQuestion classification() {
        return DecisionQuestions.classification();
    }

    private static DecisionEngine.NoulQuestion relevance(String tool) {
        return new DecisionEngine.NoulQuestion("tool:" + tool);
    }

    @Test
    @DisplayName("askAll sends ONE request and returns one typed result per question")
    void batchedAskReturnsPerQuestionResults() throws IOException {
        responseBody.set(load("batch-response.json"));

        var results = adapter.askAll(
            new DecisionEngine.State("repository-analysis", "request", 0.0),
            List.of(classification(), relevance("workspace.read"), relevance("workspace.search")));

        assertEquals(3, results.size(), "one result per question");
        assertEquals(1, requestCount.get(), "batched questions share one HTTP request");

        assertTrue(results.get("taskClass") instanceof DecisionResult.ValidChoice c
            && c.chosenLabel().equals("coding"));
        assertTrue(results.get("tool:workspace.read") instanceof DecisionResult.ValidNoul n
            && n.value());
        assertTrue(results.get("tool:workspace.search") instanceof DecisionResult.ValidNoul n
            && !n.value());
    }

    @Test
    @DisplayName("ask delegates to askAll and returns the first result")
    void askDelegatesToAskAll() throws IOException {
        responseBody.set(load("batch-response.json"));

        DecisionResult result = adapter.ask(
            new DecisionEngine.State("repository-analysis", "request", 0.0),
            List.of(classification(), relevance("workspace.read")));

        assertTrue(result instanceof DecisionResult.ValidChoice c
            && c.chosenLabel().equals("coding"));
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("A missing answer degrades that question only, not the whole batch")
    void missingAnswerDegradesSingleQuestion() throws IOException {
        responseBody.set(load("batch-response-missing.json"));

        var results = adapter.askAll(
            new DecisionEngine.State("repository-analysis", "request", 0.0),
            List.of(classification(), relevance("workspace.read"), relevance("workspace.search")));

        assertEquals(3, results.size());
        assertTrue(results.get("taskClass") instanceof DecisionResult.ValidChoice);
        assertTrue(results.get("tool:workspace.read") instanceof DecisionResult.ValidNoul);
        assertTrue(results.get("tool:workspace.search") instanceof DecisionResult.Failure f
            && f.kind() == DecisionResult.FailureKind.PROTOCOL_ERROR,
            "the unanswered question is a typed failure; answered ones stay valid");
    }

    private static String load(String name) throws IOException {
        try (InputStream in = DecisionBatchAskTest.class
            .getResourceAsStream("/systemone/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
