package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A08: filesystem boundary (tools.md) — traversal, symlinks, exclusions. */
class PathBoundaryTest {

    @TempDir
    Path root;

    private static void reject(PathBoundary b, String request) {
        assertThrows(PathBoundary.BoundaryViolation.class,
            () -> b.resolve(request), "must reject: " + request);
    }

    @Test
    @DisplayName("Relative paths within root resolve; the root itself is legal")
    void withinRootResolves() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        var b = new PathBoundary(root);
        assertTrue(b.resolve("a.txt").endsWith("a.txt"));
        assertTrue(b.resolve(".").toString().endsWith(root.getFileName().toString()));
    }

    @Test
    @DisplayName("Absolute paths are rejected")
    void absoluteRejected() {
        var b = new PathBoundary(root);
        reject(b, "/etc/passwd");
        reject(b, "~/secrets");
    }

    @Test
    @DisplayName("Traversal segments are rejected")
    void traversalRejected() throws Exception {
        var b = new PathBoundary(root);
        reject(b, "../outside.txt");
        reject(b, "docs/../../escape.txt");
        reject(b, "..");
    }

    @Test
    @DisplayName("Symlinks anywhere in the requested path are denied")
    void symlinkDenied() throws Exception {
        Path secret = Files.createFile(Path.of(System.getProperty("java.io.tmpdir"),
            "rahu-symlink-target-" + System.nanoTime()));
        Files.writeString(secret, "top secret");
        Path link = root.resolve("innocent.txt");
        Files.createSymbolicLink(link, secret);

        var b = new PathBoundary(root);
        reject(b, "innocent.txt");
        Files.deleteIfExists(link);
        Files.deleteIfExists(secret);
    }

    @Test
    @DisplayName("A symlinked parent directory component is denied during resolution")
    void symlinkedParentDenied() throws Exception {
        Path outsideDir = Files.createTempDirectory("rahu-outside");
        Path inside = root.resolve("docs");
        Files.createDirectories(inside);
        Path link = inside.resolve("jump");
        Files.createSymbolicLink(link, outsideDir);

        var b = new PathBoundary(root);
        reject(b, "docs/jump/anything.txt");
    }

    @Test
    @DisplayName("Default exclusions deny env/key/VCS/build paths even when they exist")
    void exclusionsDenied() throws Exception {
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.writeString(root.resolve(".env.local"), "SECRET=1");
        Files.createDirectories(root.resolve("server.pem.parent"));
        Files.writeString(root.resolve("server.pem"), "key material");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("target"));

        var b = new PathBoundary(root);
        reject(b, ".env");
        reject(b, ".env.local");
        reject(b, "server.pem");
        reject(b, ".git/config");
        reject(b, "target/classes/Main.class");
    }

    @Test
    @DisplayName("Directories resolve but report not-regular; real files report regular")
    void regularFileDiscrimination() throws Exception {
        Files.createDirectories(root.resolve("subdir"));
        Files.writeString(root.resolve("subdir").resolve("f.txt"), "x");
        var b = new PathBoundary(root);
        assertFalse(b.isRegularFile(b.resolve("subdir")),
            "a directory is not a regular file");
        assertTrue(b.isRegularFile(b.resolve("subdir/f.txt")),
            "a text file is regular");
    }
}
