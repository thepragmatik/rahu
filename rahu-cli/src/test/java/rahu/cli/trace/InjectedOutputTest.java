package rahu.cli.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A command that prints to {@code System.out} has an untestable output surface.
 *
 * <p>AUDIT-2026-10-03-i. {@code ReplayCommand}, {@code TraceInspectCommand} and
 * {@code TraceCommand} all printed to the real streams while the rest of the CLI used
 * picocli's injected writers. The harness then captured <em>nothing</em> for them, so
 * every assertion on their output passed on an EMPTY STRING.
 *
 * <p>This is the most dangerous shape a test defect can take, because it is invisible:
 * the assertions look real, the suite is green, and the code is wrong. It is exactly
 * how a message telling an operator to change an already-correct config survived a full
 * increment while its test "verified" the output.
 *
 * <p>Two tests, because catching the instance is not enough — the class has to stay
 * closed:
 * <ol>
 *   <li>a source scan, so a NEW command that reaches for {@code System.out} fails the
 *       build rather than quietly becoming untestable;</li>
 *   <li>an executable check, because a scan can be satisfied by a comment while the
 *       behaviour is still broken.</li>
 * </ol>
 */
class InjectedOutputTest {

    /**
     * Resolved from the module, not from the process working directory.
     *
     * <p>The first version hard-coded {@code "src/main/java"}, which surefire only
     * happens to satisfy. Locating the tree from a class we already have makes the
     * scan independent of how the tests are launched.
     */
    private static final Path MAIN = Path.of(
        InjectedOutputTest.class.getProtectionDomain().getCodeSource().getLocation().getPath())
        .resolve("../../src/main/java").normalize();

    /**
     * Files allowed to print to the real streams, and why.
     *
     * <p>Scoped STRUCTURALLY, not by a hand-maintained list of methods: the scan
     * exempts any print that appears after a {@code public static void main} in its
     * file. A {@code main} body is a program entry point invoked by
     * {@code mvn exec:java}, not a library method a test drives — there is no
     * picocli writers to route through. Only two files qualify today
     * ({@code SchemaGenerator}, {@code ShadowCorpusProbe}) and neither is registered
     * as a subcommand, which is verified rather than assumed.
     */
    private static boolean isInsideAMainEntryPoint(List<String> lines, int printIndex) {
        for (int i = 0; i < printIndex; i++) {
            if (lines.get(i).contains("public static void main(")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The exempt files must not become shipped commands unnoticed.
     *
     * <p>If {@code SchemaGenerator} or {@code ShadowCorpusProbe} were ever registered
     * in {@code Main}'s subcommands, their {@code main} bodies would become a
     * library path whose output no test could assert. This is the check that keeps the
     * exemption honest instead of permanent.
     */
    @Test
    @DisplayName("no file that prints to the real streams is registered as a subcommand")
    void filesPrintingToRealStreamsAreNotShippedCommands() throws IOException {
        String main = Files.readString(MAIN.resolve("rahu/cli/Main.java"), StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("System.out.print") && !text.contains("System.err.print")) {
                    continue;
                }
                if (!text.contains("public static void main(")) {
                    // Not an entry point; the main scan already covers it.
                    continue;
                }
                String type = file.getFileName().toString().replace(".java", "");
                if (main.contains(type + ".class")) {
                    offenders.add(type);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "these print to the real streams and are registered as subcommands, so their "
                + "output is untestable: " + offenders);
    }

    @Test
    @DisplayName("no command under src/main prints to the real streams")
    void noCommandBypassesInjectedWriters() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                List<String> lines = List.of(text.split("\n", -1));
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.strip();
                    // Skip comments and javadoc: a scan must not be satisfiable (or
                    // unsatisfiable) by prose about the rule.
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")
                        || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (isInsideAMainEntryPoint(lines, i)) {
                        continue;
                    }
                    if (trimmed.contains("System.out.print") || trimmed.contains("System.err.print")
                        || trimmed.contains("System.out.format")
                        || trimmed.contains("System.err.format")
                        || trimmed.contains("System.out.printf")
                        || trimmed.contains("System.err.printf")) {
                        offenders.add(MAIN.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "these write to the real streams, so no test can assert on their output "
                + "(AUDIT-2026-10-03-i). Route them through picocli's "
                + "spec.commandLine().getOut()/getErr(), or take an injected PrintWriter:\n  "
                + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("replay's output actually arrives on the injected writer, not System.out")
    void replayReachesTheInjectedWriter() throws IOException {
        Path run = Files.createTempDirectory("injected-replay-");
        // events.jsonl with the two events the integrity predicate requires, so the
        // command reaches its normal output rather than an error path.
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            "{\"sequence\":1,\"type\":\"RunStarted\"}\n"
                + "{\"sequence\":2,\"type\":\"RouteResolved\",\"payload\":{"
                + "\"suggested\":\"fast@low\",\"executed\":\"fast@low\",\"degraded\":false}}\n"
                + "{\"sequence\":3,\"type\":\"RunTerminated\"}\n",
            StandardCharsets.UTF_8);

        java.io.StringWriter capturedOut = new java.io.StringWriter();
        java.io.StringWriter capturedErr = new java.io.StringWriter();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        try {
            // Capture the real streams so that anything bypassing injection is
            // visible as a NON-EMPTY side channel rather than silently lost.
            ByteArrayOutputStream leakedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream leakedErr = new ByteArrayOutputStream();
            System.setOut(new PrintStream(leakedOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(leakedErr, true, StandardCharsets.UTF_8));
            picocli.CommandLine cmd = new picocli.CommandLine(
                new rahu.cli.trace.TraceCommand());
            cmd.setOut(new java.io.PrintWriter(capturedOut, true));
            cmd.setErr(new java.io.PrintWriter(capturedErr, true));
            cmd.execute("inspect", run.toString(), "--format", "text");
            System.setOut(realOut);
            System.setErr(realErr);
            String leaked = leakedOut.toString(StandardCharsets.UTF_8);
            assertTrue(leaked.isEmpty(),
                "inspect wrote " + leaked.length() + " chars to the real System.out: <"
                    + leaked + "> — output that bypasses injection is untestable");
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        // And the injected writer really received the completeness report.
        assertTrue(capturedOut.toString().contains("RunStarted")
                || capturedOut.toString().contains("events"),
            "inspect produced no report on the injected writer: <"
                + capturedOut + "> stderr: <" + capturedErr + ">");
    }

    @Test
    @DisplayName("trace with no subcommand prints usage to the injected error writer")
    void traceUsageIsAssertable() {
        java.io.StringWriter capturedOut = new java.io.StringWriter();
        java.io.StringWriter capturedErr = new java.io.StringWriter();
        picocli.CommandLine cmd = new picocli.CommandLine(new TraceCommand());
        cmd.setOut(new java.io.PrintWriter(capturedOut, true));
        cmd.setErr(new java.io.PrintWriter(capturedErr, true));
        int code = cmd.execute();
        assertFalse(capturedErr.toString().isEmpty(),
            "`rahu trace` alone must say how to use it on the injected writer, so the "
                + "usage text is assertable");
        assertTrue(capturedErr.toString().contains("rahu trace inspect"),
            "the usage text should name the actual subcommand: <" + capturedErr + ">");
        assertFalse(code == 0, "a bare `rahu trace` is a usage error, not a success");
    }
}
