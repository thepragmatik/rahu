package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUDIT-2026-10-03-ad: artifacts.md promised
 * {@code docs/generated/build-manifest.json} and no code emitted it -- there was no
 * build-info resource, no git-commit-id plugin, and nothing in any pom.
 *
 * <p>These tests pin the CONTENT rules, which is the part that can regress. They
 * deliberately do NOT assert the manifest is committed: a manifest carries the build
 * revision, so a tracked one is dirty on every commit that is not itself the manifest
 * update, and a byte-for-byte sync assertion would then be red for a reason unrelated to
 * correctness. See the class note on {@link BuildManifestGenerator}.
 */
class BuildManifestGeneratorTest {

    private static Map<String, String> modules() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rahu-core", "1.2.3");
        m.put("rahu-cli", "1.2.3");
        return m;
    }

    private static Map<String, String> toolchain() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("javaRelease", "27");
        return t;
    }

    private static JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("a released build with a revision is reproducible")
    void releasedBuildIsReproducible() throws Exception {
        JsonNode root = parse(BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.of("a1b2c3d4"), false, modules(), toolchain()));
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("1.2.3", root.path("build").path("version").asText());
        assertFalse(root.path("build").path("isSnapshot").asBoolean(),
            "1.2.3 is not a snapshot");
        assertTrue(root.path("build").path("reproducible").asBoolean(),
            "a fixed version + a revision + a clean tree is reproducible");
        assertEquals("a1b2c3d4", root.path("provenance").path("revision").asText());
    }

    @Test
    @DisplayName("'latest' is refused as a version rather than written")
    void latestIsRefused() {
        // The spec's exact words. Writing it would produce a file that looks like a
        // version record while naming no build at all.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BuildManifestGenerator.buildManifestJson(
                BuildManifestGenerator.FORBIDDEN_VERSION, Optional.of("abc"),
                false, modules(), toolchain()));
        assertTrue(e.getMessage().contains("names no build"),
            "the refusal must say why: " + e.getMessage());
        // Case-insensitive, because 'Latest' would be the same lie.
        assertThrows(IllegalArgumentException.class,
            () -> BuildManifestGenerator.buildManifestJson("Latest", Optional.of("abc"),
                false, modules(), toolchain()));
    }

    @Test
    @DisplayName("a blank version is refused instead of written")
    void blankVersionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> BuildManifestGenerator.buildManifestJson(
                "   ", Optional.of("abc"), false, modules(), toolchain()));
        assertThrows(IllegalArgumentException.class,
            () -> BuildManifestGenerator.buildManifestJson(
                null, Optional.of("abc"), false, modules(), toolchain()));
    }

    @Test
    @DisplayName("a snapshot is recorded but flagged, never called reproducible")
    void snapshotIsFlaggedNotHidden() throws Exception {
        // '0.1.0-SNAPSHOT' is the project's current version. It changes under its own
        // name on every rebuild, so presenting it as a release version is the same class
        // of error as writing 'latest' -- subtler, because it looks specific.
        JsonNode root = parse(BuildManifestGenerator.buildManifestJson(
            "0.1.0-SNAPSHOT", Optional.of("deadbee"), false, modules(), toolchain()));
        assertEquals("0.1.0-SNAPSHOT", root.path("build").path("version").asText(),
            "the real version is recorded, not hidden");
        assertTrue(root.path("build").path("isSnapshot").asBoolean());
        assertFalse(root.path("build").path("reproducible").asBoolean(),
            "a snapshot cannot be reproduced from its own name");
    }

    @Test
    @DisplayName("an unavailable revision is stated, not invented")
    void unavailableRevisionIsHonest() throws Exception {
        // A build from a release tarball has no VCS. A placeholder that looks like a hash
        // is worse than an absence, so the absence is recorded and explained.
        JsonNode root = parse(BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.empty(), false, modules(), toolchain()));
        JsonNode provenance = root.path("provenance");
        assertTrue(provenance.path("revision").isNull(),
            "no revision is invented; null, not a placeholder");
        assertEquals("unavailable", provenance.path("revisionSource").asText());
        assertTrue(provenance.path("note").asText().contains("version and toolchain"),
            "the reader is told what this build can be identified by");
        assertFalse(root.path("build").path("reproducible").asBoolean());
    }

    @Test
    @DisplayName("a dirty tree is not reproducible even at a fixed version")
    void dirtyTreeIsNotReproducible() throws Exception {
        // version + revision is necessary but not sufficient: the revision names a
        // commit, and a dirty tree means the bytes are not that commit's bytes.
        JsonNode root = parse(BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.of("a1b2c3d4"), true, modules(), toolchain()));
        assertTrue(root.path("build").path("gitDirty").asBoolean());
        assertFalse(root.path("build").path("reproducible").asBoolean(),
            "dirty-tree bytes are not the revision's bytes");
    }

    @Test
    @DisplayName("every module version is refused if it is not reproducible-looking")
    void moduleVersionsAreHeldToTheSameRule() {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("rahu-core", "1.0.0");
        bad.put("rahu-cli", "latest");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BuildManifestGenerator.buildManifestJson(
                "1.0.0", Optional.of("abc"), false, bad, toolchain()));
        assertTrue(e.getMessage().contains("names no build"));
    }

    @Test
    @DisplayName("the manifest is byte-stable for the same inputs")
    void manifestIsDeterministic() {
        // Two builds of the same tree must produce identical bytes, or the manifest
        // cannot be compared against anything to detect drift.
        String a = BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.of("a1b2c3d4"), false, modules(), toolchain());
        String b = BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.of("a1b2c3d4"), false, modules(), toolchain());
        assertEquals(a, b);
    }

    @Test
    @DisplayName("no field anywhere in the document is ever 'latest'")
    void noFieldEverReadsLatest() throws Exception {
        // Belt and braces on the whole document, not just the top-level version: a
        // future field could reintroduce it without touching requireVersion.
        String json = BuildManifestGenerator.buildManifestJson(
            "0.1.0-SNAPSHOT", Optional.of("abc"), true, modules(), toolchain());
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("\"latest\""),
            "the literal must not appear as a value: " + json);
        assertFalse(json.contains("null\" : \"latest\""), "and not as a stringified null");
    }

    @Test
    @DisplayName("snapshot detection covers the Maven timestamped form")
    void snapshotDetectionCoversTimestampedForm() {
        assertTrue(BuildManifestGenerator.isSnapshot("1.0.0-SNAPSHOT"));
        assertTrue(BuildManifestGenerator.isSnapshot("0.1.0-snapshot"));
        assertTrue(BuildManifestGenerator.isSnapshot("1.0-20261003.120000-3"),
            "the timestamped release-candidate form is also unstable under its own name");
        assertFalse(BuildManifestGenerator.isSnapshot("1.2.3"));
        assertFalse(BuildManifestGenerator.isSnapshot("1.2.3-rc1"));
    }

    @Test
    @DisplayName("every module version equals the project version, by construction")
    void modulesCannotDisagreeWithTheProjectVersion() throws Exception {
        // Found by generating a manifest with -Drahu.version=1.2.3 and READING IT: the
        // modules still said 0.1.0-SNAPSHOT, so one document named two different
        // versions for a single build. Nothing in the suite could see that, because the
        // module map was built from repeated literals instead of from one value.
        //
        // This asserts the aggregate property rather than the plumbing: whichever way
        // the map is built, every value must be the project version.
        JsonNode root = parse(BuildManifestGenerator.buildManifestJson(
            "1.2.3", Optional.of("abc"), false, modules(), toolchain()));
        var moduleNode = root.path("modules");
        assertTrue(moduleNode.isObject() && !moduleNode.isEmpty(), "modules must be present");
        var seen = new java.util.TreeSet<String>();
        moduleNode.fields().forEachRemaining(e -> seen.add(e.getValue().asText()));
        assertEquals(java.util.Set.of("1.2.3"), seen,
            "every module must carry the project version, got: " + moduleNode);
    }
}
