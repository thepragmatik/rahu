package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT-2026-10-03-d: the exit-code vocabulary of cli.md:42, asserted against the
 * constants rather than against whatever the drivers happen to return.
 *
 * <p>Three privacy blocks returned {@code 4}, which cli.md assigns to
 * <em>provider/decision/tool failure</em> - a materially different claim. It went
 * unnoticed because both drivers agreed with each other, and because the offline
 * test I wrote in AUDIT-2026-10-03-b asserted "exit 4, as the live driver does".
 * A test that pins a value to its sibling rather than to the spec will happily
 * certify two identical bugs, which is precisely what happened.
 *
 * <p>So this test reads the two drivers' source and checks that each numeric
 * return corresponds to a {@link ExitCode} constant of the right meaning. It is a
 * source-reading test on purpose: the alternative - asserting exit codes through
 * the drivers - requires provoking six distinct failure modes, two of which
 * (provider outage, routing terminal) cannot be provoked offline at all, which
 * is how the original gap survived in the first place.
 */
class ExitCodeConformanceTest {

    @TempDir
    Path root;

    private static final Path LIVE =
        Path.of("src/main/java/rahu/cli/live/LiveTurnDriver.java");
    private static final Path OFFLINE =
        Path.of("src/main/java/rahu/cli/live/OfflineTurnDriver.java");

    /** Both drivers, as the CLI builds them: the test runs from rahu-cli/. */
    private String sourceOf(Path moduleRelative) throws Exception {
        Path module = Path.of(System.getProperty("user.dir"));
        if (!Files.exists(module.resolve(moduleRelative))) {
            // Fall back to walking up, so the test does not depend on the cwd the
            // surefire fork happened to use.
            Path dir = module.toAbsolutePath();
            while (dir != null && !Files.exists(dir.resolve(moduleRelative))) {
                dir = dir.getParent();
            }
            if (dir == null) {
                throw new IllegalStateException("cannot locate " + moduleRelative);
            }
            module = dir;
        }
        return Files.readString(module.resolve(moduleRelative));
    }

    @Test
    @DisplayName("cli.md:42 - the constant values ARE the spec's vocabulary")
    void constantsMatchTheSpec() {
        // If cli.md:42 is ever amended, this is the line that must change with it.
        // Quoting it here means the test fails loudly rather than the spec and the
        // code drifting apart unnoticed.
        assertEquals(0, ExitCode.OK);
        assertEquals(2, ExitCode.INVALID_INPUT);
        assertEquals(3, ExitCode.NO_ROUTE_OR_LIMIT_OR_PRIVACY);
        assertEquals(4, ExitCode.PROVIDER_DECISION_OR_TOOL_FAILURE);
        assertEquals(5, ExitCode.TRACE_INTEGRITY_FAILURE);
        assertEquals(130, ExitCode.INTERRUPTED);

        // The grouped case. cli.md puts privacy blocked with no-feasible-route and
        // limit-reached because all three mean "nothing was sent, and retrying
        // unchanged will not help". A supervisor deciding whether to retry relies
        // on exactly this grouping.
        assertEquals(ExitCode.NO_ROUTE_OR_LIMIT_OR_PRIVACY, ExitCode.PRIVACY_BLOCKED);
    }

    @Test
    @DisplayName("no driver returns a bare numeric exit code")
    void noBareNumericReturns() throws Exception {
        for (Path driver : List.of(LIVE, OFFLINE)) {
            String src = sourceOf(driver);
            for (String line : src.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                assertTrue(!trimmed.matches("return \\d+;"),
                    driver.getFileName() + " returns a bare literal: " + trimmed
                        + " - use an ExitCode constant so cli.md:42 stays checkable");
            }
        }
    }

    @Test
    @DisplayName("every privacy block returns the privacy code, not the provider code")
    void privacyBlocksUseThePrivacyCode() throws Exception {
        for (Path driver : List.of(LIVE, OFFLINE)) {
            String src = sourceOf(driver);
            String[] lines = src.split("\n");
            int blocks = 0;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].strip();
                boolean isBlock = trimmed.contains("privacy blocked")
                    || (trimmed.contains("admitted instanceof PrivacyGate.Decision.Blocked")
                        && i + 3 < lines.length);
                if (!isBlock || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                // Walk forward to the return that closes this block.
                for (int j = i; j < Math.min(i + 14, lines.length); j++) {
                    if (lines[j].strip().startsWith("return ")) {
                        String ret = lines[j].strip();
                        blocks++;
                        assertTrue(ret.contains("ExitCode.PRIVACY_BLOCKED"),
                            driver.getFileName() + " privacy block at line " + (j + 1)
                                + " returns " + ret + " - cli.md:42 groups privacy blocked"
                                + " with no-feasible-route/limit (3), not provider failure (4)");
                        break;
                    }
                }
            }
            assertTrue(blocks > 0, "found no privacy block in " + driver.getFileName()
                + " - if the driver was refactored, update this test rather than"
                + " letting it pass vacuously");
        }
    }

    @Test
    @DisplayName("the trace-failure code is distinct from the provider code (A16)")
    void traceFailureIsDistinctFromProviderFailure() {
        // A16 needs its own code so a caller can tell "the answer may be wrong"
        // from "the answer exists but its evidence does not". Collapsing them would
        // make an unauditable run indistinguishable from a failed one.
        assertEquals(5, ExitCode.TRACE_INTEGRITY_FAILURE);
        assertTrue(ExitCode.TRACE_INTEGRITY_FAILURE
            != ExitCode.PROVIDER_DECISION_OR_TOOL_FAILURE);
    }
}