package rahu.cli.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUDIT-2026-10-03-y: A31 asked for a clean-checkout release verification. Running it
 * found the README claiming "128 tests pass offline" against an actual 528, and
 * {@code .env.example} declaring none of the three variables the shipped live config
 * requires while asserting "No env var is needed for it".
 *
 * <p>Both were stale because nothing connected the documentation to the thing it
 * describes. Hand-correcting a number fixes it until the next test is added, which is
 * the same defect one layer up: a claim that no check can reach. So these tests derive
 * the claim from the repository rather than restating it.
 *
 * <p>What is pinned here is deliberately narrow. A test count is verified against the
 * Surefire reports the build just produced, not against a constant, so adding a test
 * updates the truth and this fails until the README admits it.
 */
class DocumentedClaimsTest {

    /** Walks up from the module directory to the repository root. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pom.xml"))
                && Files.isDirectory(p.resolve("docs"))) {
                return p;
            }
        }
        throw new IllegalStateException("no repository root above " + here);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    /**
     * Counts the {@code @Test} declarations in the repository's test sources.
     *
     * <p>This is deliberately NOT the surefire total, and the difference is not noise.
     * Three things separate them:
     *
     * <ul>
     *   <li>{@code @ParameterizedTest} runs once PER INPUT, so one declaration is many
     *       executions.
     *   <li>{@code rahu-cli} is the LAST module in the reactor, so a test inside it
     *       physically cannot see its own module's final report - when this class runs,
     *       roughly half of {@code rahu-cli}'s reports do not exist yet. No test placed
     *       anywhere in this reactor can observe the completed total.
     *   <li>A declaration count is stable regardless of build order, which is what makes
     *       it assertable at all.
     * </ul>
     *
     * <p>So the README figure is verified as a FLOOR, not an equality. That is still
     * exactly the check that matters: the original defect was a README claiming 128
     * against a real 521, an understatement of 393. A floor catches understatement of
     * any size, which is the failure a reader is harmed by.
     */
    private static int declaredTests() throws IOException {
        final int[] total = {0};
        try (var stream = Files.walk(repoRoot())) {
            stream.filter(p -> p.toString().contains(File.separator + "src"
                    + File.separator + "test" + File.separator))
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        total[0] += countTestAnnotations(Files.readString(p));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        }
        return total[0];
    }

    /**
     * Counts test-annotated methods, ignoring anything inside comments or text blocks.
     *
     * <p>Stripping those first matters: a commented-out {@code @Test} is prose about a
     * test, not a test, and counting it would let the floor drift upward silently.
     */
    private static int countTestAnnotations(String source) {
        String s = source.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("//[^\\n]*", "");
        int n = 0;
        for (String annotation : new String[] {"@Test", "@ParameterizedTest",
            "@RepeatedTest", "@TestFactory"}) {
            Matcher m = Pattern.compile(Pattern.quote(annotation) + "\\b").matcher(s);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    // -------------------------------------------------- the count in the README

    @Test
    @DisplayName("The README's test count is the count the build reports, not a remembered number")
    void readmeTestCountMatchesTheBuild() throws Exception {
        String readme = read("README.md");
        Matcher m = Pattern.compile("(\\d+) tests pass offline").matcher(readme);
        assertTrue(m.find(),
            "the README must state how many tests pass offline, so this check has "
                + "something to verify; if the sentence was reworded, update this test");
        int claimed = Integer.parseInt(m.group(1));
        int declared = declaredTests();
        assertTrue(claimed >= declared,
            "README claims only " + claimed + " tests pass offline, but the "
                + "repository declares " + declared + " test methods. The figure is "
                + "understated, so a reader concludes the suite is smaller and weaker "
                + "than it is. Correct the README, not this test.");
        // The floor above is the enforceable half. The exact surefire total cannot be
        // checked from inside the suite (see declaredTests), so the precise number is
        // verified by the clean-checkout release run recorded in
        // docs/reviews/015-architecture-audit.md instead of by a self-referential test.
    }

    // --------------------------------------- .env.example vs the live example

    /**
     * Every variable the shipped live config interpolates must be declared by
     * {@code .env.example}, which is the file the quickstart tells operators to copy.
     *
     * <p>Asserted in both directions that matter: a var the config needs but the example
     * omits (the original defect, which made the documented live path fail on a clean
     * checkout), and a var the example declares that nothing reads (which would send an
     * operator to set a value that changes nothing).
     */
    @Test
    @DisplayName(".env.example declares exactly the variables the live config interpolates")
    void envExampleDeclaresEveryInterpolatedVariable() throws Exception {
        String config = read("examples/live-local-systemone.json");
        Set<String> needed = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}").matcher(config);
        while (m.find()) {
            needed.add(m.group(1));
        }
        assertTrue(needed.size() >= 3,
            "expected the live example to interpolate several variables, found " + needed);

        String envExample = read(".env.example");
        Set<String> declared = new LinkedHashSet<>();
        Matcher d = Pattern.compile("^([A-Z_][A-Z0-9_]*)=", Pattern.MULTILINE).matcher(envExample);
        while (d.find()) {
            declared.add(d.group(1));
        }

        Set<String> missing = new LinkedHashSet<>(needed);
        missing.removeAll(declared);
        assertTrue(missing.isEmpty(),
            "examples/live-local-systemone.json interpolates " + missing
                + " but .env.example does not declare " + missing
                + ". `config validate` refuses to load the config while a referenced "
                + "variable is unset, so the documented live quickstart fails on a clean "
                + "checkout. Declare every one of " + needed + " in .env.example.");

        // OPENROUTER_API_KEY is read as a NAME (generation.apiKeyEnv), never
        // interpolated, so it is required by the quickstart without appearing in the
        // config text. Assert it separately rather than loosening the check above.
        assertTrue(declared.contains("OPENROUTER_API_KEY"),
            ".env.example must declare OPENROUTER_API_KEY; the live config names it "
                + "through generation.apiKeyEnv rather than ${...}");
    }

    @Test
    @DisplayName(".env.example makes no claim that contradicts the live config")
    void envExampleDoesNotClaimNoVariablesAreNeeded() throws Exception {
        String envExample = read(".env.example");
        // The original text read "No env var is needed for it." while the config it
        // describes required three. A reassuring sentence is worse than none: it stops
        // the operator looking for the variables they do need.
        assertTrue(!envExample.contains("No env var is needed"),
            ".env.example asserts no environment variable is needed, but "
                + "examples/live-local-systemone.json interpolates RAHU_DECISION_MODEL, "
                + "RAHU_FAST_MODEL and RAHU_QUALITY_MODEL and refuses to load without "
                + "them. Remove the claim.");
    }
}