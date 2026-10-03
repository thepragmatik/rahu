package rahu.cli.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT-2026-10-03-ag, A30/G10: "requirement-to-test evidence manifest", plus G10's own
 * warning that "code presence alone is not evidence".
 *
 * <p>Before this class the repository had 34 acceptance IDs and a large, genuinely good
 * test suite, and no machine-checked link between the two. Nothing stopped an acceptance
 * row from being quietly unimplemented while the build stayed green, and nothing stopped a
 * review from citing a test that no longer existed. Both are invisible to {@code mvn
 * verify} - they are documentation defects, and documentation defects do not fail builds
 * on their own.
 *
 * <p>So the manifest is GENERATED FROM THE TESTS ({@link EvidenceManifest}) and these
 * tests check the generation is honest in both directions.
 */
class EvidenceManifestTest {

    @Test
    @DisplayName("every acceptance ID has method-level evidence, or a recorded exemption")
    void everyRequirementHasEvidenceOrAnExemption() throws Exception {
        List<String> ids = EvidenceManifest.allIds(repoRoot());
        assertEquals(34, ids.size(),
            "acceptance.md should still declare exactly 34 requirements. A count change means "
                + "the spec moved, and the manifest must be regenerated deliberately rather "
                + "than silently following along");

        Map<String, List<String>> evidence = EvidenceManifest.evidence(repoRoot());
        Map<String, String> exemptions = EvidenceManifest.exemptions();

        List<String> gaps = new ArrayList<>();
        for (String id : ids) {
            if (evidence.getOrDefault(id, List.of()).isEmpty()
                && !exemptions.containsKey(id)) {
                gaps.add(id);
            }
        }
        assertEquals(List.of(), gaps,
            "these acceptance IDs have neither test evidence nor a recorded exemption. G10 "
                + "requires a requirement-to-test evidence manifest, and an unevidenced "
                + "requirement is precisely what that manifest exists to make visible: " + gaps);
    }

    @Test
    @DisplayName("every method the manifest CITES exists, in the emitted manifest itself")
    void everyCitedMethodExists() throws Exception {
        Path root = repoRoot();

        // Read the citations out of the GENERATED document, not out of evidence().
        //
        // Mutation V1 caught the difference: I had the generator emit a fabricated citation
        // and the test passed, because evidence() and generate() are different code paths and
        // the test was inspecting the one that had no fabrication in it. A phantom check that
        // validates a recomputation instead of the artifact is not checking the artifact -
        // and the artifact is the thing a reviewer reads.
        JsonNode manifest = new ObjectMapper().readTree(EvidenceManifest.generate(root));
        Map<String, List<String>> evidence = new java.util.LinkedHashMap<>();
        for (JsonNode row : manifest.get("requirements")) {
            List<String> cites = new ArrayList<>();
            row.path("tests").forEach(t2 -> cites.add(t2.asText()));
            evidence.put(row.get("id").asText(), cites);
        }

        List<String> phantom = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : evidence.entrySet()) {
            for (String citation : e.getValue()) {
                int hash = citation.indexOf('#');
                String className = citation.substring(0, hash);
                String method = citation.substring(hash + 1);
                if (method.equals("<class>")) {
                    // Class-javadoc citation: the class must still exist, but there is no
                    // single method to name, so only the class is checked.
                    if (declaredMethods(root, className).isEmpty()) {
                        phantom.add(e.getKey() + " -> " + citation + " (no such test class)");
                    }
                } else if (!declaredMethods(root, className).contains(method)) {
                    phantom.add(e.getKey() + " -> " + citation);
                }
            }
        }
        assertEquals(List.of(), phantom,
            "these citations name a method absent from the source. A manifest pointing at a "
                + "deleted or renamed test is worse than no manifest, because it converts a "
                + "missing test into an apparent passing one: " + phantom);
    }

    @Test
    @DisplayName("a per-method citation outranks a class-javadoc citation")
    void methodLevelBeatsClassLevel() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
            EvidenceManifest.generate(repoRoot()));
        int strong = 0;
        java.util.List<String> weakIds = new java.util.ArrayList<>();
        for (JsonNode row : root.get("requirements")) {
            String status = row.get("status").asText();
            if ("evidenced".equals(status)) {
                strong++;
                // A strong row must cite at least one real method, not only class markers.
                assertTrue(
                    java.util.stream.StreamSupport
                        .stream(row.get("tests").spliterator(), false)
                        .anyMatch(t2 -> !t2.asText().endsWith("#<class>")),
                    row.get("id").asText() + " is labelled per-method but cites only classes");
            } else if ("evidencedByClassDoc".equals(status)) {
                weakIds.add(row.get("id").asText());
            }
        }
        // 28 carry a named per-method citation. The remaining 3 evidenced rows are
        // class-level only - A02 (SystemOneAdapterTest), A27 (NoProgressDetectorTest) and
        // A31 (DocumentedClaimsTest) all name their ID in the class javadoc but no individual
        // test documents it. Those are the honest outliers, so they are pinned by ID rather
        // than by a total that would hide which ones they are.
        assertEquals(28, strong,
            "28 requirements carry at least one named per-method citation");
        assertEquals(List.of("A02", "A27", "A31"), weakIds,
            "the class-level-only rows are A02, A27 and A31. If this list changes, the change "
                + "is meaningful - a test gained a per-method citation, or one lost the only "
                + "evidence it had - so it must be looked at, not absorbed");
    }

    @Test
    @DisplayName("the manifest reports gaps instead of implying full coverage")
    void theManifestIsHonestAboutGaps(@TempDir Path tmp) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(EvidenceManifest.generate(repoRoot()));

        JsonNode summary = root.get("summary");
        assertEquals(34, summary.get("total").asInt());
        assertEquals(0, summary.get("gaps").asInt(),
            "an ID with neither evidence nor exemption is a silent coverage hole; it must be "
                + "surfaced as a gap rather than omitted, so the count can never read as full "
                + "coverage when it is not");
        assertEquals(
            EvidenceManifest.exemptions().size(),
            summary.get("exempted").asInt(),
            "the exemptions are the named, reasoned gaps (A17 needs paired live eval runs, A20 "
                + "is a process clause, A30 is this gate). Their count must match the declared "
                + "set, or a row was dropped without anyone noticing");
        assertEquals(31, summary.get("evidenced").asInt(),
            "31 of 34 requirements carry method-level evidence");

        // Every requirement row must say which of the three states it is in.
        for (JsonNode row : root.get("requirements")) {
            String status = row.get("status").asText();
            assertTrue(List.of("evidenced", "evidencedByClassDoc", "exempted", "GAP")
                    .contains(status),
                "unknown status " + status + " for " + row.get("id").asText());
            if ("evidencedByClassDoc".equals(status)) {
                // A weaker claim, recorded as weaker: the ID appears in the class javadoc, so
                // the class as a whole carries the evidence, not one named test. Saying so is
                // the point - presenting this as per-method evidence would overstate it.
                assertTrue(row.hasNonNull("caveat"),
                    "a class-level citation must carry its caveat: "
                        + row.get("id").asText());
                assertTrue(row.get("caveat").asText().contains("class javadoc"),
                    row.get("id").asText());
            }
            if (status.startsWith("evidenced")) {
                assertTrue(row.get("tests").size() > 0,
                    "an evidenced requirement with no cited tests is self-contradictory: "
                        + row.get("id").asText());
            } else {
                assertTrue(row.hasNonNull("reason"),
                    "an unevidenced requirement must state why: "
                        + row.get("id").asText());
            }
        }
    }

    @Test
    @DisplayName("the spec and the manifest never disagree about how many requirements exist")
    void manifestRowCountMatchesTheSpec() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
            EvidenceManifest.generate(repoRoot()));
        assertEquals(EvidenceManifest.allIds(repoRoot()).size(),
            root.get("requirements").size(),
            "a requirement row was dropped between parsing acceptance.md and emitting the "
                + "manifest; the two must be the same set");
    }

    @Test
    @DisplayName("generation is deterministic, so the manifest is reviewable evidence")
    void generationIsDeterministic() throws Exception {
        String first = EvidenceManifest.generate(repoRoot());
        String second = EvidenceManifest.generate(repoRoot());
        assertEquals(first, second,
            "the manifest is release evidence, so two runs on an unchanged tree must be "
                + "byte-identical; otherwise a diff means nothing and reviewers cannot trust it");
        assertFalse(first.isBlank());
        assertTrue(first.endsWith("\n"));
    }

    @Test
    @DisplayName("the manifest is written where docs/generated is ignored, not tracked")
    void theManifestIsNotATrackedArtifact() throws Exception {
        String ignore = Files.readString(repoRoot().resolve(".gitignore"),
            StandardCharsets.UTF_8);
        assertTrue(ignore.contains("docs/generated/"),
            "the manifest is regenerated on every test edit, so docs/generated/ must stay "
                + "gitignored; committing it guarantees a dirty tree and a failing sync "
                + "assertion on every commit that is not itself the manifest update");
    }

    // ------------------------------------------------------------- helpers

    /**
     * Walks up from the module directory to the repository root, matching
     * {@link DocumentedClaimsTest} rather than introducing a second convention.
     */
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

    /**
     * Methods declared by the test class named {@code className}, resolved by the DECLARED
     * class rather than by file name - so a citation stops resolving the moment the class is
     * renamed, which is the point.
     */
    private static List<String> declaredMethods(Path root, String className)
            throws java.io.IOException {
        List<String> names = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Test.java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                if (!EvidenceManifest.declaredClassName(file).equals(className)) {
                    continue;
                }
                Matcher m = Pattern.compile("void\\s+(\\w+)\\s*\\(")
                    .matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }
}
