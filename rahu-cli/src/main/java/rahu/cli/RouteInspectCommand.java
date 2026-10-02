package rahu.cli;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;
import rahu.core.ModelProfile;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.routing.CandidateFactory;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.ModelPool;
import rahu.core.routing.OperationRequirements;
import rahu.core.routing.PoolModel;

/**
 * route inspect (cli.md): explain feasible candidates and exclusions without a
 * paid call. Offline mode uses synthetic evidence; live evidence lands with S04.
 */
@Command(name = "route",
    description = "Route inspection.",
    subcommands = { RouteInspectCommand.Inspect.class })
public final class RouteInspectCommand {

    private RouteInspectCommand() {
    }

    @Command(name = "inspect",
        description = "Explain feasible candidates and exclusions; no decision call.")
    public static final class Inspect implements Callable<Integer> {

        @Option(names = "--config", required = true, description = "Config JSON path")
        Path config;

        @Option(names = "--prompt", required = true, description = "Prompt text (untrusted input)")
        String prompt;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            try {
                RahuConfig c = new ConfigLoader().load(config);
                if (!"offline".equals(c.mode())) {
                    spec.commandLine().getErr().println(
                        "route inspect supports offline configs until S04 catalog evidence lands");
                    return 2;
                }
                List<RahuConfig.PoolEntry> entries = c.pools().get(c.routing().pool());
                List<PoolModel> models = entries.stream()
                    .<PoolModel>map(e -> new PoolModel(e.alias(),
                        new ModelRef(e.id()),
                        new java.util.LinkedHashSet<>(toPolicies(e.reasoning()))))
                    .toList();
                Map<ModelRef, ModelProfile> profiles = new HashMap<>();
                for (var e : entries) {
                    profiles.put(new ModelRef(e.id()), syntheticProfile(new ModelRef(e.id())));
                }
                var pool = new ModelPool(c.routing().pool(), models);
                CandidateSet set = new CandidateFactory().generate(pool, profiles,
                    new OperationRequirements(true, false, 2000, false),
                    "synthetic-catalog", "config-" + c.schemaVersion());

                var out = spec.commandLine().getOut();
                out.println("pool: " + c.routing().pool() + "  candidates: "
                    + set.candidates().size());
                for (var cand : set.candidates()) {
                    out.println("  " + cand.id() + " -> " + cand.model().providerNeutralId());
                }
                if (!set.exclusions().isEmpty()) {
                    out.println("exclusions:");
                    for (var x : set.exclusions()) {
                        out.println("  " + x.ref() + " (" + x.reason() + ")");
                    }
                }
                out.println("baseline: " + c.routing().baseline() + "  fallback: "
                    + c.routing().fallback() + "  mode: " + c.routing().mode());
                return 0;
            } catch (ConfigError e) {
                spec.commandLine().getErr().println("config invalid: " + e.getMessage());
                return 2;
            }
        }

        private static List<ReasoningPolicy> toPolicies(List<String> reasoning) {
            return reasoning.stream().map(r -> switch (r) {
                case "default" -> (ReasoningPolicy) new ReasoningPolicy.ProviderDefault();
                case "none" -> ReasoningPolicy.Disabled.INSTANCE;
                default -> ReasoningPolicy.ExplicitEffort.of(
                    ReasoningPolicy.Effort.valueOf(r.toUpperCase()));
            }).toList();
        }

        /** Synthetic offline evidence (offline.json demo pool semantics). */
        private static ModelProfile syntheticProfile(ModelRef ref) {
            return new ModelProfile(ref, 32000, 4096,
                new rahu.core.MoneyAmount(new java.math.BigDecimal("0.000001"),
                    rahu.core.CurrencyUnit.USD),
                new rahu.core.MoneyAmount(new java.math.BigDecimal("0.000002"),
                    rahu.core.CurrencyUnit.USD),
                ReasoningPolicy.Effort.values(), false,
                java.time.Instant.parse("2026-10-01T00:00:00Z"), true, true);
        }
    }
}
