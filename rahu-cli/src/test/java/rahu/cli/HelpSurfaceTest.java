package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Every documented command must be able to describe itself (cli.md:47).
 *
 * <p>AUDIT-2026-10-03-f. cli.md requires that "help examples use consistent
 * terminology". None of the commands could: {@code rahu demo --help} answered
 * {@code Unknown option: '--help'} and {@code rahu chat --help} failed on a
 * missing {@code --config} before printing help at all. A command that cannot
 * print its own usage is the one place a user goes to learn it, so this is a
 * usability defect and also a discoverability defect for the commands that were
 * missing entirely.
 *
 * <p>The check drives {@link Main} through {@link CommandLine} — the real entry
 * point — rather than instantiating each command class. Registering a command
 * and being able to invoke it are different properties, and only the first path
 * can see the second.
 */
class HelpSurfaceTest {

    /**
     * Every command cli.md:8-16 documents <em>that exists</em>.
     *
     * <p>{@code run} (cli.md:11) is deliberately NOT in this list: it is documented
     * and unimplemented as of AUDIT-2026-10-03-f. Naming this "every command
     * cli.md documents" while quietly omitting one would make the test a claim the
     * code does not support — the exact defect this file exists to prevent. The gap
     * is asserted explicitly and visibly in {@link #runIsTheKnownUnimplementedGap()}
     * instead, and adding {@code run} here when it lands will fail that test,
     * which is the reminder to move it over.
     */
    private static final List<String> DOCUMENTED = List.of(
        "demo", "config", "config validate", "config show", "route", "route inspect",
        "chat", "trace", "trace inspect", "replay", "eval");

    private record Result(int exit, String out) {
    }

    private static Result run(String commandLine) throws IOException {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        int code;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            code = new CommandLine(new Main()).execute(commandLine.split(" "));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return new Result(code, out.toString(StandardCharsets.UTF_8)
            + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("every command in cli.md exists on the entry point")
    void everyDocumentedCommandIsRegistered() {
        var registered = new CommandLine(new Main()).getSubcommands();
        for (String command : DOCUMENTED) {
            String[] parts = command.split(" ");
            assertTrue(registered.containsKey(parts[0]),
                "cli.md documents `rahu " + command + "` but it is not registered; found "
                    + registered.keySet());
            if (parts.length > 1) {
                var children = registered.get(parts[0]).getSubcommands();
                assertTrue(children.containsKey(parts[1]),
                    "cli.md documents `rahu " + command + "` but the subcommand is"
                        + " missing; found " + children.keySet());
            }
        }
    }

    @Test
    @DisplayName("every command answers --help with usage and exit 0")
    void everyCommandAnswersHelp() throws IOException {
        for (String command : DOCUMENTED) {
            Result result = run(command + " --help");
            String label = "rahu " + command + " --help";
            assertEquals(0, result.exit(),
                label + " exited " + result.exit() + " instead of printing help:"
                    + result.out());
            assertTrue(result.out().contains("Usage:"), label + " printed no usage");
        }
    }

    /**
     * The one cli.md command that does not exist, asserted so the gap stays visible.
     *
     * <p>When {@code rahu run} is implemented this test FAILS, and the fix is to add
     * "run" to {@link #DOCUMENTED} — not to relax the assertion. A gap marker that
     * can be satisfied by weakening itself is worse than no marker.
     */
    @Test
    @DisplayName("run is the known unimplemented gap (cli.md:11)")
    void runIsTheKnownUnimplementedGap() {
        var registered = new CommandLine(new Main()).getSubcommands();
        assertTrue(!registered.containsKey("run"),
            "rahu run now exists. Remove this gap marker and add \"run\" to"
                + " DOCUMENTED so the help and registration checks cover it.");
    }

    @Test
    @DisplayName("every command answers -h as well as --help")
    void shortHelpFlagWorks() throws IOException {
        for (String command : DOCUMENTED) {
            Result result = run(command + " -h");
            assertEquals(0, result.exit(), "rahu " + command + " -h exited " + result.exit());
            assertTrue(result.out().contains("Usage:"), "rahu " + command + " -h printed no usage");
        }
    }

    @Test
    @DisplayName("help does not require the command's own required options")
    void helpDoesNotRequireOptions() throws IOException {
        // `rahu chat --help` used to fail with "Missing required option:
        // --config" - the user could not see how to supply it. The mixin makes the
        // help flag short-circuit required-option validation.
        for (String command : DOCUMENTED) {
            Result result = run(command + " --help");
            assertTrue(!result.out().contains("Missing required"),
                "rahu " + command + " --help demanded its own options first");
        }
    }
}