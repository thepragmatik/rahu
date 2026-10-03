package rahu.cli;

import java.nio.file.Path;

/**
 * Locates a file in the repository when tests run from a Maven module directory.
 *
 * <p>Audit finding AUDIT-2026-10-03-g: this helper existed as a private copy in at
 * least two test classes. A third copy is the same drift this repo has paid for twice
 * in {@code src/main} (two integrity predicates in AUDIT-f, two capture integrities in
 * AUDIT-e), and a copy that resolves the wrong root fails as "fixture missing" rather
 * than as a path bug - a misleading error for whoever reads the failure next.
 */
final class RepoFile {

    private RepoFile() {
    }

    /**
     * Resolves a repo-relative path from wherever surefire started.
     *
     * <p>Surefire runs with {@code user.dir} at the module directory, so
     * {@code examples/offline.json} is one level up. Detected by looking for a
     * directory that only the repo root has, rather than counting {@code ..} hops -
     * a hop count silently breaks if the module is ever nested differently.
     */
    static Path of(String relative) {
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = moduleDir.resolve("docs").toFile().exists()
            ? moduleDir : moduleDir.getParent();
        if (root == null) {
            throw new IllegalStateException("cannot locate the repository root from "
                + moduleDir + "; no parent directory to resolve against");
        }
        return root.resolve(relative);
    }
}
