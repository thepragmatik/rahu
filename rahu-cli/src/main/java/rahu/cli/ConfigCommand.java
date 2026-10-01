package rahu.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import rahu.cli.config.ConfigError;
import rahu.cli.config.ConfigLoader;
import rahu.cli.config.RahuConfig;

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

    @Command(name = "validate", description = "Structural validation; no model calls.")
    public static final class Validate implements Callable<Integer> {

        @Option(names = "--config", required = true, description = "Config JSON path")
        Path config;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            try {
                RahuConfig c = new ConfigLoader().load(config);
                spec.commandLine().getOut().println(
                    "config valid: mode=" + c.mode() + ", routing=" + c.routing().mode());
                return 0;
            } catch (ConfigError e) {
                spec.commandLine().getErr().println("config invalid: " + e.getMessage());
                return 2;
            }
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
