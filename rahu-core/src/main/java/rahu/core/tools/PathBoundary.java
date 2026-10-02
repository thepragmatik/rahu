package rahu.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Filesystem boundary for read-only tools (tools.md): root containment,
 * symlink denial anywhere in the path, and default exclusions. Reading locally
 * is separate from disclosure — privacy admission happens later, per view.
 */
public final class PathBoundary {

    /** Typed rejection; the safe reason never echoes the offending value. */
    public static final class BoundaryViolation extends SecurityException {

        public final String reason;

        public BoundaryViolation(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private static final List<String> DEFAULT_EXCLUDED_NAMES = List.of(
        ".env", ".env.local", ".env.development", ".env.production", ".env.test");
    private static final List<String> DEFAULT_EXCLUDED_SUFFIXES = List.of(
        ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");
    private static final List<String> DEFAULT_EXCLUDED_DIRS = List.of(
        ".git", ".hg", ".svn", "target", "build", "out", "node_modules", ".idea", ".vscode");

    private final Path root;
    private final List<String> excludedNames;
    private final List<String> excludedSuffixes;
    private final List<String> excludedDirs;

    public PathBoundary(Path root) {
        this(root, DEFAULT_EXCLUDED_NAMES, DEFAULT_EXCLUDED_SUFFIXES, DEFAULT_EXCLUDED_DIRS);
    }

    /**
     * A boundary with operator-supplied exclusions ADDED to the credential-like name
     * defaults. {@code tools.exclusions} from the config lands here.
     *
     * <p>Operator names are matched case-insensitively alongside the defaults, because
     * {@code checkExclusions} lowercases the segment for name matching and a
     * case-sensitive exclusion list is a trap on a case-preserving filesystem.
     */
    public PathBoundary(Path root, List<String> operatorExclusions) {
        this(root, mergeNames(DEFAULT_EXCLUDED_NAMES, operatorExclusions),
            DEFAULT_EXCLUDED_SUFFIXES, DEFAULT_EXCLUDED_DIRS);
    }

    private static List<String> mergeNames(List<String> defaults, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return defaults;
        }
        var merged = new java.util.LinkedHashSet<String>(defaults);
        for (String name : extra) {
            if (name != null && !name.isBlank()) {
                merged.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(merged);
    }

    public PathBoundary(Path root, List<String> excludedNames, List<String> excludedSuffixes,
        List<String> excludedDirs) {
        Objects.requireNonNull(root, "root");
        this.root = root.toAbsolutePath().normalize();
        this.excludedNames = List.copyOf(excludedNames);
        this.excludedSuffixes = List.copyOf(excludedSuffixes);
        this.excludedDirs = List.copyOf(excludedDirs);
    }

    /**
     * Resolves a workspace-relative request and enforces every boundary rule.
     * Returns the normalized absolute path on success.
     */
    public Path resolve(String request) {
        if (request == null || request.isBlank()) {
            throw new BoundaryViolation("empty path request");
        }
        Path candidate;
        try {
            candidate = Path.of(request);
        } catch (InvalidPathException e) {
            // A NUL byte or lone surrogate reaches here from a single model-authored
            // argument. InvalidPathException is neither a BoundaryViolation nor an
            // IOException, so it would otherwise escape every catch block in the tool
            // layer. The reason never echoes the offending value.
            throw new BoundaryViolation("path contains an illegal character");
        }
        if (candidate.isAbsolute()) {
            throw new BoundaryViolation("absolute paths are not permitted");
        }
        if (request.startsWith("~")) {
            throw new BoundaryViolation("home-expansion paths are not permitted");
        }
        for (Path segment : candidate) {
            String name = segment.toString();
            if (name.equals("..")) {
                throw new BoundaryViolation("path traversal is not permitted");
            }
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new BoundaryViolation("path escapes the workspace root");
        }
        checkExclusions(resolved);
        checkNoSymlinks(resolved);
        return resolved;
    }

    /** True when the resolved path is a regular file (not a directory/device). */
    public boolean isRegularFile(Path resolved) {
        return Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    /** Root accessor for tools that enumerate from the workspace root. */
    public Path root() {
        return root;
    }

    private void checkExclusions(Path resolved) {
        for (Path segment : root.relativize(resolved)) {
            String name = segment.toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (excludedNames.contains(lower)) {
                throw new BoundaryViolation("excluded path component (credential-like name)");
            }
            // Directory exclusions match case-insensitively: a case-sensitive list is
            // a bypass on any filesystem that preserves case, so .GIT or Node_Modules
            // would read where .git and node_modules would not (audit F-5).
            if (excludedDirs.contains(lower)) {
                throw new BoundaryViolation("excluded directory component (VCS/build output)");
            }
            for (String suffix : excludedSuffixes) {
                if (lower.endsWith(suffix)) {
                    throw new BoundaryViolation("excluded file type (key material)");
                }
            }
        }
    }

    /** Denies symlinks anywhere in the path, root to leaf, without following them. */
    private void checkNoSymlinks(Path resolved) {
        Path current = resolved;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) {
                throw new BoundaryViolation("symlinks are not permitted in tool paths");
            }
            current = current.getParent();
        }
        try {
            Path real = resolved.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw new BoundaryViolation("resolved path escapes the workspace root");
            }
        } catch (IOException e) {
            // target may not exist yet (list/search of a fresh dir is not a read);
            // the NOFOLLOW checks above already ran for existing components.
        }
    }
}
