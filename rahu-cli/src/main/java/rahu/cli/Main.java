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
        ChatCommand.class, RunCommand.class, EvalCommand.class,
        // cli.md:13-14 document `rahu trace inspect` and `rahu replay`; neither
        // existed until AUDIT-2026-10-03-e/f, and ReplayEngine was unreachable
        // from src/main.
        rahu.cli.trace.TraceCommand.class, rahu.cli.trace.ReplayCommand.class })
public final class Main {

    public static void main(String[] args) {
        // .env (gitignored) is the local key source; real environment wins.
        rahu.cli.config.DotEnv.load(java.nio.file.Path.of("."));
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
