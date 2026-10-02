package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.cli.config.DotEnv;
import rahu.cli.config.RahuConfig;
import rahu.openrouter.OpenRouterProvider;
import rahu.systemone.SystemOneHttpAdapter;

/**
 * Live wiring selection (extensibility.md): the config names the adapters, and
 * credentials resolve through DotEnv only — never from a config literal.
 */
class LiveWiringTest {

    @TempDir
    Path tmp;

    private static RahuConfig config(String decisionAdapter, String generationAdapter) {
        return new RahuConfig(
            1, "live",
            new RahuConfig.DecisionConfig(decisionAdapter,
                "https://openrouter.ai/api/alpha/decisions", "jev-compatible-v1",
                "typesafe/jev-1.13", "OPENROUTER_API_KEY", 8000, "configured-tariff"),
            new RahuConfig.GenerationConfig(generationAdapter,
                "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", true, List.of()),
            new RahuConfig.RoutingConfig("shadow", "cheap", "nemo@default", "qwen@default",
                "chosen_probability", 0.65, 32),
            Map.of("cheap", List.of(new RahuConfig.PoolEntry("nemo",
                "mistralai/mistral-nemo", List.of("default"), "cheap"))),
            new RahuConfig.SummarisationConfig("cheap", "nemo@default", "qwen@default"),
            new RahuConfig.AgentConfig(8, 180, new BigDecimal("0.10"), 2048, 2),
            new RahuConfig.CatalogConfig(86400, false, null, null),
            new RahuConfig.ToolsConfig(".", List.of(), List.of(), 8, 65536),
            new RahuConfig.TraceConfig(".rahu/runs", "metadata", "stop"),
            new RahuConfig.ContextConfig(List.of(), 8192, 4096),
            new RahuConfig.SessionConfig("in-process", 20, new BigDecimal("0.50")),
            new RahuConfig.OrchestrationConfig("single"),
            new RahuConfig.PrivacyConfig("strict", "block", "unknown", null),
            RahuConfig.InjectionConfig.defaults());
    }

    @Test
    @DisplayName("OpenRouter decisions adapter builds the Jev-capable decision engine")
    void decisionAdapterSelected() {
        assertInstanceOf(SystemOneHttpAdapter.class,
            LiveWiring.decision(config("openrouter-decisions", "openrouter")));
    }

    @Test
    @DisplayName("Unknown adapter names are rejected, never silently defaulted")
    void unknownAdaptersRejected() {
        var cfg = config("nope", "openrouter");
        assertThrows(IllegalArgumentException.class, () -> LiveWiring.decision(cfg));

        var badGeneration = config("openrouter-decisions", "nope");
        assertThrows(IllegalArgumentException.class, () -> LiveWiring.generation(badGeneration));
    }

    @Test
    @DisplayName("Generation adapter builds the OpenRouter provider")
    void generationAdapterSelected() {
        assertInstanceOf(OpenRouterProvider.class,
            LiveWiring.generation(config("openrouter-decisions", "openrouter")));
    }

    @Test
    @DisplayName("Credentials resolve through .env; a blank env name yields no key")
    void credentialResolution() throws Exception {
        Files.writeString(tmp.resolve(".env"), "OPENROUTER_API_KEY=fixture-not-a-credential\n");
        DotEnv.load(tmp);

        assertTrue(LiveWiring.keySupplier("OPENROUTER_API_KEY").get().isPresent());
        assertTrue(LiveWiring.keySupplier(null).get().isEmpty());
        assertEquals("fixture-not-a-credential",
            LiveWiring.keySupplier("OPENROUTER_API_KEY").get().orElseThrow());
    }
}
