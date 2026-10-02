package rahu.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import rahu.core.CurrencyUnit;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.MoneyAmount;
import rahu.core.ReasoningPolicy;

/**
 * Model-catalog evidence (openrouter.md): one /api/v1/models fetch is written
 * to a versioned evidence file and reused until the TTL expires; the loader
 * maps entries onto ModelProfile. The file carries capability and pricing
 * evidence only — never credentials or prompt content. Age is judged from the
 * recorded fetch time, so an operator-restored copy cannot look fresh.
 */
public final class ModelProfileCatalog {

    /** Evidence schema version; bumped on any incompatible field change. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final String baseUrl;
    private final Path evidenceFile;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public ModelProfileCatalog(String baseUrl, Path evidenceFile) {
        this.baseUrl = baseUrl == null ? "https://openrouter.ai/api/v1" : baseUrl;
        this.evidenceFile = evidenceFile;
    }

    /** Fetches once when no evidence file exists or the TTL has expired. */
    public void refreshIfNeeded(Duration ttl, Instant now) throws IOException,
        InterruptedException {

        if (Files.exists(evidenceFile)) {
            JsonNode existing = readEvidence();
            if (existing != null
                && ageOf(existing, now).compareTo(ttl) < 0) {
                return; // fresh enough; do not spend a request
            }
        }
        fetchAndStore(now);
    }

    /** Returns the profile for a model id, or null when the catalog lacks it. */
    public ModelProfile profile(ModelRef ref) throws IOException {
        JsonNode root = readEvidence();
        if (root == null) {
            return null;
        }
        JsonNode entry = entryFor(root, ref.providerNeutralId());
        return entry == null ? null : toProfile(ref, entry,
            fetchedAtOf(root));
    }

    /**
     * Freshness check with operator stale permission: false when evidence is
     * missing, older than {@code maxStaleAge}, or unparsable. The caller
     * decides whether merely-expired-but-within-max-stale evidence may be used
     * (openrouter.md: live mode refuses stale required evidence by default).
     */
    public boolean profileFresh(ModelRef ref, Duration maxStaleAge, Instant now)
        throws IOException {

        JsonNode root = readEvidence();
        if (root == null || entryFor(root, ref.providerNeutralId()) == null) {
            return false;
        }
        Duration age = ageOf(root, now);
        return age.compareTo(Duration.ZERO) >= 0 && age.compareTo(maxStaleAge) <= 0;
    }

    // ------------------------------------------------------------------ wire

    private void fetchAndStore(Instant now) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<byte[]> response =
            http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("catalog fetch failed: HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("catalog response exceeds " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode root = MAPPER.readTree(body);
        if (root == null || !root.has("data") || !root.get("data").isArray()) {
            throw new IOException("catalog response is not a models document");
        }

        ObjectNode evidence = MAPPER.createObjectNode();
        evidence.put("schemaVersion", SCHEMA_VERSION);
        evidence.put("fetchedAt", now.toString());
        evidence.set("models", root.get("data"));

        Path tmp = evidenceFile.resolveSibling(
            evidenceFile.getFileName() + ".tmp");
        Files.createDirectories(evidenceFile.toAbsolutePath().getParent());
        Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(evidence), StandardCharsets.UTF_8);
        Files.move(tmp, evidenceFile,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------------------------------------------------------------- loader

    private JsonNode readEvidence() throws IOException {
        if (!Files.exists(evidenceFile)) {
            return null;
        }
        byte[] raw = Files.readAllBytes(evidenceFile);
        if (raw.length > MAX_BODY_BYTES) {
            throw new IOException("evidence file exceeds " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode root = MAPPER.readTree(raw);
        if (root == null || !root.has("schemaVersion") || !root.has("fetchedAt")
            || !root.has("models") || !root.get("models").isArray()) {
            throw new IOException("evidence file is not a model-profiles document: "
                + evidenceFile);
        }
        if (root.get("schemaVersion").asInt() != SCHEMA_VERSION) {
            throw new IOException("evidence schema version "
                + root.get("schemaVersion").asInt() + " is not supported (expected "
                + SCHEMA_VERSION + "); delete the file to refetch");
        }
        return root;
    }

    private static JsonNode entryFor(JsonNode root, String id) {
        for (JsonNode entry : root.get("models")) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        return null;
    }

    private static Instant fetchedAtOf(JsonNode root) {
        try {
            return Instant.parse(root.get("fetchedAt").asText());
        } catch (DateTimeParseException e) {
            return Instant.EPOCH; // unparsable age is maximally stale, never fresh
        }
    }

    private static Duration ageOf(JsonNode root, Instant now) {
        return Duration.between(fetchedAtOf(root), now);
    }

    private static ModelProfile toProfile(ModelRef ref, JsonNode entry,
        Instant fetchedAt) {

        Integer context = entry.hasNonNull("context_length")
            ? entry.get("context_length").asInt() : null;
        Integer maxOutput = entry.path("top_provider").hasNonNull("max_completion_tokens")
            ? entry.path("top_provider").get("max_completion_tokens").asInt() : null;

        JsonNode pricing = entry.path("pricing");
        Optional<MoneyAmount> inputPrice = priceOf(pricing, "prompt");
        Optional<MoneyAmount> outputPrice = priceOf(pricing, "completion");

        var supported = new LinkedHashMap<String, Boolean>();
        JsonNode params = entry.path("supported_parameters");
        boolean tools = false;
        var efforts = new java.util.LinkedHashSet<ReasoningPolicy.Effort>();
        if (params.isArray()) {
            for (JsonNode p : params) {
                String name = p.asText();
                supported.put(name, true);
                if ("tools".equals(name) || "tool_choice".equals(name)) {
                    tools = true;
                }
                if ("reasoning".equals(name) || "include_reasoning".equals(name)) {
                    efforts.add(ReasoningPolicy.Effort.LOW);
                    efforts.add(ReasoningPolicy.Effort.MEDIUM);
                    efforts.add(ReasoningPolicy.Effort.HIGH);
                }
            }
        }
        if (efforts.isEmpty()) {
            // No reasoning evidence: default effort remains admissible only.
            efforts.add(ReasoningPolicy.Effort.MEDIUM);
        }

        return new ModelProfile(ref, context, maxOutput,
            inputPrice.orElse(null), outputPrice.orElse(null),
            efforts.toArray(new ReasoningPolicy.Effort[0]),
            /* mandatoryReasoning */ false,
            fetchedAt, /* evidenceFresh */ true, tools);
    }

    private static Optional<MoneyAmount> priceOf(JsonNode pricing, String field) {
        if (!pricing.hasNonNull(field)) {
            return Optional.empty();
        }
        try {
            var amount = new java.math.BigDecimal(pricing.get(field).asText());
            return Optional.of(new MoneyAmount(amount, CurrencyUnit.USD));
        } catch (NumberFormatException e) {
            return Optional.empty(); // unparsable price is unknown, never zero
        }
    }
}
