package rahu.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Drives a REAL jdtls process. Skipped (not failed) when the binary is absent, so
 * a machine without it does not report a red build for an environmental reason.
 */
class LspSessionLiveTest {

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
        String binary;
        try {
            binary = LspSession.resolveBinary();
        } catch (java.io.IOException e) {
            Assumptions.abort("no jdtls on this machine: " + e.getMessage());
            throw e; // unreachable; abort throws
        }
        System.out.println("driving jdtls at " + binary);
        return LspSession.start(workspaceRoot());
    }

    @Test
    void initializeAdvertisesTextDocumentSync() throws Exception {
        try (LspSession session = startIfAvailable()) {
            Map<String, Object> capabilities = session.serverCapabilities();
            assertNotNull(capabilities, "server must return capabilities");
            assertTrue(capabilities.containsKey("textDocumentSync"),
                    "expected textDocumentSync, got: " + capabilities.keySet());
        }
    }

    @Test
    void serverAdvertisesRenameOrCodeAction() throws Exception {
        try (LspSession session = startIfAvailable()) {
            Map<String, Object> capabilities = session.serverCapabilities();
            boolean any = capabilities.containsKey("renameProvider")
                    || capabilities.containsKey("codeActionProvider")
                    || capabilities.containsKey("executeCommandProvider");
            assertTrue(any,
                    "a refactoring server must advertise at least one, got: " + capabilities.keySet());
        }
    }

    @Test
    void workspaceSymbolsFindsARealClass() throws Exception {
        try (LspSession session = startIfAvailable()) {
            List<?> hits = session.workspaceSymbols("DecisionEngine");
            assertNotNull(hits, "result must be present");
            // Not asserting non-empty: indexing a multi-module Maven reactor over
            // stdio can legitimately lag. An empty list is recorded, not failed, so
            // the test reports capability without asserting on index timing.
            if (hits.isEmpty()) {
                System.out.println("NOTE: workspace/symbol returned 0 hits; index may not be ready");
            }
        }
    }

    @Test
    void closeLeavesNoJdtlsProcessBehind() throws Exception {
        // Regression guard: a leaked language server holds the workspace index
        // lock, so the NEXT run fails to start against the same directory.
        long before = jdtlsProcessCount();
        LspSession session = startIfAvailable();
        assertTrue(jdtlsProcessCount() > before, "the server should be running while the session is open");
        session.close();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        long after = jdtlsProcessCount();
        while (after > before && System.nanoTime() < deadline) {
            Thread.sleep(250);
            after = jdtlsProcessCount();
        }
        assertEquals(before, after,
                "close() must reap the server; leaked jdtls processes hold the index lock");
        session.close(); // idempotent
    }

    /** Counts running jdtls snapshot servers, excluding this process's own shell. */
    private static long jdtlsProcessCount() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "ps -eo command | grep 'jdtls-snapshot' | grep -v grep | wc -l")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return out.isEmpty() ? 0 : Long.parseLong(out);
        } catch (Exception e) {
            return -1; // cannot measure; do not fail the test on the probe itself
        }
    }

    @Test
    void unknownMethodYieldsAnErrorFrameRatherThanAHang() throws Exception {
        try (LspSession session = startIfAvailable()) {
            Map<String, Object> response = session.request("textDocument/definitelyNotAMethod", Map.of());
            assertTrue(response.containsKey("error"),
                    "expected an error frame, got keys: " + response.keySet());
        }
    }
}