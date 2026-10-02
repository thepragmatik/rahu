package rahu.cli.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Typed, validated configuration v1 (configuration.md field table). All numeric
 * limits are validated positive/nonnegative at parse time; money is exact
 * decimal. Immutable once loaded; session lifetime binds it.
 */
public record RahuConfig(
    int schemaVersion,
    String mode,
    DecisionConfig decision,
    GenerationConfig generation,
    RoutingConfig routing,
    Map<String, List<PoolEntry>> pools,
    SummarisationConfig summarisation,
    AgentConfig agent,
    CatalogConfig catalog,
    ToolsConfig tools,
    TraceConfig trace,
    ContextConfig context,
    SessionConfig session,
    OrchestrationConfig orchestration,
    PrivacyConfig privacy) {

    public record OrchestrationConfig(String mode) {
    }

    public record DecisionConfig(String adapter, String baseUrl, String compatibilityProfile,
        String model, String apiKeyEnv, Integer timeoutMillis, String costMode) {
    }

    public record GenerationConfig(String adapter, String baseUrl, String apiKeyEnv,
        Boolean requireParameters, List<String> allowedProviders) {
    }

    public record RoutingConfig(String mode, String pool, String baseline, String fallback,
        String confidenceField, Double confidenceFloor, Integer maximumCandidates) {
    }

    public record PoolEntry(String alias, String id, List<String> reasoning,
        String description) {
    }

    public record SummarisationConfig(String pool, String baseline, String fallback) {
    }

    public record AgentConfig(Integer maxGenerationAttempts, Integer deadlineSeconds,
        BigDecimal maxCostUsd, Integer maxCompletionTokens, Integer maxCompactions) {
    }

    public record CatalogConfig(Integer cacheTtlSeconds, Boolean allowStale,
        Integer maximumStaleSeconds, String offlineFixture) {

        /** The documented defaults, also used when a config omits the block. */
        public static CatalogConfig defaults() {
            return new CatalogConfig(86400, false, 172800, null);
        }

        /** Seconds before cached evidence is refetched (a REFETCH question). */
        public int ttlSeconds() {
            return cacheTtlSeconds == null ? 86400 : cacheTtlSeconds;
        }

        /** Seconds after which evidence stops buying paid admission (an ADMISSION question). */
        public int staleSeconds() {
            return maximumStaleSeconds == null ? 172800 : maximumStaleSeconds;
        }

        public boolean staleAllowed() {
            return Boolean.TRUE.equals(allowStale);
        }
    }

    public record ToolsConfig(String root, List<String> enabled, List<String> exclusions,
        Integer maxCallsPerStep, Integer resultBytes) {
    }

    public record TraceConfig(String directory, String capture, String onFailure) {
    }

    public record ContextConfig(List<String> instructionFiles, Integer maxPromptTokens,
        Integer routerStateBytes) {
    }

    public record SessionConfig(String mode, Integer maxTurns, BigDecimal maxCostUsd) {
    }

    public record PrivacyConfig(String mode, String onUnknown, String inputClassification,
        String sourcePolicyFile) {
    }
}
