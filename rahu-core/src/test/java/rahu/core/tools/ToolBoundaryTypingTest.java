package rahu.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Audit N2: the tool boundary must degrade to a typed {@link ToolResult}, never let a
 * raw JDK exception escape into the model-facing loop.
 *
 * <p>Every failure mode below is reachable from a single model-authored argument or
 * from an ordinary permission-restricted directory -- no adversary required. Before
 * the fixes, each threw {@code InvalidPathException} or {@code UncheckedIOException}
 * out of the tool, and the exception message echoed the offending path back to the
 * model.
 *
 * <p>The contract asserted here is the CONTRACT, not the historical defect: these
 * tests must keep passing after the throws are removed, and must fail if a future
 * change lets any raw exception escape again.
 */
class ToolBoundaryTypingTest {

    @Test
    void nulByteInPathDegradesToInvalidRatherThanEscaping(@TempDir java.nio.file.Path root)
        throws IOException {
        var tools = new WorkspaceTools(new PathBoundary(root));
        Files.writeString(root.resolve("a.txt"), "hello");

        // Path.of() throws InvalidPathException, which is neither BoundaryViolation
        // nor IOException, so it passed straight through both catch blocks.
        ToolResult result = tools.read("a\u0000.txt", null, null);

        assertEquals(ToolResult.Status.INVALID, result.status(),
            "a NUL byte must be a typed INVALID, not an exception");
        assertFalse(result.safeReason().contains("\u0000"),
            "the reason must not echo the offending value");
        assertFalse(result.safeReason().contains(root.toString()),
            "the reason must not leak the workspace path");
    }

    @Test
    void nulByteInListArgumentDegradesToInvalid(@TempDir java.nio.file.Path root)
        throws IOException {
        var tools = new WorkspaceTools(new PathBoundary(root));
        Files.writeString(root.resolve("a.txt"), "hello");

        ToolResult result = tools.list("sub\u0000dir", 2);
        assertEquals(ToolResult.Status.INVALID, result.status());
    }

    @Test
    void nulByteInSearchArgumentDegradesToInvalid(@TempDir java.nio.file.Path root)
        throws IOException {
        var tools = new WorkspaceTools(new PathBoundary(root));
        Files.writeString(root.resolve("a.txt"), "needle here");

        ToolResult result = tools.search("needle", "sub\u0000dir");
        assertEquals(ToolResult.Status.INVALID, result.status(),
            "the directory argument must be typed, not thrown");
    }

    @Test
    void unreadableSubdirectoryFailsTypedInsteadOfEscapingUnchecked(@TempDir java.nio.file.Path root)
        throws IOException {
        var tools = new WorkspaceTools(new PathBoundary(root));
        var locked = Files.createDirectory(root.resolve("locked"));
        Files.writeString(locked.resolve("secret.txt"), "credential surface");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

        try {
            // Files.walk() wraps directory-read failures in UncheckedIOException, a
            // RuntimeException, which catch (IOException) cannot see. Any locked
            // directory in a workspace used to abort the whole call.
            ToolResult result = tools.list(".", 3);

            assertEquals(ToolResult.Status.FAILED, result.status(),
                "must be a typed FAILED, not an escaped UncheckedIOException");
            assertFalse(result.safeReason().contains("secret"),
                "must not leak the unread entry's name");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void unreadableSubdirectoryInSearchFailsTyped(@TempDir java.nio.file.Path root)
        throws IOException {
        var tools = new WorkspaceTools(new PathBoundary(root));
        var locked = Files.createDirectory(root.resolve("vault"));
        Files.writeString(locked.resolve("creds.txt"), "token=abc123");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

        try {
            ToolResult result = tools.search("abc123", ".");
            assertEquals(ToolResult.Status.FAILED, result.status(),
                "must be a typed FAILED, not an escaped UncheckedIOException");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aSkippedUnreadableFileIsVisibleInTheResult(@TempDir java.nio.file.Path root)
        throws IOException {
        // A search that skipped an entry returned SUCCESS with no marker, so "I found
        // nothing" was indistinguishable from "I could not look". That is absence of
        // evidence reported as evidence of absence.
        var tools = new WorkspaceTools(new PathBoundary(root));
        var secret = root.resolve("secret.txt");
        Files.writeString(secret, "token=abc123");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("---------"));

        try {
            ToolResult result = tools.search("abc123", ".");

            // Skipping an unreadable FILE stays non-fatal by design; the defect was
            // that the skip was invisible. So SUCCESS is acceptable here, but only
            // when the result says the tree was incomplete.
            assertTrue(result.content().toLowerCase().contains("unread")
                    || result.truncated(),
                "an incomplete search must say so: " + result.content());
            assertTrue(result.status() != ToolResult.Status.SUCCESS || result.truncated(),
                "a SUCCESS that skipped an entry must be marked truncated");
        } finally {
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void aFullySearchableTreeReportsNoSkipMarker(@TempDir java.nio.file.Path root)
        throws IOException {
        // The negative control: the marker must not fire on a healthy tree, or it
        // would be noise the model learns to ignore.
        var tools = new WorkspaceTools(new PathBoundary(root));
        Files.writeString(root.resolve("a.txt"), "token=abc123");

        ToolResult result = tools.search("abc123", ".");
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertFalse(result.content().contains("unreadable"), "no marker on a healthy tree");
        assertFalse(result.truncated(), "nothing was truncated");
    }

    @Test
    void directoryExclusionsAreCaseInsensitiveLikeNameExclusions(@TempDir java.nio.file.Path root)
        throws IOException {
        // DEFAULT_EXCLUDED_DIRS is all-lowercase and was compared against the RAW
        // segment name, so .GIT slipped past a protection that appeared to hold.
        Files.createDirectory(root.resolve(".GIT"));
        Files.writeString(root.resolve(".GIT/config"), "[core]");

        var tools = new WorkspaceTools(new PathBoundary(root));
        ToolResult result = tools.list(".", 2);
        assertFalse(result.content().contains(".GIT"),
            "a case-variant of an excluded directory must still be excluded");
    }
}