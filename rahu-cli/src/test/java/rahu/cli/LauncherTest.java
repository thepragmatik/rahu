package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUDIT-2026-10-03-aa: A28 requires a "launcher verified". It was not: the launcher's
 * own comment claimed it pinned the JDK while it exec'd bare {@code java}.
 *
 * <p>The sharp edge was that the README tells users to {@code export JAVA_HOME=/path/to/jdk27},
 * and the launcher never read JAVA_HOME. Set a correct JDK 27, leave a JDK 21 first on
 * PATH, and the launcher still died - on a class file version number, with no mention of
 * which java ran or how to fix it.
 *
 * <p>These tests run the real script in a child process with a controlled PATH, because
 * the behaviour under test IS the choice of executable. Asserting on the file's text
 * instead would pass on a launcher that is broken, and would pass on one that had been
 * rewritten but still describes itself correctly.
 */
class LauncherTest {

    private static final Path LAUNCHER = RepoFile.of("bin/rahu");

    @Test
    @DisplayName("JAVA_HOME decides which java runs, even when PATH prefers another JDK")
    void javaHomeBeatsPath() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(LAUNCHER),
            "bin/rahu is missing or not executable at " + LAUNCHER);

        String wrong = firstJdkOnDiskLowerThan(27);
        String right = javaHomeUnderTest();
        Assumptions.assumeTrue(wrong != null && right != null,
            "needs both an older JDK on disk and a JDK 27 JAVA_HOME to compare");

        // PATH deliberately leads with the OLD jdk. Only a launcher that reads
        // JAVA_HOME can possibly succeed here, and it does.
        var ok = run(right, pathLeadingWith(wrong));

        assertEquals(0, ok.exit, "JAVA_HOME=" + right + " must win over PATH's " + wrong
            + "; the README's setup instruction has to actually work. Output:\n"
            + ok.output);
        assertTrue(ok.output.contains("rahu stand-in ok"),
            "expected the stand-in Main to run under the chosen java, got: " + ok.output);
    }

    @Test
    @DisplayName("a too-old runtime is refused with the fix in the message, not a class version")
    void tooOldRuntimeIsRefusedWithGuidance() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(LAUNCHER),
            "bin/rahu is missing or not executable at " + LAUNCHER);

        String old = firstJdkOnDiskLowerThan(27);
        Assumptions.assumeTrue(old != null, "needs an older JDK installed to test the refusal");

        var refused = run(null, pathLeadingWith(old));

        assertNotEquals(0, refused.exit,
            "a JDK too old to load the classes must NOT be allowed to try: " + refused.output);
        assertTrue(refused.output.contains("JDK 27"),
            "the refusal must name the required version, got: " + refused.output);
        assertTrue(refused.output.contains("JAVA_HOME"),
            "the refusal must tell the operator how to fix it, got: " + refused.output);
        // The old failure mode. This is the whole point of the fix: the operator must
        // never be handed a class file version number to decode.
        assertTrue(!refused.output.contains("UnsupportedClassVersionError"),
            "the operator should never see a class file version number; got: " + refused.output);
    }

    // ------------------------------------------------------------------ harness

    private record Result(int exit, String output) {
    }

    private static Result run(String javaHome, String path) throws Exception {
        Path script = sandboxWithStandInJar();

        List<String> cmd = new ArrayList<>(List.of(script.toString(), "--version"));
        ProcessBuilder pb = new ProcessBuilder(cmd)
            // Start from a clean env: an inherited JAVA_HOME or an inherited PATH entry
            // would decide the outcome and make the test test the developer instead.
            .redirectErrorStream(true)
            .directory(script.getParent().toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.equals("JAVA_HOME"));
        env.put("PATH", path);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome);
        }

        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "launcher hung");
        return new Result(proc.exitValue(), out);
    }

    /**
     * Copies the launcher next to a tiny stand-in jar.
     *
     * <p>This is why the test needs no packaging step. My first version ran the real
     * {@code rahu-cli.jar} and therefore SKIPPED itself during {@code clean verify},
     * because surefire runs before the jar is built - a test that quietly opts out of
     * the very build it is meant to police. The launcher resolves its jar as
     * {@code $SCRIPT_DIR/../rahu-cli/target/rahu-cli.jar}, so a two-class stand-in
     * reproduces that layout and lets the launcher, the executable choice and the
     * version guard all be exercised for real on every run.
     */
    private static Path sandboxWithStandInJar() throws Exception {
        Path root = Files.createTempDirectory("rahu-launcher");
        Path script = root.resolve("bin").resolve("rahu");
        Path jar = root.resolve("rahu-cli/target/rahu-cli.jar");
        Files.createDirectories(script.getParent());
        Files.createDirectories(jar.getParent());
        Files.copy(LAUNCHER, script);
        Files.copy(standInJar(), jar, StandardCopyOption.REPLACE_EXISTING);
        return script;
    }

    /** A jar whose Main announces itself, so a successful launch is observable. */
    private static Path standInJar() throws Exception {
        Path work = Files.createTempDirectory("rahu-standin");
        Path src = work.resolve("StandIn.java");
        Files.writeString(src, "public class StandIn {\n"
            + "    public static void main(String[] a) {\n"
            + "        System.out.println(\"rahu stand-in ok\");\n"
            + "    }\n"
            + "}\n");
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        Process p = new ProcessBuilder(javac, src.toString())
            .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "javac hung: " + log);
        assertEquals(0, p.exitValue(), "could not compile the stand-in Main: " + log);
        return jar(work);
    }

    private static Path jar(Path work) throws Exception {
        String jarTool = Path.of(System.getProperty("java.home"), "bin", "jar").toString();
        Path out = work.resolve("rahu-cli.jar");
        Path manifest = work.resolve("MANIFEST.MF");
        // Main-Class is mandatory for `java -jar`. My first stand-in omitted it and the
        // launcher reported "no main manifest attribute" - which looked like a launcher
        // bug in the failure output but was a broken fixture. The launcher was right.
        Files.writeString(manifest, "Manifest-Version: 1.0\nMain-Class: StandIn\n\n");
        Process p = new ProcessBuilder(jarTool, "--create", "--file", out.toString(),
            "--manifest", manifest.toString(),
            "-C", work.toString(), "StandIn.class")
            .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "jar hung: " + log);
        assertEquals(0, p.exitValue(), "could not build the stand-in jar: " + log);
        return out;
    }

    private static String pathLeadingWith(String jdk) {
        return jdk + "/bin:/usr/bin:/bin";
    }

    /** The JDK 27 that is building the project, i.e. JAVA_HOME as configured. */
    private static String javaHomeUnderTest() {
        String h = System.getProperty("java.home");
        // java.home is the JRE inside the JDK on some layouts; normalise to the JDK root.
        Path p = Path.of(h);
        if (p.getFileName() != null && p.getFileName().toString().equals("jre")) {
            p = p.getParent();
        }
        return Files.isExecutable(p.resolve("bin/java")) ? p.toString() : null;
    }

    /** Any JDK below 27 on this host, so the refusal path can be exercised for real. */
    private static String firstJdkOnDiskLowerThan(int floor) throws IOException, InterruptedException {
        List<String> roots = List.of("/Library/Java/JavaVirtualMachines", "/opt/homebrew/opt",
            "/usr/local/opt", System.getProperty("user.home") + "/.sdkman/candidates/java");
        for (String root : roots) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var entries = Files.list(dir)) {
                for (Path candidate : entries.sorted().toList()) {
                    Path home = Files.isDirectory(candidate.resolve("Contents/Home"))
                        ? candidate.resolve("Contents/Home") : candidate;
                    // Named javaBin, not java: a parameter called java shadows the
                    // package root and breaks every java.nio.* reference below it.
                    Path javaBin = home.resolve("bin/java");
                    if (Files.isExecutable(javaBin)) {
                        String v = specVersion(javaBin);
                        if (v != null && !v.isBlank() && Integer.parseInt(v) < floor) {
                            return home.toString();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String specVersion(Path javaBin) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(javaBin.toString(), "-XshowSettings:properties", "-version")
            .redirectErrorStream(true).start();
        String all = new String(p.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        for (String line : all.split("\n")) {
            String t = line.trim();
            if (t.startsWith("java.specification.version")) {
                int eq = t.indexOf('=');
                if (eq > 0) {
                    return t.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }
}