package rahu.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import rahu.core.model.ContinuationEnvelope;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ToolDescriptor;

/**
 * S04 OpenRouter generation contract tests against a local recording server
 * (synthetic fixtures; no network, no keys).
 */
class OpenRouterProviderTest {

    private HttpServer server;
    private OpenRouterProvider provider;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String successBody(String finishReason, boolean withUsage) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"chatcmpl-1\",\"model\":\"observed-model\",\"choices\":[{")
            .append("\"message\":{\"role\":\"assistant\",\"content\":\"the answer\"},")
            .append("\"finish_reason\":\"").append(finishReason).append("\"}]");
        if (withUsage) {
            sb.append(",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22,"
                + "\"total_tokens\":33}");
        }
        sb.append("}");
        return sb.toString();
    }

    @Test
    @DisplayName("Request maps model/messages/tools/reasoning exactly; ProviderDefault omits reasoning")
    void requestMappingProviderDefault() throws Exception {
        var request = new GenerationRequest(new ModelRef("demo-fast"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.system("be terse"), ChatMessage.user("hi")),
            List.of(), 256);
        provider.generate(request);

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("demo-fast", body.path("model").asText());
        assertEquals(2, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals(256, body.path("max_tokens").asInt());
        assertTrue(body.path("reasoning").isMissingNode(),
            "ProviderDefault must omit reasoning controls");
        assertTrue(body.path("provider").path("require_parameters").asBoolean(false),
            "require_parameters must be true for controlled requests");
    }

    @Test
    @DisplayName("Explicit effort maps to reasoning.effort; Disabled maps to enabled=false")
    void effortMapping() throws Exception {
        provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW),
            List.of(ChatMessage.user("q")), List.of(), 64));
        assertEquals("low", MAPPER.readTree(lastBody.get()).path("reasoning")
            .path("effort").asText());

        provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.Disabled.INSTANCE,
            List.of(ChatMessage.user("q")), List.of(), 64));
        assertFalse(MAPPER.readTree(lastBody.get()).path("reasoning")
            .path("enabled").asBoolean(true), "Disabled must send reasoning.enabled=false");
    }

    @Test
    @DisplayName("Tool descriptors map to the tools array with function schema")
    void toolDescriptorMapping() throws Exception {
        var tools = List.of(new ToolDescriptor("workspace.read", "Read a file",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));
        provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("q")), tools, 64));

        JsonNode toolsNode = MAPPER.readTree(lastBody.get()).path("tools");
        assertEquals(1, toolsNode.size());
        assertEquals("function", toolsNode.get(0).path("type").asText());
        assertEquals("workspace.read", toolsNode.get(0).path("function").path("name").asText());
        assertTrue(toolsNode.get(0).path("function").path("parameters").has("properties"));
    }

    @Test
    @DisplayName("Multiple proposed tool calls parse in order; opaque reasoning captured in envelope")
    void toolCallOrderAndContinuation() throws Exception {
        responseBody.set("{\"id\":\"x\",\"model\":\"observed-model\",\"choices\":[{"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
            + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"workspace.list\","
            + "\"arguments\":\"{}\"}},{\"id\":\"call_2\",\"type\":\"function\","
            + "\"function\":{\"name\":\"workspace.read\",\"arguments\":\"{\\\"path\\\":\\\"a.md\\\"}\"}}],"
            + "\"reasoning\":\"opaque-chain\"},"
            + "\"finish_reason\":\"tool_calls\"}]}");

        var outcome = provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("q")), List.of(), 512));

        assertTrue(outcome instanceof ModelOutcome.Completed completed && completed.hasToolCalls());
        var completed = (ModelOutcome.Completed) outcome;
        assertEquals("call_1", completed.proposedToolCalls().get(0).id());
        assertEquals("call_2", completed.proposedToolCalls().get(1).id());
        assertEquals("workspace.read", completed.proposedToolCalls().get(1).name());
        assertTrue(completed.continuation().present(),
            "opaque reasoning must be captured in the adapter-owned envelope");
        assertEquals("openrouter", completed.continuation().providerId());
    }

    @Test
    @DisplayName("A10: usage captured when present; missing cost is unknown, never zero")
    void usageCapture() throws Exception {
        responseBody.set(successBody("stop", true));
        var outcome = provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE, List.of(ChatMessage.user("q")),
            List.of(), 64));
        var completed = (ModelOutcome.Completed) outcome;
        assertEquals(11, completed.usage().promptTokensOpt().orElse(-1));
        assertEquals(22, completed.usage().completionTokensOpt().orElse(-1));
        assertTrue(completed.usage().totalCostMicrosOpt().isEmpty(),
            "no cost in response -> unknown, not zero");
        assertEquals(Optional.of("observed-model"), completed.observedModel());

        responseBody.set(successBody("stop", false));
        var noUsage = (ModelOutcome.Completed) provider.generate(new GenerationRequest(
            new ModelRef("m"), ReasoningPolicy.ProviderDefault.INSTANCE,
            List.of(ChatMessage.user("q")), List.of(), 64));
        assertTrue(noUsage.usage().promptTokensOpt().isEmpty());
    }

    @Test
    @DisplayName("finish_reason=length with empty answer is a typed incomplete, never success")
    void emptyLengthIsIncomplete() throws Exception {
        responseBody.set("{\"id\":\"x\",\"model\":\"m\",\"choices\":[{"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
            + "\"finish_reason\":\"length\"}]}");
        var outcome = provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE, List.of(ChatMessage.user("q")),
            List.of(), 64));
        assertTrue(outcome instanceof ModelOutcome.Failed failed
            && failed.kind() == ModelOutcome.Failed.FailureKind.EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("401 fails without retry; 429 is a typed rate-limit failure")
    void authAndRateLimit() throws Exception {
        status.set(401);
        var outcome = provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE, List.of(ChatMessage.user("q")),
            List.of(), 64));
        assertTrue(outcome instanceof ModelOutcome.Failed failed
            && failed.kind() == ModelOutcome.Failed.FailureKind.AUTH_REJECTED);

        status.set(429);
        var rate = provider.generate(new GenerationRequest(new ModelRef("m"),
            ReasoningPolicy.ProviderDefault.INSTANCE, List.of(ChatMessage.user("q")),
            List.of(), 64));
        assertTrue(rate instanceof ModelOutcome.Failed failed
            && failed.kind() == ModelOutcome.Failed.FailureKind.RATE_LIMITED);
    }
}
