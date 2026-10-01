package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;

/**
 * S05 System One HTTP adapter contract tests (systemone.md; A02, A06). Local
 * recording server; fixtures are the repo's authored synthetic examples.
 */
class SystemOneAdapterTest {

    private HttpServer server;
    private SystemOneHttpAdapter adapter;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
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

    private static String load(String name) throws IOException {
        try (InputStream in = SystemOneAdapterTest.class
            .getResourceAsStream("/systemone/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DecisionEngine.ChoiceQuestion routeQuestion() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("fast@low", "Permitted analysis candidate with read-only tool support");
        criteria.put("quality@medium", "Permitted analysis candidate with medium reasoning");
        return new DecisionEngine.ChoiceQuestion("route", criteria);
    }

    @Test
    @DisplayName("Request maps state/questions to the pinned envelope shape")
    void requestShape() throws Exception {
        adapter.ask(new DecisionEngine.State("repository-analysis",
            "Explain the four planned Rahu modules", 0.85),
            java.util.List.of(routeQuestion()));

        com.fasterxml.jackson.databind.JsonNode body =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody.get());
        assertEquals("demo-decision", body.path("model").asText());
        assertEquals("repository-analysis", body.path("state").path("operation").asText());
        assertEquals("choice", body.path("questions").path("route").path("type").asText());
        assertTrue(body.path("questions").path("route").path("criteria").has("fast@low"));
    }

    @Test
    @DisplayName("Valid response parses choice + probabilities + separate raw confidence")
    void validResponseParses() throws IOException {
        responseBody.set(load("response.json"));
        DecisionResult result = adapter.ask(
            new DecisionEngine.State("repository-analysis", "request", 0.85),
            java.util.List.of(routeQuestion()));

        assertTrue(result instanceof DecisionResult.ValidChoice c
            && c.chosenLabel().equals("fast@low"));
        var c = (DecisionResult.ValidChoice) result;
        assertEquals(0.8, c.probabilities().get("fast@low"));
        assertEquals(Optional.of(0.6), c.rawConfidence(),
            "raw confidence (0.6) is separate from chosen probability (0.8)");
        assertEquals("concentration", c.confidenceSemantics());
    }

    @Test
    @DisplayName("A06: unknown label (invalid-response.json) is a typed protocol failure")
    void invalidLabelRejected() throws IOException {
        responseBody.set(load("invalid-response.json"));
        DecisionResult result = adapter.ask(
            new DecisionEngine.State("repository-analysis", "request", 0.85),
            java.util.List.of(routeQuestion()));

        assertTrue(result instanceof DecisionResult.Failure f
            && f.kind() == DecisionResult.FailureKind.PROTOCOL_ERROR,
            "unknown label must be rejected, not repaired");
    }

    @Test
    @DisplayName("A06: malformed JSON, NaN probabilities and missing answers are typed failures")
    void malformedResponsesRejected() {
        for (String bad : new String[] {"not json",
            "{\"model\":\"m\",\"answers\":{\"route\":{\"type\":\"choice\","
                + "\"choice\":\"fast@low\",\"probabilities\":{\"fast@low\":NaN}}}}",
            "{\"model\":\"m\"}"}) {
            responseBody.set(bad);
            DecisionResult result = adapter.ask(
                new DecisionEngine.State("op", "request", 0.0),
                java.util.List.of(routeQuestion()));
            assertTrue(result instanceof DecisionResult.Failure,
                "expected typed failure for: " + bad);
        }
    }

    @Test
    @DisplayName("Noul question parses finite probability; out-of-range rejected")
    void noulParsing() throws IOException {
        responseBody.set("{\"model\":\"m\",\"answers\":{\"q1\":{\"type\":\"noul\",\"noul\":0.9}}}");
        DecisionResult result = adapter.ask(new DecisionEngine.State("op", "request", 0.0),
            java.util.List.of(new DecisionEngine.NoulQuestion("q1")));
        assertTrue(result instanceof DecisionResult.ValidNoul n && n.value());

        responseBody.set("{\"model\":\"m\",\"answers\":{\"q1\":{\"type\":\"noul\",\"noul\":1.5}}}");
        DecisionResult bad = adapter.ask(new DecisionEngine.State("op", "request", 0.0),
            java.util.List.of(new DecisionEngine.NoulQuestion("q1")));
        assertTrue(bad instanceof DecisionResult.Failure);
    }

    @Test
    @DisplayName("HTTP 500 and 429 become typed failures; timeout honoured")
    void httpFailures() {
        status.set(500);
        DecisionResult r500 = adapter.ask(new DecisionEngine.State("op", "r", 0.0),
            java.util.List.of(routeQuestion()));
        assertTrue(r500 instanceof DecisionResult.Failure f
            && f.kind() == DecisionResult.FailureKind.PROTOCOL_ERROR);

        status.set(429);
        DecisionResult r429 = adapter.ask(new DecisionEngine.State("op", "r", 0.0),
            java.util.List.of(routeQuestion()));
        assertTrue(r429 instanceof DecisionResult.Failure);
    }

    @Test
    @DisplayName("truncated=true response is rejected under the default profile")
    void truncatedRejected() throws IOException {
        String body = load("response.json").replaceFirst("\\{",
            "{\"truncated\":true,");
        responseBody.set(body);
        DecisionResult result = adapter.ask(
            new DecisionEngine.State("repository-analysis", "request", 0.85),
            java.util.List.of(routeQuestion()));
        assertTrue(result instanceof DecisionResult.Failure,
            "decision on truncated input is not the admitted decision");
    }
}
