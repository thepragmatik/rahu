package rahu.systemone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import rahu.core.decision.DecisionResult;

/**
 * Genuine System One HTTP adapter (systemone.md, jev-compatible-v1). Strict
 * response validation: unknown labels, non-finite numbers, duplicate JSON keys,
 * truncated inputs and malformed envelopes become typed failures — never parser
 * repairs. No retries by default (fallback is cheaper).
 */
public final class SystemOneHttpAdapter implements DecisionEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String endpoint;
    private final String model;
    private final String profile;
    private final int timeoutMillis;
    private final Supplier<Optional<String>> apiKey;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    public SystemOneHttpAdapter(String endpoint, String model, String profile,
        int timeoutMillis, Supplier<Optional<String>> apiKey) {
        this.endpoint = endpoint;
        this.model = model;
        this.profile = profile == null ? "jev-compatible-v1" : profile;
        this.timeoutMillis = timeoutMillis;
        this.apiKey = apiKey == null ? () -> Optional.empty() : apiKey;
        // Single source of truth: PrivacyGate.endpointPolicy decides by PARSED HOST.
        // This class previously carried a second copy of the rule that matched on a
        // string prefix, so http://localhost.evil.example passed here while the core
        // policy rejected it -- a fail-open that survived the core fix precisely
        // because the rule was duplicated. One rule, one owner.
        String policyViolation = rahu.core.privacy.PrivacyGate.endpointPolicy(endpoint);
        if (policyViolation != null) {
            throw new IllegalArgumentException(
                "unencrypted HTTP is permitted only for loopback (systemone.md): "
                    + policyViolation);
        }
    }

    @Override
    public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode stateNode = body.putObject("state");
        stateNode.put("operation", state.operation());
        stateNode.put("request", state.request() == null ? "" : state.request());
        stateNode.put("contextPressure", state.contextPressure());
        ObjectNode questionsNode = body.putObject("questions");
        for (Question q : questions) {
            ObjectNode qn = questionsNode.putObject(DecisionEngine.questionId(q));
            if (q instanceof ChoiceQuestion c) {
                qn.put("type", "choice");
                qn.put("instructions", "Choose one permitted option using the supplied criteria.");
                ObjectNode criteria = qn.putObject("criteria");
                for (Map.Entry<String, String> e : c.criteria().entrySet()) {
                    criteria.put(e.getKey(), e.getValue());
                }
            } else if (q instanceof NoulQuestion n) {
                qn.put("type", "noul");
                qn.put("instructions", "Answer yes or no.");
            } else if (q instanceof ScoreQuestion s) {
                // The legend travels with the question: without it the model has no
                // scale to place a level on, and the level would not be comparable.
                qn.put("type", "score");
                qn.put("instructions",
                    "Rate how well the candidate satisfies the request using the legend, "
                        + "most irrelevant first.");
                var legend = qn.putArray("legend");
                s.legend().forEach(legend::add);
            }
        }

        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            Optional<String> key = apiKey.get();
            if (key.isPresent()) {
                builder.header("Authorization", "Bearer " + key.get());
            }
            request = builder.build();
        } catch (Exception e) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.UNKNOWN,
                    "invalid decision endpoint"));
        }

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.CANCELLED,
                    "decision call interrupted"));
        } catch (IOException | IllegalArgumentException e) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.TIMEOUT,
                    "decision transport failed"));
        }

        if (response.statusCode() != 200) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "decision service returned status " + response.statusCode()));
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "decision response exceeds 1 MiB bound"));
        }

        return parseAnswers(response.body(), questions);
    }

    /** One typed failure per question, keyed by id (batch order preserved). */
    private static Map<String, DecisionResult> failuresFor(List<Question> questions,
        DecisionResult.Failure failure) {
        Map<String, DecisionResult> results = new LinkedHashMap<>();
        for (Question q : questions) {
            results.put(DecisionEngine.questionId(q), failure);
        }
        return results;
    }

    /**
     * Parses the answers map into one result per question. A question without a
     * usable answer degrades alone to a typed Failure; its siblings are unaffected.
     */
    private Map<String, DecisionResult> parseAnswers(byte[] payload, List<Question> questions) {
        JsonNode root;
        try {
            var parser = MAPPER.getFactory().createParser(payload);
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature
                .STRICT_DUPLICATE_DETECTION);
            root = MAPPER.readTree(parser);
            parser.close();
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase().contains("duplicate")) {
                return failuresFor(questions,
                    new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                        "duplicate JSON key in decision response"));
            }
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.MALFORMED,
                    "decision response is not valid JSON"));
        }

        if (root == null) {
            // readTree() returns null for a zero-byte body and throws nothing, so the
            // catch above never runs and root.has(..) below would raise a raw
            // NullPointerException out of askAll -- losing the whole batch instead of
            // the one malformed question. An empty body is a protocol fault, not a
            // parse error, which is why it needs its own arm.
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.MALFORMED,
                    "decision response body was empty"));
        }

        if (root.has("truncated") && root.get("truncated").asBoolean(false)) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "decision input was truncated server-side; rejected under default profile"));
        }

        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            return failuresFor(questions,
                new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "decision response has no answers map"));
        }

        Map<String, DecisionResult> results = new LinkedHashMap<>();
        for (Question q : questions) {
            String id = DecisionEngine.questionId(q);
            JsonNode answer = answers.get(id);
            if (answer == null || answer.isNull()) {
                results.put(id, new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "missing answer for question " + id));
                continue;
            }
            if (q instanceof ChoiceQuestion c) {
                results.put(id, parseChoice(id, answer, c));
            }
            if (q instanceof NoulQuestion) {
                JsonNode noul = answer.path("noul");
                if (!noul.isNumber() || !noul.isDouble() && !noul.isInt()
                    || noul.asDouble() < 0.0 || noul.asDouble() > 1.0) {
                    results.put(id, new DecisionResult.Failure(
                        DecisionResult.FailureKind.PROTOCOL_ERROR,
                        "noul answer for " + id + " is not a finite probability in [0,1]"));
                    continue;
                }
                results.put(id, new DecisionResult.ValidNoul(id, noul.asDouble() >= 0.5,
                    Optional.of(noul.asDouble())));
            }
            if (q instanceof ScoreQuestion s) {
                results.put(id, parseScore(id, answer, s));
            }
        }
        return results;
    }

    /**
     * Parses a score answer into a typed ValidScore. Every rejection here is a silent
     * quality trap if it were accepted: a level past the end of the legend sorts FIRST
     * and looks like the strongest possible match, so an out-of-range or non-integral
     * level degrades to a typed Failure instead. The legend echoed back must be the one
     * the question carried — a server that answered against a different scale would
     * make levels incomparable across candidates, which is the whole premise of a rerank.
     */
    private DecisionResult parseScore(String id, JsonNode answer, ScoreQuestion question) {
        String type = answer.path("type").asText("");
        if (!"score".equals(type)) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "answer type for " + id + " is not score");
        }
        JsonNode level = answer.path("level");
        if (!level.isIntegralNumber()) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "score level for " + id + " is not an integer");
        }
        int value = level.asInt();
        if (value < 0 || value >= question.legend().size()) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "score level " + value + " for " + id + " is outside the legend");
        }
        JsonNode echoed = answer.path("legend");
        if (echoed.isArray() && echoed.size() != question.legend().size()) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "score legend for " + id + " does not match the question legend");
        }
        return new DecisionResult.ValidScore(id, value, question.legend());
    }

    private DecisionResult parseChoice(String id, JsonNode answer, ChoiceQuestion c) {
        String type = answer.path("type").asText("");
        if (!"choice".equals(type)) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "answer type for " + id + " is not choice");
        }
        String chosen = answer.path("choice").asText("");
        if (!c.criteria().containsKey(chosen)) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "unknown choice label \"" + chosen + "\" for " + id);
        }
        JsonNode probs = answer.path("probabilities");
        if (!probs.isObject() || probs.isEmpty()) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "missing probability distribution for " + id);
        }
        Map<String, Double> distribution = new LinkedHashMap<>();
        double sum = 0.0;
        Iterator<Map.Entry<String, JsonNode>> fields = probs.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode v = e.getValue();
            if (!v.isNumber() || Double.isNaN(v.doubleValue())
                || v.doubleValue() < 0.0 || v.doubleValue() > 1.0) {
                return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                    "probability for " + e.getKey() + " is not finite in [0,1]");
            }
            distribution.put(e.getKey(), v.doubleValue());
            sum += v.doubleValue();
        }
        if (Math.abs(sum - 1.0) > 0.0001) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "probabilities for " + id + " sum to " + sum + ", not 1 within 0.0001");
        }
        if (!distribution.keySet().equals(c.criteria().keySet())) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "probability labels for " + id + " do not cover exactly the submitted criteria");
        }
        Optional<Double> rawConfidence = Optional.empty();
        JsonNode conf = answer.path("confidence");
        if (conf.isNumber() && !Double.isNaN(conf.asDouble())) {
            rawConfidence = Optional.of(conf.asDouble());
        }
        return new DecisionResult.ValidChoice(id, chosen, distribution, rawConfidence,
            "concentration");
    }
}
