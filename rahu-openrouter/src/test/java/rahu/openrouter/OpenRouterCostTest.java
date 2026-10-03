package rahu.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.model.ChatMessage;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;

/**
 * Billed-cost capture (openrouter.md usage mapping; A10). OpenRouter reports
 * cost in dollars only when the request asks for it, so the adapter must ask —
 * and must leave the value unknown, never zero, when the provider stays silent.
 */
class OpenRouterCostTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private OpenRouterProvider provider;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new OpenRouterProvider(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
            () -> Optional.of("test-key"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private GenerationRequest request() {
        return new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("q")), List.of(), 64);
    }

    @Test
    @DisplayName("Reported dollar cost becomes exact micros and usage.include is requested")
    void reportedCostIsCaptured() throws Exception {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":0.000014112}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertEquals(14L, outcome.usage().totalCostMicrosOpt().orElseThrow(),
            "0.000014112 USD rounds to 14 micros");
        assertEquals(10, outcome.usage().promptTokensOpt().orElseThrow());
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertTrue(body.path("usage").path("include").asBoolean(false),
            "the request must ask OpenRouter to include billed cost");
    }

    @Test
    @DisplayName("Absent cost stays unknown; it is never reported as zero (A10)")
    void absentCostIsUnknown() {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertTrue(outcome.usage().totalCostMicrosOpt().isEmpty(),
            "a silent provider must remain unknown, not zero");
    }

    // ------------------------------------------------- C-1: a positive cost that rounds to 0

    /**
     * AUDIT-2026-10-03-u. OpenRouter bills in dollars; the ledger holds exact micros.
     * A reported cost that is POSITIVE but smaller than half a micro converts to 0
     * micros. Because {@code parseCostMicros} returned {@code Optional.of(0L)} for
     * that, {@code LiveTurnDriver.account} took the "cost was reported" branch and
     * called {@code settle(..., $0.000000, true)} -- a CONFIDENT settled zero.
     *
     * <p>That is the one thing {@code TurnOutcome.costUnobserved} exists to prevent:
     * a caller rendering "$0.00" is asserting the provider billed nothing. The
     * provider billed something. We simply cannot hold it at micro resolution.
     * The honest state is UNCERTAIN, which the ledger already has.
     */
    @Test
    @DisplayName("A positive cost too small for micros stays UNKNOWN, not a settled zero")
    void subMicrodollarCostIsNotASettledZero() {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":0.0000004}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertTrue(outcome.usage().totalCostMicrosOpt().isEmpty(),
            "0.0000004 USD is positive and billed; reporting it as 0 micros would let the "
                + "ledger settle a confident zero. It must stay unknown so the reservation "
                + "is marked uncertain instead. Got: "
                + outcome.usage().totalCostMicrosOpt());
    }

    @Test
    @DisplayName("A genuinely free call still reports a real zero")
    void genuineZeroCostIsStillZero() {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":0}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertEquals(0L, outcome.usage().totalCostMicrosOpt().orElseThrow(),
            "an exact 0.0 is a real observation of free, not a rounding artifact, and it must "
                + "survive as a settled zero -- otherwise every free call looks uncertain");
    }

    @Test
    @DisplayName("Half a micro or more still settles; the boundary is not fuzzy")
    void halfAMicroStillSettles() {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":0.0000005}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertEquals(1L, outcome.usage().totalCostMicrosOpt().orElseThrow(),
            "0.5 micros rounds up to a real 1 micro, so it is known and must settle");
    }

    @Test
    @DisplayName("The conversion is exact decimal, not binary floating point")
    void conversionIsExactDecimal() {
        // 0.1 + 0.2 style drift: in double, this is not exactly 3 micros worth.
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":8.675309e-6}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertEquals(9L, outcome.usage().totalCostMicrosOpt().orElseThrow(),
            "exact-decimal conversion of 8.675309 micros rounds to 9");
    }

    // ------------------------------------------- negatives and magnitudes

    @Test
    @DisplayName("A negative cost is refused, not settled as a credit")
    void negativeCostIsRefused() {
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":-0.000014}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertTrue(outcome.usage().totalCostMicrosOpt().isEmpty(),
            "a negative cost would settle as a credit against the allowance, and "
                + "Ledger.settleUp would then reduce the parent's committed amount. "
                + "An impossible reading must be unknown, not a number.");
    }

    @Test
    @DisplayName("A cost too large to hold in micros is refused, not silently wrapped")
    void overflowingCostIsRefused() {
        // 1e15 USD is 1e21 micros, past Long.MAX (9.22e18). Casting that to a long
        // wraps NEGATIVE, which would read as a credit and could pass a cost gate.
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":1e15}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertTrue(outcome.usage().totalCostMicrosOpt().isEmpty(),
            "1e21 micros must not wrap into a long; got "
                + outcome.usage().totalCostMicrosOpt());
    }

    @Test
    @DisplayName("A cost at the top of the long range still settles exactly")
    void largestHoldableCostStillSettles() {
        // 9223372036854.775807 USD == Long.MAX_VALUE micros. This is the boundary
        // just below the overflow guard, and it must NOT be refused.
        responseBody.set("{\"model\":\"observed\",\"choices\":[{\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
            + "\"cost\":9223372036854.775807}}");

        ModelOutcome.Completed outcome = (ModelOutcome.Completed) provider.generate(request());

        assertEquals(Long.MAX_VALUE, outcome.usage().totalCostMicrosOpt().orElseThrow(),
            "the largest exactly-representable cost must still be reported, not refused "
                + "as overflow");
    }
}
