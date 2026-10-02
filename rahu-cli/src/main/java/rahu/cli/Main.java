package rahu.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Rahu CLI entrypoint. Subcommands are added per slice; see docs/specs/cli.md.
 */
@Command(
    name = "rahu",
    mixinStandardHelpOptions = true,
    version = "rahu 0.1.0",
    description = "Java agent harness with a configurable System One decision plane.",
    subcommands = { DemoCommand.class, ConfigCommand.class, RouteInspectCommand.class,
        ChatCommand.class, EvalCommand.class })
public final class Main {

    public static void main(String[] args) {
        // .env (gitignored) is the local key source; real environment wins.
        rahu.cli.config.DotEnv.load(java.nio.file.Path.of("."));
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
