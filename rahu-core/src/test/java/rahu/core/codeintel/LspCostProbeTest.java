package rahu.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Task 4: the measurement gate. Answers "is jdtls cost-effective for this workload"
 * with a number instead of an opinion.
 *
 * <p>Prints, never asserts on, timing: the numbers are the deliverable, and a hard
 * threshold would make the suite fail on a slow machine rather than report a fact.
 */
class LspCostProbeTest {

    /** Surefire runs from the module dir; walk up to the repo root marker. */
    private static Path workspaceRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("rahu-core"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("repo root not found");
    }

    private static LspSession startIfAvailable() throws Exception {
        try {
            return LspSession.start(workspaceRoot());
        } catch (java.io.IOException e) {
            Assumptions.abort("no jdtls: " + e.getMessage());
            throw e;
        }
    }

    @Test
    void measureStartupAndQueryLatency() throws Exception {
        for (int run = 1; run <= 3; run++) {
            long t0 = System.nanoTime();
            try (LspSession session = startIfAvailable()) {
                long started = System.nanoTime();
                Map<String, Object> caps = session.serverCapabilities();
                long capsAt = System.nanoTime();
                session.workspaceSymbols("DecisionEngine");
                long symbolsAt = System.nanoTime();

                System.out.printf(
                        "LSP-PROBE run=%d startup_ms=%d caps_ms=%d symbols_ms=%d capabilities=%d%n",
                        run,
                        (started - t0) / 1_000_000,
                        (capsAt - started) / 1_000_000,
                        (symbolsAt - capsAt) / 1_000_000,
                        caps.size());
            }
            long closed = System.nanoTime();
            System.out.printf("LSP-PROBE run=%d total_ms=%d%n", run, (closed - t0) / 1_000_000);
        }
    }

    @Test
    void reportBinaryAndIndexState() throws Exception {
        String binary = LspSession.resolveBinary();
        System.out.println("LSP-PROBE binary=" + binary);
        File plugins = new File(binary).getParentFile();
        for (int up = 0; up < 4 && plugins != null; up++, plugins = plugins.getParentFile()) {
            File dir = new File(plugins, "plugins");
            if (dir.isDirectory()) {
                System.out.println("LSP-PROBE plugins=" + dir + " count="
                        + String.valueOf(dir.list() == null ? 0 : dir.list().length));
                break;
            }
        }
        assertTrue(Files.isExecutable(Path.of(binary)), "resolved binary must be executable");
    }
}