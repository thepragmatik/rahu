package rahu.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generates {@code docs/generated/build-manifest.json} (artifacts.md: "Record build
 * versions/revisions in {@code docs/generated/build-manifest.json} when building. Do
 * not write 'latest' as a reproducible version.").
 *
 * <p>WHY THIS IS NOT A COMMITTED ARTIFACT. The sibling {@code config.schema.json} is
 * committed and {@link SchemaGeneratorTest} asserts it byte-for-byte, which is the right
 * trade for a document that only changes when someone edits a literal. A manifest also
 * carries the build REVISION, so committing it guarantees a dirty working tree and a
 * failing sync assertion on every commit that is not itself the manifest update -- a test
 * that is red for a reason unrelated to correctness. {@code docs/generated/} is already
 * in {@code .gitignore} for exactly this class of file ({@code model-profiles.json} is
 * ignored for the same reason). So this is written at build time and never tracked; the
 * test pins the CONTENT rules instead, which is the part that can actually regress.
 *
 * <p>WHAT "REPRODUCIBLE" MEANS HERE. Two substitutions would both look like a version and
 * be useless for reproduction, so both are refused rather than written:
 * <ul>
 *   <li>the literal {@code latest}, which names no build at all;</li>
 *   <li>a SNAPSHOT version, which changes under its own name on every rebuild, so
 *       {@code 0.1.0-SNAPSHOT} plus a revision is the only thing that pins a build --
 *       it is recorded, but flagged so nobody reads it as a release version.</li>
 * </ul>
 * The revision is recorded only when supplied. This generator never shells out to git:
 * a build from a release tarball has no VCS, and a build that failed to find one must say
 * so rather than invent a placeholder that reads like a hash.
 */
public final class BuildManifestGenerator {

    private BuildManifestGenerator() {
    }

    /** Schema version of the manifest document itself, so a reader can pin its parser. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * The one string that must never appear as a version. Kept as a named constant so the
     * test and the generator cannot drift into checking different spellings.
     */
    public static final String FORBIDDEN_VERSION = "latest";

    /**
     * Builds the manifest document.
     *
     * @param projectVersion the Maven {@code project.version}, never null or blank
     * @param revision the VCS revision this build came from, or empty when unavailable
     * @param dirty whether the working tree had uncommitted changes at build time
     * @param modules module artifactId to version, in the order given
     * @param toolchain build toolchain facts that affect reproducibility
     */
    public static String buildManifestJson(
            String projectVersion,
            Optional<String> revision,
            boolean dirty,
            Map<String, String> modules,
            Map<String, String> toolchain) {

        String version = requireVersion(projectVersion);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);

        // project version + its reproducibility status. Recorded first because it is the
        // field a reader trusts most and the one most often wrong.
        ObjectNode build = root.putObject("build");
        build.put("version", version);
        build.put("isSnapshot", isSnapshot(version));
        build.put("reproducible", !isSnapshot(version) && revision.isPresent() && !dirty);
        build.put("gitDirty", dirty);

        // A revision is only ever a real revision. Absent is a legitimate, honest state
        // (tarball build); a placeholder is not.
        ObjectNode provenance = root.putObject("provenance");
        if (revision.isPresent()) {
            provenance.put("revision", revision.get());
            provenance.put("revisionSource", "supplied");
        } else {
            provenance.put("revision", (String) null);
            provenance.put("revisionSource", "unavailable");
            provenance.put("note",
                "no VCS revision was supplied to the generator; this build is identified by "
                    + "version and toolchain only");
        }

        ObjectNode modulesNode = root.putObject("modules");
        modules.forEach((artifactId, moduleVersion) -> {
            requireVersion(moduleVersion);
            modulesNode.put(artifactId, moduleVersion);
        });

        root.set("toolchain", mapper.valueToTree(toolchain));

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("build manifest is not serialisable", e);
        }
    }

    /** A blank or absent version is a build bug, not a value to write. */
    private static String requireVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must be present");
        }
        String trimmed = version.trim();
        if (FORBIDDEN_VERSION.equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException(
                "'" + FORBIDDEN_VERSION + "' names no build and is not a reproducible version");
        }
        return trimmed;
    }

    /** Maven snapshot convention: a trailing {@code -SNAPSHOT} or a bare timestamped form. */
    static boolean isSnapshot(String version) {
        String v = version.trim().toUpperCase(java.util.Locale.ROOT);
        return v.endsWith("-SNAPSHOT") || v.endsWith("SNAPSHOT") || v.matches(".*\\d{8}\\.\\d{6}-\\d+");
    }

    /** Writes {@code docs/generated/build-manifest.json} from the repo root. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path generated = root.resolve("docs/generated");
        Files.createDirectories(generated);
        Path manifest = generated.resolve("build-manifest.json");

        // Every module shares the reactor's project.version. Deriving them from one
        // value instead of repeating literals keeps the document from contradicting
        // itself: with a version that disagrees between build.version and modules, the
        // manifest names two different versions for the same build.
        String projectVersion = System.getProperty("rahu.version", "0.1.0-SNAPSHOT");
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("rahu-core", projectVersion);
        modules.put("rahu-openrouter", projectVersion);
        modules.put("rahu-systemone", projectVersion);
        modules.put("rahu-cli", projectVersion);

        Map<String, String> toolchain = new LinkedHashMap<>();
        toolchain.put("javaRelease", System.getProperty("java.specification.version", "unknown"));
        toolchain.put("mavenBuild", "maven");

        Files.writeString(manifest, buildManifestJson(
            projectVersion,
            Optional.ofNullable(System.getProperty("rahu.revision")),
            Boolean.parseBoolean(System.getProperty("rahu.dirty", "false")),
            modules,
            toolchain), StandardCharsets.UTF_8);
        System.out.println("wrote " + manifest);
    }
}
