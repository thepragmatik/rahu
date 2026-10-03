package rahu.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import rahu.core.ReasoningPolicy;
import rahu.core.model.ChatMessage;
import rahu.core.model.ContinuationEnvelope;
import rahu.core.model.GenerationRequest;
import rahu.core.model.ModelOutcome;
import rahu.core.model.ToolCall;
import rahu.core.model.ToolDescriptor;
import rahu.core.model.Usage;

/**
 * OpenRouter generation adapter (openrouter.md). Non-streaming; provider DTOs
 * stay in this module. No internal retries; typed outcomes for every failure
 * class; opaque reasoning captured in a bounded adapter-owned envelope.
 */
public final class OpenRouterProvider implements rahu.core.model.ModelProvider {

    /** Largest micro amount the ledger can hold without overflowing a long. */
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads provider responses with JSON floats kept as exact decimals.
     *
     * <p>Jackson binds an untyped JSON float to {@code double} by default, so the
     * digits the provider sent are already rounded to binary precision before any
     * cost arithmetic happens: {@code 9223372036854.775807} arrives as
     * {@code 9223372036854.775}. Reading it back with {@code decimalValue()} then
     * yields a decimal that is exactly as inexact as the double it came from, which
     * makes "convert through BigDecimal" cosmetic. This mapper keeps the literal.
     *
     * <p>Scoped to the response read only. The request mapper is untouched: nothing
     * we send needs decimal fidelity, and changing it would alter unrelated
     * serialization.
     */
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final String baseUrl;
    private final Supplier<Optional<String>> apiKey;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public OpenRouterProvider(String baseUrl, Supplier<Optional<String>> apiKey) {
        this.baseUrl = baseUrl == null ? "https://openrouter.ai/api/v1" : baseUrl;
        this.apiKey = apiKey == null ? () -> Optional.empty() : apiKey;
    }

    @Override
    public ModelOutcome generate(GenerationRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().providerNeutralId());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", wireRole(m.role()));
            msg.put("content", m.content());
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = msg.putArray("tool_calls");
                for (ToolCall call : m.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id());
                    c.put("type", "function");
                    c.putObject("function")
                        .put("name", call.name())
                        .put("arguments", call.argumentsJson());
                }
            }
        }
        body.put("max_tokens", request.maxCompletionTokens());
        body.putObject("provider").put("require_parameters", true);
        // Ask for the billed cost so the ledger can settle exactly (A10: an
        // absent cost stays unknown — never a fabricated zero).
        body.putObject("usage").put("include", true);

        if (request.reasoningPolicy() instanceof ReasoningPolicy.ExplicitEffort e) {
            body.putObject("reasoning").put("effort", wireEffort(e.effort()));
        } else if (request.reasoningPolicy() instanceof ReasoningPolicy.Disabled) {
            body.putObject("reasoning").put("enabled", false);
        }
        // ProviderDefault: omit reasoning controls entirely.

        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDescriptor t : request.tools()) {
                ObjectNode fn = tools.addObject()
                    .put("type", "function")
                    .putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                try {
                    fn.set("parameters", MAPPER.readTree(t.jsonSchema()));
                } catch (IOException io) {
                    return new ModelOutcome.Failed(
                        ModelOutcome.Failed.FailureKind.INVALID_REQUEST,
                        "tool schema is not valid JSON: " + t.name(), null);
                }
            }
        }

        String payload = body.toString();
        HttpRequest httpRequest;
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            Optional<String> key = apiKey.get();
            if (key.isPresent()) {
                builder.header("Authorization", "Bearer " + key.get());
            }
            httpRequest = builder.build();
        } catch (Exception e) {
            return failed(ModelOutcome.Failed.FailureKind.INVALID_REQUEST,
                "invalid generation endpoint", null);
        }

        HttpResponse<byte[]> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(ModelOutcome.Failed.FailureKind.UNKNOWN, "interrupted", null);
        } catch (IOException | IllegalArgumentException e) {
            return failed(ModelOutcome.Failed.FailureKind.NETWORK,
                "generation transport failed", null);
        }

        return parseResponse(request, response);
    }

    private ModelOutcome parseResponse(GenerationRequest request,
        HttpResponse<byte[]> response) {

        int code = response.statusCode();
        if (code == 401 || code == 403) {
            return failed(ModelOutcome.Failed.FailureKind.AUTH_REJECTED,
                "generation auth rejected (" + code + ")", null);
        }
        if (code == 429) {
            return failed(ModelOutcome.Failed.FailureKind.RATE_LIMITED,
                "generation rate limited", null);
        }
        if (code >= 500) {
            return failed(ModelOutcome.Failed.FailureKind.SERVER_ERROR,
                "generation server error (" + code + ")", null);
        }
        if (code != 200) {
            return failed(ModelOutcome.Failed.FailureKind.INVALID_REQUEST,
                "unexpected status " + code, null);
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            return failed(ModelOutcome.Failed.FailureKind.MALFORMED_RESPONSE,
                "response exceeds size bound", null);
        }

        JsonNode root;
        try {
            root = RESPONSE_MAPPER.readTree(response.body());
        } catch (IOException e) {
            return failed(ModelOutcome.Failed.FailureKind.MALFORMED_RESPONSE,
                "response is not valid JSON", null);
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            return failed(ModelOutcome.Failed.FailureKind.MALFORMED_RESPONSE,
                "response has no choices", null);
        }
        String finishReason = choice.path("finish_reason").asText("unknown");
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");

        Usage usage = parseUsage(root.path("usage"));
        ContinuationEnvelope continuation = ContinuationEnvelope.empty("openrouter");
        JsonNode reasoning = message.path("reasoning");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            continuation = new ContinuationEnvelope("openrouter",
                reasoning.asText().getBytes(StandardCharsets.UTF_8), true);
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode callNode : message.path("tool_calls")) {
            calls.add(new ToolCall(
                callNode.path("id").asText(),
                callNode.path("function").path("name").asText(),
                callNode.path("function").path("arguments").asText("{}")));
        }

        if ("length".equals(finishReason) && content.isEmpty()) {
            return new ModelOutcome.Failed(ModelOutcome.Failed.FailureKind.EMPTY_RESPONSE,
                "finish_reason=length with empty answer (incomplete)", usage);
        }

        return new ModelOutcome.Completed(content, calls, usage, finishReason,
            Optional.ofNullable(root.path("model").asText(null)),
            Optional.ofNullable(root.path("provider").asText(null)),
            continuation);
    }

    private static Usage parseUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return new Usage(null, null, null, null);
        }
        return new Usage(
            usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
            usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
            usage.hasNonNull("completion_tokens_details")
                && usage.get("completion_tokens_details").hasNonNull("reasoning_tokens")
                ? usage.get("completion_tokens_details").get("reasoning_tokens").asInt() : null,
            parseCostMicros(usage));
    }

    /**
     * OpenRouter reports cost in dollars; the core ledger holds exact micros.
     *
     * <p>The conversion is exact decimal. Reading the JSON as {@code double} and
     * multiplying by 1e6 makes the rounding a property of binary floating point
     * rather than of the number the provider sent.
     *
     * <p>A cost that is POSITIVE but rounds to zero micros is returned as {@code
     * null} (unknown), not {@code 0}. The difference is load-bearing: a caller that
     * sees a present zero settles a confident $0.00, asserting the provider billed
     * nothing, when it did bill something we simply cannot hold at micro
     * resolution. Unknown routes the reservation to the ledger's uncertain
     * liability, which is the honest state. An exact reported {@code 0.0} is a
     * real observation of free and does settle as zero.
     */
    private static Long parseCostMicros(JsonNode usage) {
        JsonNode cost = usage.path("cost");
        if (!cost.isNumber()) {
            return null;
        }
        BigDecimal dollars;
        try {
            dollars = cost.decimalValue();
        } catch (NumberFormatException e) {
            // NaN and Infinity are numeric nodes but have no decimal value.
            return null;
        }
        if (dollars.signum() < 0) {
            return null;
        }
        BigDecimal micros = dollars.movePointRight(6).setScale(0, RoundingMode.HALF_UP);
        if (micros.signum() == 0 && dollars.signum() > 0) {
            // Billed, positive, and below half a micro: unrepresentable, not free.
            return null;
        }
        if (micros.compareTo(LONG_MAX) > 0) {
            // Beyond anything a ledger can hold; refuse rather than wrap.
            return null;
        }
        return micros.longValueExact();
    }

    private static ModelOutcome.Failed failed(ModelOutcome.Failed.FailureKind kind,
        String reason, Usage usage) {
        return new ModelOutcome.Failed(kind, reason, usage);
    }

    private static String wireRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private static String wireEffort(ReasoningPolicy.Effort effort) {
        return switch (effort) {
            case NONE -> throw new IllegalArgumentException(
                "NONE maps to Disabled, not an effort value");
            case MINIMAL -> "minimal";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case XHIGH -> "xhigh";
            case MAX -> "max";
        };
    }
}
