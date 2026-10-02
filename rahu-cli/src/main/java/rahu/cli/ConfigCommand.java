package rahu.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.LiveCheckReport;
import rahu.cli.config.RahuConfig;
import rahu.core.ModelRef;
import rahu.openrouter.ModelProfileCatalog;

/**
 * config validate / config show (cli.md): structural validation with actionable
 * errors; show prints the resolved config with secrets redacted (they are never
 * read — only env-var names appear).
 */
@Command(name = "config",
    description = "Validate or show the resolved configuration.",
    subcommands = { ConfigCommand.Validate.class, ConfigCommand.Show.class })
public final class ConfigCommand {

    private ConfigCommand() {
    }

    @Command(name = "validate",
        description = "Structural validation; no model calls unless --live-check.")
    public static final class Validate implements Callable<Integer> {

        private static final int DEFAULT_CACHE_TTL_SECONDS = 86400;
        private static final int DEFAULT_MAX_STALE_SECONDS = 172800;

        @Option(names = "--config", required = true, description = "Config JSON path")
        Path config;

        @Option(names = "--live-check", description = "Resolve catalog profile evidence for "
            + "every configured pool model. Unresolved or unadmitted evidence is a "
            + "configuration error (exit 2).")
        boolean liveCheck;

        @Option(names = "--evidence", description = "Model-profile evidence file "
            + "(default: docs/generated/model-profiles.json)")
        Path evidence = Path.of("docs/generated/model-profiles.json");

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            try {
                RahuConfig c = new ConfigLoader().load(config);
                var out = spec.commandLine().getOut();
                out.println("config valid: mode=" + c.mode() + ", routing=" + c.routing().mode());
                if (!liveCheck) {
                    return 0;
                }
                return checkEvidence(c, out);
            } catch (ConfigError e) {
                spec.commandLine().getErr().println("config invalid: " + e.getMessage());
                return 2;
            }
        }

        /**
         * Resolves profile evidence per pool model. The catalog TTL governs REFETCH;
         * the configured maximum staleness governs ADMISSION, so an operator can be
         * shown stale evidence (and can allow it) without a fetch being implied.
         */
        private int checkEvidence(RahuConfig c, PrintWriter out) {
            RahuConfig.CatalogConfig catalog = c.catalog();
            int ttl = catalog == null || catalog.cacheTtlSeconds() == null
                ? DEFAULT_CACHE_TTL_SECONDS : catalog.cacheTtlSeconds();
            int maxStale = catalog == null || catalog.maximumStaleSeconds() == null
                ? DEFAULT_MAX_STALE_SECONDS : catalog.maximumStaleSeconds();
            boolean allowStale = catalog != null && Boolean.TRUE.equals(catalog.allowStale());

            String poolName = c.routing() == null ? null : c.routing().pool();
            List<RahuConfig.PoolEntry> pool = poolName == null || c.pools() == null
                ? List.of() : c.pools().getOrDefault(poolName, List.of());
            if (pool.isEmpty()) {
                spec.commandLine().getErr().println(
                    "pool '" + poolName + "' has no models; nothing to check");
                return 2;
            }

            var profiles = new ModelProfileCatalog(
                c.generation() == null ? null : c.generation().baseUrl(), evidence);
            Instant now = Instant.now();
            try {
                profiles.refreshIfNeeded(Duration.ofSeconds(ttl), now);
            } catch (IOException e) {
                spec.commandLine().getErr().println(
                    "profile evidence unavailable: " + e.getMessage());
                return 2;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                spec.commandLine().getErr().println("profile evidence check interrupted");
                return 2;
            }

            // Evidence failures are resolved BEFORE rendering so an unreadable catalog
            // is never reported as a missing model.
            Map<String, LiveCheckReport.Resolution> resolved = new LinkedHashMap<>();
            try {
                for (var entry : pool) {
                    resolved.put(entry.alias(),
                        resolve(profiles, new ModelRef(entry.id()), maxStale, now));
                }
            } catch (IOException e) {
                spec.commandLine().getErr().println(
                    "profile evidence unavailable: " + e.getMessage());
                return 2;
            }

            var entries = LiveCheckReport.entries(pool,
                entry -> resolved.get(entry.alias()), allowStale);
            for (var entry : entries) {
                out.println(entry.rendered());
            }
            boolean admitted = LiveCheckReport.allAdmitted(entries);
            out.println("pool " + poolName + ": "
                + (admitted ? "every model admitted" : "profile evidence incomplete"));
            if (admitted) {
                return 0;
            }
            spec.commandLine().getErr().println("pool '" + poolName
                + "' has models without admissible profile evidence");
            return 2;
        }

        private static LiveCheckReport.Resolution resolve(ModelProfileCatalog profiles,
            ModelRef ref, int maxStaleSeconds, Instant now) throws IOException {

            var profile = profiles.profile(ref);
            if (profile == null) {
                return LiveCheckReport.Resolution.missing();
            }
            return new LiveCheckReport.Resolution(profile,
                profiles.profileFresh(ref, Duration.ofSeconds(maxStaleSeconds), now));
        }
    }

    @Command(name = "show", description = "Print resolved, redacted configuration.")
    public static final class Show implements Callable<Integer> {

        @Option(names = "--config", required = true, description = "Config JSON path")
        Path config;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            try {
                RahuConfig c = new ConfigLoader().load(config);
                var out = spec.commandLine().getOut();
                out.println("mode: " + c.mode());
                out.println("decision: adapter=" + c.decision().adapter()
                    + ", model=" + c.decision().model()
                    + ", apiKeyEnv=" + (c.decision().apiKeyEnv() == null ? "(none)" : "set"));
                out.println("generation: adapter=" + c.generation().adapter()
                    + ", apiKeyEnv=" + (c.generation().apiKeyEnv() == null
                        ? "(none)" : c.generation().apiKeyEnv()));
                out.println("routing: mode=" + c.routing().mode() + ", pool=" + c.routing().pool()
                    + ", baseline=" + c.routing().baseline() + ", fallback=" + c.routing().fallback());
                out.println("privacy: mode=" + c.privacy().mode() + ", onUnknown="
                    + c.privacy().onUnknown() + ", inputClassification="
                    + c.privacy().inputClassification());
                out.println("session: maxTurns=" + c.session().maxTurns()
                    + ", maxCostUsd=" + c.session().maxCostUsd());
                return 0;
            } catch (ConfigError e) {
                spec.commandLine().getErr().println("config invalid: " + e.getMessage());
                return 2;
            }
        }
    }
}
