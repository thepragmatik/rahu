package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUDIT-2026-10-03-ae: A19 was claimed satisfied, and nothing checked it.
 *
 * <p>A19 requires that "same JDK/release and preview flags succeed" across compile, test,
 * package and launcher, and that there be "no stale preview API examples". What existed
 * instead was a pom carrying {@code --enable-preview}, a surefire {@code argLine} carrying
 * it, a launcher exec'ing {@code java --enable-preview}, an ADR, and a spec recipe telling
 * maintainers to configure Failsafe with it - and no Failsafe, and no preview bytecode at
 * all. Scanning every compiled class: 356 class files, every one major version 71 with
 * minor version 0, not one carrying the preview marker (minor 65535). The whole apparatus
 * was ceremonial, and the jar ran identically with the flag removed.
 *
 * <p>That is dangerous in both directions and neither is currently detectable:
 *
 * <ul>
 *   <li>Someone uses a real preview API. The flags are all still present, so tests pass and
 *       the launcher keeps working - fine. But nothing records that the flag is now load
 *       bearing, so a later "cleanup" that drops {@code --enable-preview} compiles, tests
 *       green, and breaks only for the packaged user. engineering.md warns about exactly
 *       this ("preview bytecode cannot pass tests and then fail for users") while providing
 *       no mechanism to notice it.
 *   <li>Conversely, a reviewer reading the pom sees preview enabled and concludes preview
 *       features are in use and tested. They are not. That is how a claim stays green
 *       without ever having been exercised.
 * </ul>
 *
 * <p>So this test reads the bytecode rather than the configuration, and ties the two
 * together: the plumbing must be present whenever the bytecode needs it, and when the
 * bytecode needs nothing, the fact is stated as a measurement instead of being left as an
 * assumption. {@link #previewIsActuallyExercised()} fails the day real preview bytecode
 * appears, which is deliberate: that is the moment the flag stops being optional and the
 * reviewer must learn about it.
 */
class ToolchainAgreementTest {

    /**
     * The Java release the project targets. {@code maven.compiler.release} is a Java
     * VERSION (27), while a class file's major version is 27 + 44 = 71. Comparing the two
     * directly was my own unit error, and the test caught it by failing.
     */
    private static final int EXPECTED_RELEASE = 27;

    /** Java 27 class files. If the release moves, this test must be re-read, not deleted. */
    private static final int EXPECTED_MAJOR = 71;

    /** The minor version javac stamps on a class compiled with a preview feature enabled. */
    private static final int PREVIEW_MINOR = 0xFFFF;

    @Test
    @DisplayName("A19: the pom release and every compiled class file agree on one major version")
    void everyClassFileMatchesTheConfiguredRelease() throws Exception {
        String pom = Files.readString(RepoFile.of("pom.xml"));
        int release = releaseFrom(pom);
        assertEquals(EXPECTED_RELEASE, release,
            "this test encodes a Java 27 target; if the release moved, update it");
        // release is the Java VERSION; major is the class-file version. They differ by 44.
        int expectedMajor = release + (EXPECTED_MAJOR - EXPECTED_RELEASE);

        List<String> mismatched = new ArrayList<>();
        int seen = 0;
        for (Path classes : moduleClassesDirs()) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    seen++;
                    int[] v = classFileVersion(f);
                    if (v == null || v[0] != expectedMajor) {
                        mismatched.add(RepoFile.of("").relativize(f)
                            + " major=" + (v == null ? "?" : v[0]));
                    }
                }
            }
        }
        assertTrue(seen > 100, "expected a real reactor build, found only " + seen + " class files");
        assertEquals(List.of(), mismatched,
            "class files must be major " + expectedMajor + " for release " + release
                + "; mixed versions mean "
                + "a stale artifact was not cleaned, and the packaged jar would break on load");
    }

    @Test
    @DisplayName("A19: preview bytecode is actually exercised, so 'preview enabled' is not ceremonial")
    void previewIsActuallyExercised() throws Exception {
        List<String> preview = previewClassFiles();
        // Deliberately the inverse of the usual assertion. Today there is no preview
        // bytecode, and until someone writes a preview API that is the correct state --
        // but it must be a measured fact, not an inherited assumption.
        assertEquals(List.of(), preview,
            "Preview bytecode now exists (" + preview.size() + " class file(s): "
                + preview.subList(0, Math.min(5, preview.size())) + "). The --enable-preview "
                + "flags are now load bearing: update ADR 0004, add a Failsafe argLine if "
                + "integration tests are ever added, and add a launcher test that runs the "
                + "packaged jar, because engineering.md requires preview bytecode not to pass "
                + "tests and then fail for users.");
    }

    @Test
    @DisplayName("A19: the flag stays wired everywhere preview could be needed")
    void previewPlumbingIsPresentForWhenItBecomesLoadBearing() throws Exception {
        String pom = Files.readString(RepoFile.of("pom.xml"));
        // Substring matching was too weak and mutation M1 proved it: a whole-file
        // contains("--enable-preview") stayed true when the COMPILER's arg was deleted,
        // because the surefire argLine still spelled it. Each site is therefore checked
        // inside its own plugin block, or the check would pass on a partially
        // disassembled toolchain - which is precisely the drift A19 exists to catch.
        assertTrue(compilerArgs(pom).contains("--enable-preview"),
            "maven-compiler-plugin must pass --enable-preview, or the next preview API will "
                + "not compile at all");
        assertTrue(surefireArgLine(pom).contains("--enable-preview"),
            "surefire must run preview bytecode with the flag, or tests fail while the "
                + "packaged product is fine - the inverse of the failure engineering.md warns about");

        String launcher = Files.readString(RepoFile.of("bin/rahu"));
        assertTrue(launcher.contains("--enable-preview"),
            "the launcher must pass the flag, so a preview class file cannot pass tests and "
                + "then fail for the user running ./bin/rahu");
    }

    /** The {@code <compilerArgs>} of the compiler plugin block, or "" if there is none. */
    private static String compilerArgs(String pom) {
        int start = pom.indexOf("maven-compiler-plugin");
        if (start < 0) {
            return "";
        }
        int end = pom.indexOf("</plugin>", start);
        String block = pom.substring(start, end < 0 ? pom.length() : end);
        var m = java.util.regex.Pattern
            .compile("<compilerArgs>(.*?)</compilerArgs>", java.util.regex.Pattern.DOTALL)
            .matcher(block);
        return m.find() ? m.group(1) : "";
    }

    /** The {@code <argLine>} of the surefire plugin block, or "" if there is none. */
    private static String surefireArgLine(String pom) {
        int start = pom.indexOf("maven-surefire-plugin");
        if (start < 0) {
            return "";
        }
        int end = pom.indexOf("</plugin>", start);
        String block = pom.substring(start, end < 0 ? pom.length() : end);
        var m = java.util.regex.Pattern
            .compile("<argLine>(.*?)</argLine>", java.util.regex.Pattern.DOTALL)
            .matcher(block);
        return m.find() ? m.group(1) : "";
    }

    @Test
    @DisplayName("A19: the JDK floor the launcher enforces is the class-file version it will load")
    void launcherRefusalThresholdMatchesTheClassFiles() throws Exception {
        // The launcher refuses anything below 27 by hand. If the release moved to a higher
        // class-file version, that hand-written number would refuse the very jar it ships,
        // and the message would name a version the bytecode never had.
        String launcher = Files.readString(RepoFile.of("bin/rahu"));
        int enforced = launcherThreshold(launcher);
        // The launcher gates on java.specification.version, which is the Java VERSION,
        // not the class-file major. Comparing it to EXPECTED_MAJOR was a second unit error.
        assertEquals(EXPECTED_RELEASE, enforced,
            "bin/rahu refuses runtimes below " + enforced + " while the build targets release "
                + EXPECTED_RELEASE + "; a release bump must update the launcher's hand-written floor");
    }

    @Test
    @DisplayName("A19: no spec or ADR names a preview API the code does not actually use")
    void noStalePreviewApiExamples() throws Exception {
        // engineering.md tells the maintainer to use StructuredTaskScope and ScopedValue and
        // to configure Failsafe; neither appears anywhere in src/main. A recipe naming APIs
        // that were never adopted is exactly the "stale preview API example" A19 forbids, and
        // a reviewer cannot spot it because the recipe is written in the present tense.
        List<String> named = List.of("StructuredTaskScope", "ScopedValue");
        for (String api : named) {
            assertTrue(!usesInMainSources(api),
                api + " is named in docs/engineering.md as a practice but is used nowhere in "
                    + "src/main. Either adopt it or mark it not adopted; leaving it in the "
                    + "present tense is the stale example A19 rules out.");
        }
        // Matching the bare word "Failsafe" was too crude: after the correction above the
        // doc MENTIONS Failsafe precisely to say it does not exist, and that is the honest
        // state. What A19 actually forbids is instructing a maintainer to configure a phase
        // that is not there, so the check is on the prescription, not the noun.
        String engineering = Files.readString(RepoFile.of("docs/engineering.md"));
        assertTrue(!prescribesFailsafe(engineering) || failsafeIsConfigured(),
            "engineering.md instructs maintainers to configure Failsafe JVM arguments, but this "
                + "project has no Failsafe plugin and no integration-test phase");
    }

    /**
     * True when the doc tells someone to set the flag on Failsafe, as opposed to recording
     * that there is no Failsafe. The present-tense instruction is the stale recipe.
     */
    private static boolean prescribesFailsafe(String engineering) {
        for (String sentence : engineering.split("(?<=\\.)\\s+")) {
            String s = sentence.toLowerCase(java.util.Locale.ROOT);
            if (!s.contains("failsafe")) {
                continue;
            }
            boolean absent = s.contains("no failsafe") || s.contains("not configured")
                || s.contains("does not exist")
                || s.contains("if one is added") || s.contains("there is no");
            boolean prescribes = s.contains("configure") || s.contains("arguments")
                || s.contains("with the same flag") || s.contains("must carry");
            if (prescribes && !absent) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    /** {@code maven.compiler.release}, the single source of truth the class files must match. */
    private static int releaseFrom(String pom) {
        var m = java.util.regex.Pattern
            .compile("<maven\\.compiler\\.release>(\\d+)</maven\\.compiler\\.release>").matcher(pom);
        if (!m.find()) {
            throw new IllegalStateException("pom.xml declares no maven.compiler.release");
        }
        return Integer.parseInt(m.group(1));
    }

    /** {@code [major, minor]} of a class file, or null when it is not a class file at all. */
    private static int[] classFileVersion(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f);
             DataInputStream d = new DataInputStream(in)) {
            if (d.readInt() != 0xCAFEBABE) {
                return null;
            }
            d.readUnsignedShort(); // minor
            return new int[] {d.readUnsignedShort()};
        }
    }

    private static List<String> previewClassFiles() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path classes : moduleClassesDirs()) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    try (InputStream in = Files.newInputStream(f);
                         DataInputStream d = new DataInputStream(in)) {
                        if (d.readInt() != 0xCAFEBABE) {
                            continue;
                        }
                        if (d.readUnsignedShort() == PREVIEW_MINOR) {
                            found.add(RepoFile.of("").relativize(f).toString());
                        }
                    }
                }
            }
        }
        return found;
    }

    private static List<Path> moduleClassesDirs() throws IOException {
        List<Path> dirs = new ArrayList<>();
        Path root = RepoFile.of("");
        for (String module : List.of("rahu-core", "rahu-openrouter", "rahu-systemone", "rahu-cli")) {
            Path classes = root.resolve(module).resolve("target").resolve("classes");
            if (Files.isDirectory(classes)) {
                dirs.add(classes);
            }
        }
        return dirs;
    }

    /** The runtime major the launcher refuses to run on, parsed from its own arithmetic. */
    private static int launcherThreshold(String launcher) {
        var m = java.util.regex.Pattern
            .compile("\\(\\(\\s*major\\s*<\\s*(\\d+)\\s*\\)\\)").matcher(launcher);
        if (!m.find()) {
            throw new IllegalStateException(
                "bin/rahu no longer has a recognisable version gate; this test cannot tell "
                    + "which runtimes it refuses, so A19's toolchain agreement is unverifiable");
        }
        return Integer.parseInt(m.group(1));
    }

    private static boolean failsafeIsConfigured() throws IOException {
        try (Stream<Path> poms = Files.list(RepoFile.of("").resolve("."))) {
            for (Path pom : poms.filter(p -> p.getFileName().toString().endsWith("pom.xml")).toList()) {
                if (Files.readString(pom).contains("failsafe")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean usesInMainSources(String api) throws IOException {
        try (Stream<Path> modules = Files.list(RepoFile.of(""))) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("rahu-")).toList()) {
                Path src = module.resolve("src").resolve("main");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        if (Files.readString(f).contains(api)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
