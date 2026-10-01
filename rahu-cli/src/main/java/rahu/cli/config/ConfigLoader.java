package rahu.cli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict JSON v1 configuration loader (configuration.md): duplicate/unknown key
 * rejection, exact-decimal money, env interpolation only in documented fields,
 * cross-field validation before any I/O or paid call.
 */
public final class ConfigLoader {

    private static final Set<String> TOP_KEYS = Set.of(
        "schemaVersion", "mode", "decision", "generation", "routing", "pools",
        "summarisation", "agent", "catalog", "tools", "trace", "context",
        "session", "orchestration", "privacy");
    private static final Set<String> ENV_FIELDS = Set.of(
        "decision.model", "generation.apiKeyEnv", "decision.apiKeyEnv");
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");
    private static final Pattern MONEY_PATTERN = Pattern.compile("\\d+(\\.\\d{1,4})?");

    private final ObjectMapper mapper = new ObjectMapper();

    public RahuConfig load(Path file) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigError("config read failed: " + file + "; check the path");
        }
        JsonNode root = parseStrict(raw);
        return bind(root);
    }

    /** Parses with duplicate-key rejection (configuration.md); unknown keys fail in bind. */
    private JsonNode parseStrict(String raw) {
        try {
            var factory = mapper.getFactory();
            factory.disable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var parser = factory.createParser(raw);
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = mapper.readTree(parser);
            parser.close();
            return tree;
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase().contains("duplicate")) {
                throw new ConfigError("duplicate JSON key found; remove the repeated field ("
                    + msg.substring(0, Math.min(120, msg.length())) + ")");
            }
            throw new ConfigError("invalid JSON: "
                + msg.substring(0, Math.min(160, msg.length())) + "; fix the syntax");
        }
    }

    private RahuConfig bind(JsonNode root) {
        List<String> unknown = new ArrayList<>();
        var fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (!TOP_KEYS.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigError("unknown config key \"" + unknown.get(0)
                + "\"; remove it or fix the spelling (configuration.md field table)");
        }

        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != 1) {
            throw new ConfigError("schemaVersion must be 1, got " + schemaVersion
                + "; update the config or the loader");
        }
        String mode = requireString(root, "mode");
        if (!mode.equals("offline") && !mode.equals("live")) {
            throw new ConfigError("mode must be offline|live, got \"" + mode + "\"");
        }

        var decision = bindDecision(root.get("decision"));
        var generation = bindGeneration(root.get("generation"));
        var routing = bindRouting(root.get("routing"));
        Map<String, List<RahuConfig.PoolEntry>> pools = bindPools(root.get("pools"));
        var summarisation = bindSummarisation(root.get("summarisation"), routing);
        var agent = bindAgent(root.get("agent"));
        var catalog = bindCatalog(root.get("catalog"));
        var tools = bindTools(root.get("tools"));
        var trace = bindTrace(root.get("trace"));
        var context = bindContext(root.get("context"));
        var session = bindSession(root.get("session"));
        String orchestration = root.has("orchestration")
            ? root.get("orchestration").path("mode").asText("single")
            : "single";
        if (!orchestration.equals("single")) {
            throw new ConfigError("orchestration.mode supports only \"single\" in alpha, got \""
                + orchestration + "\"; remove the field or set single (orchestration.md)");
        }
        var privacy = bindPrivacy(root.get("privacy"));

        validateReference(routing.pool(), routing.baseline(), pools, "routing.baseline");
        validateReference(routing.pool(), routing.fallback(), pools, "routing.fallback");
        if (summarisation != null) {
            validateReference(summarisation.pool(), summarisation.baseline(), pools,
                "summarisation.baseline");
            validateReference(summarisation.pool(), summarisation.fallback(), pools,
                "summarisation.fallback");
            if (!pools.containsKey(summarisation.pool())) {
                throw new ConfigError("summarisation.pool \"" + summarisation.pool()
                    + "\" does not exist; define it under pools");
            }
        }

        return new RahuConfig(schemaVersion, mode, decision, generation, routing, pools,
            summarisation, agent, catalog, tools, trace, context, session,
            new RahuConfig.OrchestrationConfig(orchestration), privacy);
    }

    private RahuConfig.DecisionConfig bindDecision(JsonNode n) {
        if (n == null) {
            throw new ConfigError("decision section required");
        }
        return new RahuConfig.DecisionConfig(
            n.path("adapter").asText(),
            optString(n, "baseUrl"),
            optString(n, "compatibilityProfile"),
            resolveEnv(optString(n, "model"), "decision.model"),
            optString(n, "apiKeyEnv"),
            optInt(n, "timeoutMillis"),
            optString(n, "costMode"));
    }

    private RahuConfig.GenerationConfig bindGeneration(JsonNode n) {
        if (n == null) {
            throw new ConfigError("generation section required");
        }
        return new RahuConfig.GenerationConfig(
            n.path("adapter").asText(),
            optString(n, "baseUrl"),
            resolveEnv(optString(n, "apiKeyEnv"), "generation.apiKeyEnv"),
            n.has("requireParameters") ? n.get("requireParameters").asBoolean() : null,
            n.has("allowedProviders") ? stringList(n.get("allowedProviders")) : null);
    }

    private RahuConfig.RoutingConfig bindRouting(JsonNode n) {
        if (n == null) {
            throw new ConfigError("routing section required");
        }
        String m = n.path("mode").asText("shadow");
        if (!m.equals("shadow") && !m.equals("active")) {
            throw new ConfigError("routing.mode must be shadow|active, got \"" + m + "\"");
        }
        Double floor = n.has("confidenceFloor") ? n.get("confidenceFloor").asDouble() : 0.65;
        if (floor < 0.0 || floor > 1.0) {
            throw new ConfigError("routing.confidenceFloor must be in [0,1]");
        }
        return new RahuConfig.RoutingConfig(m,
            requireString(n, "pool"),
            requireString(n, "baseline"),
            requireString(n, "fallback"),
            n.path("confidenceField").asText("chosen_probability"),
            floor,
            n.has("maximumCandidates") ? n.get("maximumCandidates").asInt() : 32);
    }

    private Map<String, List<RahuConfig.PoolEntry>> bindPools(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new ConfigError("pools section required");
        }
        Map<String, List<RahuConfig.PoolEntry>> out = new LinkedHashMap<>();
        var fieldNames = n.fieldNames();
        while (fieldNames.hasNext()) {
            String poolName = fieldNames.next();
            JsonNode models = n.get(poolName).get("models");
            if (models == null || !models.isArray() || models.isEmpty()) {
                throw new ConfigError("pools." + poolName + ".models must be a nonempty array");
            }
            List<RahuConfig.PoolEntry> entries = new ArrayList<>();
            Set<String> aliases = new HashSet<>();
            for (JsonNode m : models) {
                String alias = m.path("alias").asText();
                if (!aliases.add(alias)) {
                    throw new ConfigError("pools." + poolName
                        + ": duplicate alias \"" + alias + "\"; aliases must be unique");
                }
                List<String> reasoning = stringList(m.get("reasoning"));
                if (reasoning.isEmpty()) {
                    throw new ConfigError("pools." + poolName + "." + alias
                        + ".reasoning cannot be an empty list (configuration.md)");
                }
                for (String r : reasoning) {
                    if (!List.of("none", "minimal", "low", "medium", "high", "xhigh",
                        "max", "default").contains(r)) {
                        throw new ConfigError("pools." + poolName + "." + alias
                            + ".reasoning contains unknown effort \"" + r
                            + "\"; use none|minimal|low|medium|high|xhigh|max|default");
                    }
                }
                entries.add(new RahuConfig.PoolEntry(alias,
                    resolveEnv(m.path("id").asText(), "pools." + poolName + "." + alias + ".id"),
                    reasoning, optString(m, "description")));
            }
            out.put(poolName, entries);
        }
        return out;
    }

    private RahuConfig.SummarisationConfig bindSummarisation(JsonNode n,
        RahuConfig.RoutingConfig routing) {
        if (n == null || n.isNull()) {
            return null;
        }
        return new RahuConfig.SummarisationConfig(
            n.path("pool").asText(routing.pool()),
            n.path("baseline").asText(routing.baseline()),
            n.path("fallback").asText(routing.fallback()));
    }

    private RahuConfig.AgentConfig bindAgent(JsonNode n) {
        if (n == null || n.isNull()) {
            return new RahuConfig.AgentConfig(8, 180, new BigDecimal("1.00"), 4096, 2);
        }
        return new RahuConfig.AgentConfig(
            n.path("maxGenerationAttempts").asInt(8),
            n.path("deadlineSeconds").asInt(180),
            money(n, "maxCostUsd", "1.00"),
            n.path("maxCompletionTokens").asInt(4096),
            n.path("maxCompactions").asInt(2));
    }

    private RahuConfig.CatalogConfig bindCatalog(JsonNode n) {
        if (n == null || n.isNull()) {
            return new RahuConfig.CatalogConfig(86400, false, 172800, null);
        }
        return new RahuConfig.CatalogConfig(
            n.path("cacheTtlSeconds").asInt(86400),
            n.path("allowStale").asBoolean(false),
            n.path("maximumStaleSeconds").asInt(172800),
            optString(n, "offlineFixture"));
    }

    private RahuConfig.ToolsConfig bindTools(JsonNode n) {
        if (n == null) {
            throw new ConfigError("tools section required");
        }
        return new RahuConfig.ToolsConfig(
            n.path("root").asText("."),
            stringList(n.get("enabled")),
            n.has("exclusions") ? stringList(n.get("exclusions")) : List.of(),
            n.path("maxCallsPerStep").asInt(8),
            n.path("resultBytes").asInt(65536));
    }

    private RahuConfig.TraceConfig bindTrace(JsonNode n) {
        if (n == null) {
            throw new ConfigError("trace section required");
        }
        return new RahuConfig.TraceConfig(
            n.path("directory").asText(".rahu/runs"),
            n.path("capture").asText("metadata"),
            n.path("onFailure").asText("stop"));
    }

    private RahuConfig.ContextConfig bindContext(JsonNode n) {
        if (n == null || n.isNull()) {
            return new RahuConfig.ContextConfig(List.of(), null, 16384);
        }
        return new RahuConfig.ContextConfig(
            n.has("instructionFiles") ? stringList(n.get("instructionFiles")) : List.of(),
            n.has("maxPromptTokens") ? n.get("maxPromptTokens").asInt() : null,
            n.path("routerStateBytes").asInt(16384));
    }

    private RahuConfig.SessionConfig bindSession(JsonNode n) {
        if (n == null) {
            throw new ConfigError("session section required");
        }
        return new RahuConfig.SessionConfig(
            n.path("mode").asText("in-process"),
            n.path("maxTurns").asInt(20),
            money(n, "maxCostUsd", "3.00"));
    }

    private RahuConfig.PrivacyConfig bindPrivacy(JsonNode n) {
        if (n == null) {
            throw new ConfigError("privacy section required");
        }
        String mode = n.path("mode").asText("strict");
        String onUnknown = n.path("onUnknown").asText("block");
        String classification = n.path("inputClassification").asText("unknown");
        if (!mode.equals("strict")) {
            throw new ConfigError("privacy.mode supports only \"strict\" in alpha, got \""
                + mode + "\" (privacy.md)");
        }
        if (!onUnknown.equals("block")) {
            throw new ConfigError("privacy.onUnknown supports only \"block\" in alpha, got \""
                + onUnknown + "\" (privacy.md)");
        }
        if (!classification.equals("unknown") && !classification.equals("approved-nonsensitive")) {
            throw new ConfigError("privacy.inputClassification must be unknown|approved-nonsensitive");
        }
        return new RahuConfig.PrivacyConfig(mode, onUnknown, classification,
            optString(n, "sourcePolicyFile"));
    }

    private void validateReference(String poolName, String ref,
        Map<String, List<RahuConfig.PoolEntry>> pools, String fieldPath) {
        List<RahuConfig.PoolEntry> pool = pools.get(poolName);
        if (pool == null) {
            throw new ConfigError(fieldPath + ": pool \"" + poolName
                + "\" does not exist; define it under pools");
        }
        String alias = ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
        boolean found = pool.stream().anyMatch(e -> e.alias().equals(alias));
        if (!found) {
            throw new ConfigError(fieldPath + " \"" + ref + "\" not found in pool \""
                + poolName + "\"; add the alias or fix the reference");
        }
    }

    private String requireString(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new ConfigError("field \"" + field + "\" is required; set a value");
        }
        return v.asText();
    }

    private int requireInt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.canConvertToInt()) {
            throw new ConfigError("field \"" + field + "\" must be an integer");
        }
        return v.asInt();
    }

    private Integer optInt(JsonNode n, String field) {
        return n.has(field) ? n.get(field).asInt() : null;
    }

    private String optString(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    /** configuration.md: nonnegative decimal string, no exponent; parsed exactly. */
    private BigDecimal money(JsonNode n, String field, String fallback) {
        String raw = n.path(field).asText(fallback);
        Matcher m = MONEY_PATTERN.matcher(raw);
        if (!m.matches()) {
            throw new ConfigError(field + " \"" + raw
                + "\" must be a nonnegative decimal string without exponent (e.g. \"1.00\")");
        }
        return new BigDecimal(raw);
    }

    /** configuration.md: ${ENV} only in documented fields; missing env is an error. */
    private String resolveEnv(String value, String fieldPath) {
        if (value == null) {
            return null;
        }
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!ENV_FIELDS.contains(fieldPath) && !fieldPath.startsWith("pools.")) {
                throw new ConfigError("${ENV} interpolation is not allowed in \"" + fieldPath
                    + "\"; only model IDs, decision.model and credential references");
            }
            String resolved = DotEnv.get(name).orElse(null);
            if (resolved == null) {
                throw new ConfigError("environment variable " + name
                    + " referenced by " + fieldPath
                    + " is not set; export it or add it to .env (gitignored)");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(out);
        return out.toString();
    }
}
