package rahu.cli;

import java.util.Optional;
import java.util.function.Supplier;
import rahu.cli.config.DotEnv;
import rahu.cli.config.RahuConfig;
import rahu.core.model.ModelProvider;
import rahu.openrouter.OpenRouterProvider;
import rahu.systemone.DecisionEngine;
import rahu.systemone.SystemOneHttpAdapter;

/**
 * Builds the live adapters from a validated config (extensibility.md adapter
 * selection). Secrets resolve through {@link DotEnv} (environment first, then
 * the gitignored .env) — a config literal can never carry a credential.
 */
public final class LiveWiring {

    private LiveWiring() {
    }

    /** Resolves a credential name to a value supplier; never logs the value. */
    public static Supplier<Optional<String>> keySupplier(String envName) {
        if (envName == null || envName.isBlank()) {
            return Optional::empty;
        }
        return () -> DotEnv.get(envName);
    }

    /**
     * Decision plane. Both adapter names speak the same {model,state,questions}
     * wire shape: a loopback System One service and OpenRouter's hosted Jev
     * endpoint (alpha/decisions) differ only in base URL and credential.
     */
    public static DecisionEngine decision(RahuConfig cfg) {
        var decision = cfg.decision();
        String adapter = decision.adapter() == null ? "" : decision.adapter();
        if (!adapter.equals("system-one-http") && !adapter.equals("openrouter-decisions")) {
            throw new IllegalArgumentException("unknown decision adapter \"" + adapter
                + "\"; expected system-one-http or openrouter-decisions");
        }
        return new SystemOneHttpAdapter(
            decision.baseUrl(),
            decision.model(),
            decision.compatibilityProfile() == null
                ? "jev-compatible-v1" : decision.compatibilityProfile(),
            decision.timeoutMillis() == null ? 8000 : decision.timeoutMillis(),
            keySupplier(decision.apiKeyEnv()));
    }

    /** Generation plane (OpenRouter chat completions). */
    public static ModelProvider generation(RahuConfig cfg) {
        var generation = cfg.generation();
        String adapter = generation.adapter() == null ? "" : generation.adapter();
        if (!adapter.equals("openrouter")) {
            throw new IllegalArgumentException("unknown generation adapter \"" + adapter
                + "\"; expected openrouter");
        }
        return new OpenRouterProvider(generation.baseUrl(),
            keySupplier(generation.apiKeyEnv()));
    }
}
