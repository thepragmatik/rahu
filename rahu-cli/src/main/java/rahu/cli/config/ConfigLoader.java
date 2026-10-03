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
        "session", "orchestration", "privacy", "injection", "search");
    /**
     * The keys each section accepts, checked before binding.
     *
     * <p>AUDIT-2026-10-03-s: only the ROOT object was checked against
     * {@link #TOP_KEYS}. Every {@code bind*} method read the keys it knew and
     * ignored the rest, so a misspelling one level down loaded clean and the
     * operator's file described a system that was never configured. A15 proves the
     * root check works, which is exactly why nobody looked for the second one.
     *
     * <p>Each set is the union of what the binder reads, so adding a key to a
     * {@code bind*} method without adding it here fails
     * {@code ConfigKeyReachabilityTest}, which is the point: the two must not drift.
     */
    private static final Map<String, Set<String>> SECTION_KEYS = Map.ofEntries(
        Map.entry("decision", Set.of("adapter", "baseUrl", "compatibilityProfile",
            "model", "apiKeyEnv", "timeoutMillis", "costMode")),
        Map.entry("generation", Set.of("adapter", "baseUrl", "apiKeyEnv",
            "requireParameters", "allowedProviders")),
        Map.entry("routing", Set.of("mode", "pool", "baseline", "fallback",
            "confidenceField", "confidenceFloor", "maximumCandidates")),
        Map.entry("agent", Set.of("maxGenerationAttempts", "deadlineSeconds",
            "maxCostUsd", "maxCompletionTokens", "maxCompactions")),
        Map.entry("catalog", Set.of("cacheTtlSeconds", "allowStale",
            "maximumStaleSeconds", "offlineFixture")),
        Map.entry("summarisation", Set.of("pool", "baseline", "fallback")),
        Map.entry("tools", Set.of("root", "enabled", "exclusions", "maxCallsPerStep",
            "resultBytes")),
        Map.entry("trace", Set.of("directory", "capture", "onFailure")),
        Map.entry("context", Set.of("instructionFiles", "maxPromptTokens",
            "routerStateBytes")),
        Map.entry("session", Set.of("mode", "maxTurns", "maxCostUsd")),
        Map.entry("orchestration", Set.of("mode")),
        Map.entry("privacy", Set.of("mode", "onUnknown", "inputClassification",
            "sourcePolicyFile")),
        Map.entry("search", Set.of("mode", "maxCandidates")),
        Map.entry("injection", Set.of("mode", "threshold")));

    /** Keys accepted inside one {@code pools.<name>} entry. */
    private static final Set<String> POOL_KEYS = Set.of("models");

    /** Keys accepted inside one {@code pools.<name>.models[]} entry. */
    private static final Set<String> POOL_MODEL_KEYS =
        Set.of("alias", "id", "reasoning", "description");

    private static final Set<String> ENV_FIELDS = Set.of(
        "decision.model", "generation.apiKeyEnv", "decision.apiKeyEnv");
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");
    private static final Pattern MONEY_PATTERN = Pattern.compile("\\d+(\\.\\d{1,4})?");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The top-level keys this loader accepts.
     *
     * <p>Exposed so the published schema can be tested for documenting every key the
     * loader honours; the two drifting apart is how a valid config ends up flagged by
     * editor validation.
     */
    public static Set<String> acceptedTopLevelKeys() {
        return TOP_KEYS;
    }

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

        rejectUnknownSectionKeys(root);

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
        var injection = bindInjection(root.get("injection"));
        var search = bindSearch(root.get("search"));
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
            new RahuConfig.OrchestrationConfig(orchestration), privacy, injection, search);
    }

    /**
     * Refuses a key no binder reads, naming its full path.
     *
     * <p>Depth matters as much as the check: {@code privacy.onUnkown} is reported as
     * {@code privacy.onUnkown}, never as a bare {@code onUnkown}, because an operator
     * holding a forty-line config cannot find a typo they are not shown the location of.
     *
     * <p>Absent sections are skipped rather than demanded: {@code injection} and
     * {@code search} are documented as optional, and an absent block must stay absent.
     */
    private void rejectUnknownSectionKeys(JsonNode root) {
        for (var section : SECTION_KEYS.entrySet()) {
            JsonNode node = root.get(section.getKey());
            if (node == null || node.isNull()) {
                continue;
            }
            if (!node.isObject()) {
                throw new ConfigError(section.getKey() + " must be an object");
            }
            rejectUnknown(node, section.getKey(), section.getValue());
        }
        JsonNode pools = root.get("pools");
        if (pools != null && pools.isObject()) {
            for (var pool : pools.properties()) {
                rejectUnknown(pool.getValue(), "pools." + pool.getKey(), POOL_KEYS);
                JsonNode models = pool.getValue().path("models");
                if (models.isArray()) {
                    for (JsonNode model : models) {
                        if (model.isObject()) {
                            rejectUnknown(model,
                                "pools." + pool.getKey() + "." + model.path("alias").asText(),
                                POOL_MODEL_KEYS);
                        }
                    }
                }
            }
        }
    }

    private static void rejectUnknown(JsonNode node, String path, Set<String> allowed) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ConfigError("unknown config key \"" + path + "." + name
                    + "\"; remove it or fix the spelling (configuration.md field table)");
            }
        }
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

    /**
     * Injection overlay config. The block is optional: an absent block is the
     * documented off|0.10 default, so a config written before G1 still loads.
     */
    private RahuConfig.InjectionConfig bindInjection(JsonNode n) {
        if (n == null) {
            return RahuConfig.InjectionConfig.defaults();
        }
        String m = n.path("mode").asText("off");
        if (!m.equals("off") && !m.equals("shadow") && !m.equals("enforce")) {
            throw new ConfigError(
                "injection.mode must be off|shadow|enforce, got \"" + m + "\"");
        }
        Double threshold = n.has("threshold") ? n.get("threshold").asDouble() : 0.10;
        if (threshold < 0.0 || threshold > 1.0) {
            throw new ConfigError("injection.threshold must be in [0,1]");
        }
        return new RahuConfig.InjectionConfig(m, threshold);
    }

    /**
     * Search rerank config. The block is optional: an absent block is the documented
     * off default, so a config written before this feature still loads unchanged.
     */
    private RahuConfig.SearchConfig bindSearch(JsonNode n) {
        if (n == null) {
            return RahuConfig.SearchConfig.defaults();
        }
        String m = n.path("mode").asText("off");
        if (!m.equals("off") && !m.equals("shadow") && !m.equals("enforce")) {
            throw new ConfigError(
                "search.mode must be off|shadow|enforce, got \"" + m + "\"");
        }
        // AUDIT-2026-10-03-af: 20 was also written literally in ToolLoop; one source now.
        Integer max = n.has("maxCandidates") ? n.get("maxCandidates").asInt()
            : OperationalDefaults.RERANK_CANDIDATES;
        if (max < 1 || max > 1000) {
            throw new ConfigError("search.maxCandidates must be within 1-1000");
        }
        return new RahuConfig.SearchConfig(m, max);
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

    /**
     * Bind {@code tools}, validating the two numeric keys.
     *
     * <p>{@code asInt} performs no validation, so {@code resultBytes: 0} used to load
     * clean and run clean: the cap reached the executor as zero and would truncate
     * every tool result to nothing while still reporting success. The committed
     * schema already declared {@code "minimum": 1}; the loader simply never enforced
     * it, and a schema that cannot stop an operator is documentation. Found by
     * running the packaged binary with {@code resultBytes: 0} and getting exit 0.
     *
     * <p>{@code maxCallsPerStep} gets the same treatment for the same reason: zero
     * or negative would make the tool loop refuse every call while reporting a
     * completed answer.
     */
    private RahuConfig.ToolsConfig bindTools(JsonNode n) {
        if (n == null) {
            throw new ConfigError("tools section required");
        }
        // AUDIT-2026-10-03-af: the magic number now names its own source.
        int resultBytes = n.path("resultBytes")
            .asInt(OperationalDefaults.TOOL_RESULT_BYTES);
        if (resultBytes < 1) {
            throw new ConfigError("tools.resultBytes must be at least 1, got "
                + resultBytes + "; it bounds each tool result, and a smaller value "
                + "would truncate every result to nothing");
        }
        int maxCalls = n.path("maxCallsPerStep").asInt(8);
        if (maxCalls < 1) {
            throw new ConfigError("tools.maxCallsPerStep must be at least 1, got "
                + maxCalls + "; it bounds tool calls per step, so a smaller value "
                + "would refuse every call");
        }
        return new RahuConfig.ToolsConfig(
            n.path("root").asText("."),
            stringList(n.get("enabled")),
            n.has("exclusions") ? stringList(n.get("exclusions")) : List.of(),
            maxCalls,
            resultBytes);
    }

    /**
     * Trace config, with both enum keys refused at load.
     *
     * <p>AUDIT-2026-10-03-h: both keys were accepted verbatim. {@code capture="payload"}
     * (one s) loaded clean, so {@code payloadsEnabled} went false, no replay input was
     * written, and a later {@code replay} honestly reported UNAVAILABLE - while the
     * operator's config said payloads. The failure mode is a typo that silently
     * downgrades an observability guarantee, and it is invisible because the tool that
     * depends on it is honest about being unable to help.
     *
     * <p>{@code onFailure} is accepted only as {@code stop}. The key used to accept
     * {@code warn} and then ignore it, so an operator who asked for a degraded run got
     * a stopped one. {@code warn} is not merely unimplemented: observability.md:32
     * requires that a persistence failure "stops new operations with TRACE_FAILURE",
     * and A16 requires stopping and never inventing completion. A continue-on-failure
     * mode would let a run present an answer whose trace never reached disk, which is
     * the A16 failure this subsystem exists to prevent. It is therefore refused with an
     * explanation rather than accepted and ignored - a key the spec forbids must not be
     * advertised by our own schema.
     */
    private RahuConfig.TraceConfig bindTrace(JsonNode n) {
        if (n == null) {
            throw new ConfigError("trace section required");
        }
        String capture = n.path("capture").asText("metadata");
        if (!capture.equals("metadata") && !capture.equals("payloads")) {
            throw new ConfigError("trace.capture must be metadata|payloads, got \""
                + capture + "\"; a typo would silently disable replay-input capture, "
                + "so `replay` would report UNAVAILABLE for a run the operator "
                + "believed was replayable");
        }
        String onFailure = n.path("onFailure").asText("stop");
        if (!onFailure.equals("stop")) {
            throw new ConfigError("trace.onFailure must be stop, got \"" + onFailure
                + "\"; observability.md requires a trace persistence failure to stop "
                + "new operations (TRACE_FAILURE) and A16 forbids presenting "
                + "completion for a run whose trace is incomplete, so there is no "
                + "continue-on-failure mode to select");
        }
        return new RahuConfig.TraceConfig(
            n.path("directory").asText(".rahu/runs"), capture, onFailure);
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
