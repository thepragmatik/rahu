package rahu.cli.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the requirement-to-test evidence manifest required by A30 and gate G10
 * ("Cross-subsystem review and resolved high-severity findings; requirement-to-test
 * evidence manifest"), into {@code docs/generated/evidence-manifest.json}.
 *
 * <p>WHY IT IS GENERATED RATHER THAN WRITTEN. G10 says "code presence alone is not
 * evidence", and the corollary is that a hand-maintained evidence table is a second
 * thing to keep in sync that nothing checks. So the rows are DERIVED from the test
 * sources: an acceptance ID counts as evidenced only when a test method's own
 * javadoc or {@code @DisplayName} names that ID. Generation makes drift impossible in
 * one direction (a deleted test cannot leave a surviving claim) and detectable in the
 * other ({@link EvidenceManifestTest} fails when an ID loses all evidence).
 *
 * <p>METHOD-LEVEL, NOT CLASS-LEVEL. A class-level rule would let any single method that
 * mentions "A16" in passing satisfy the whole class, which is the exact mistake G10
 * warns about one level up.
 *
 * <p>WHY IT IS NOT COMMITTED, like {@code build-manifest.json}: {@code docs/generated/}
 * is gitignored, and a file that changes on every test edit should never be a commit
 * that has to be remembered. The test pins the CONTENT RULES instead.
 */
public final class EvidenceManifest {

    private EvidenceManifest() {
    }

    /** Acceptance IDs that cannot have a named offline test, and the reason. */
    private static final Map<String, String> EXEMPTIONS = Map.of(
        "A17", "requires paired live evaluation runs with real router overhead and confidence "
            + "intervals; no offline test can produce a genuine baseline/candidate pair",
        "A20", "is a process clause about recorded findings, not a behavioural requirement; it "
            + "is discharged by docs/reviews/ and the audit register, not by a test",
        "A30", "is the release gate itself; it is discharged by EvidenceManifestTest plus this "
            + "manifest, which cannot meaningfully cite itself");

    /**
     * Requirements that are honestly not covered offline. Mirrors the test's copy.
     * Kept here because the manifest must say so out loud, and the test asserts the two
     * lists agree, so neither can quietly drop a gap.
     */
    public static Map<String, String> exemptions() {
        return EXEMPTIONS;
    }

    public static String generate(Path repoRoot) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("note", "GENERATED - do not edit by hand and do not commit "
            + "(docs/generated/ is gitignored). Evidence is attributed per test METHOD, from "
            + "the ID appearing in that method's own javadoc or @DisplayName.");

        Map<String, TreeSet<String>> byId = new TreeMap<>();
        for (Path file : testSources(repoRoot)) {
            if (isManifestItself(file)) {
                // The checker is not evidence. I let this through first and A30 came back
                // "evidenced" citing EvidenceManifestTest#<class> - this very class. A gate
                // that discharges itself is circular: the requirement is satisfied by the
                // existence of the machinery, not by the machinery's self-description.
                continue;
            }
            collectFile(file, byId);
            collectClassDoc(file, byId);
        }

        ArrayNode requirements = root.putArray("requirements");
        int evidenced = 0;
        int exempted = 0;
        for (Row row : parseRequirementRows(repoRoot)) {
            String id = row.id();
            ObjectNode entry = mapper.createObjectNode();
            entry.put("id", id);
            entry.put("given", row.given());
            entry.put("required", row.required());
            TreeSet<String> methods = byId.getOrDefault(id, new TreeSet<>());
            // "method level" means at least ONE named method cites the ID. Written the other
            // way round first, which labelled every row evidencedByClassDoc even when it had
            // real per-method citations, because a class javadoc usually cites the same ID.
            boolean methodLevel =
                methods.stream().anyMatch(s -> !s.endsWith("#<class>"));
            if (!methods.isEmpty()) {
                evidenced++;
                // Distinguish the two strengths honestly. A method-level citation names the
                // exact test; a class-level one only says the class doc claims the ID, so it
                // is recorded as such rather than dressed up as per-method evidence.
                entry.put("status", methodLevel ? "evidenced" : "evidencedByClassDoc");
                if (!methodLevel) {
                    entry.put("caveat", "cited in the class javadoc, not in any single test's "
                        + "documentation; the class as a whole is the evidence");
                }
                ArrayNode cites = entry.putArray("tests");
                methods.forEach(cites::add);
            } else {
                String reason = EXEMPTIONS.get(id);
                if (reason == null) {
                    // Not exempted and not evidenced. Recorded as a gap rather than omitted,
                    // so the manifest cannot be read as full coverage when it is not.
                    entry.put("status", "GAP");
                } else {
                    exempted++;
                    entry.put("status", "exempted");
                    entry.put("reason", reason);
                }
            }
            requirements.add(entry);
        }

        ObjectNode summary = root.putObject("summary");
        summary.put("total", requirements.size());
        summary.put("evidenced", evidenced);
        summary.put("exempted", exempted);
        summary.put("gaps", requirements.size() - evidenced - exempted);

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    public static void writeTo(Path repoRoot, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, generate(repoRoot), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- internals

    private record Row(String id, String given, String required) {}

    private static List<Row> parseRequirementRows(Path repoRoot) throws IOException {
        List<Row> out = new ArrayList<>();
        for (String line : Files.readAllLines(
                repoRoot.resolve("docs/specs/acceptance.md"), StandardCharsets.UTF_8)) {
            if (!line.startsWith("| A") || line.startsWith("| ID")) {
                continue;
            }
            String[] cells = line.split("\\|");
            if (cells.length >= 5) {
                out.add(new Row(cells[1].strip(), cells[2].strip(), cells[4].strip()));
            }
        }
        return out;
    }

    /**
     * The class a source file declares. The manifest cites THIS, not the file name.
     *
     * <p>Citing the file name looked equivalent and was not: renaming the class inside
     * {@code FooTest.java} left every citation still resolving to the file. A citation must
     * name the thing a reader will go and look at, and a reader follows the class.
     *
     * <p>Note what this does and does not buy. Renaming {@code OperationalDefaultsTest} still
     * leaves the suite green - correctly so. I checked it directly: the emitted citations
     * become {@code RenamedDefaultsTest#...}, so the manifest stays true to the code rather
     * than pointing at a stale name. A generated manifest cannot cite a phantom; what the
     * phantom test guards is a generator bug that fabricates a citation (mutation V1).
     */
    public static String declaredClassName(Path file) throws IOException {
        Matcher m = Pattern.compile("class\\s+(\\w+)")
            .matcher(Files.readString(file, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : file.getFileName().toString().replace(".java", "");
    }

    /** True for the manifest's own sources, which may not serve as evidence for anything. */
    private static boolean isManifestItself(Path file) {
        String name = file.getFileName().toString();
        return name.equals("EvidenceManifest.java") || name.equals("EvidenceManifestTest.java");
    }

    private static List<Path> testSources(Path repoRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith("Test.java"))
                .filter(p -> !p.toString().contains("/target/"))
                .sorted()
                .forEach(out::add);
        }
        return out;
    }

    /**
     * The javadoc visible to a method is its own, or the class javadoc if it has none -
     * but NOT the previous method's. Slicing at the last closing brace before the
     * annotation is what keeps one method's citation from bleeding into the next.
     */
    private static void collectClassDoc(Path file, Map<String, TreeSet<String>> byId)
            throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String simple = declaredClassName(file);
        int body = source.indexOf('{');
        if (body < 0) {
            return;
        }
        Matcher id = Pattern.compile("\\bA(\\d{2})\\b").matcher(source.substring(0, body));
        while (id.find()) {
            byId.computeIfAbsent("A" + id.group(1), k -> new TreeSet<>()).add(simple + "#<class>");
        }
    }

    private static void collectFile(Path file, Map<String, TreeSet<String>> byId)
            throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String simple = declaredClassName(file);
        Pattern declaration = Pattern.compile(
            "(?:@DisplayName\\(\"([^\"]*)\"\\)\\s*)?"
                + "(?:@\\w+(?:\\([^)]*\\))?\\s*)*"
                + "(?:public\\s+|private\\s+)?void\\s+(\\w+)\\s*\\(");
        Matcher test = Pattern.compile("@Test\\b").matcher(source);
        while (test.find()) {
            Matcher decl = declaration.matcher(source.substring(test.end()));
            if (!decl.find()) {
                continue;
            }
            String prefix = source.substring(0, test.start());
            String ownerDoc = prefix.substring(Math.max(prefix.lastIndexOf('}'), 0));
            StringBuilder doc = new StringBuilder(ownerDoc)
                .append(' ')
                .append(decl.group(1) == null ? "" : decl.group(1));
            Matcher id = Pattern.compile("\\bA(\\d{2})\\b").matcher(doc);
            while (id.find()) {
                byId.computeIfAbsent("A" + id.group(1), k -> new TreeSet<>())
                    .add(simple + "#" + decl.group(2));
            }
        }
    }

    /** Every acceptance ID declared in the spec, in order. */
    public static List<String> allIds(Path repoRoot) throws IOException {
        List<String> out = new ArrayList<>();
        parseRequirementRows(repoRoot).forEach(r -> out.add(r.id()));
        return out;
    }

    /**
     * Maps each acceptance ID to the tests citing it. Citations ending in
     * {@code #<class>} come from a class javadoc and are weaker than a per-method
     * citation; the manifest records the difference rather than flattening it.
     */
    public static Map<String, List<String>> evidence(Path repoRoot) throws IOException {
        Map<String, TreeSet<String>> byId = new TreeMap<>();
        for (Path file : testSources(repoRoot)) {
            if (isManifestItself(file)) {
                continue;
            }
            collectFile(file, byId);
            collectClassDoc(file, byId);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byId.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }
}
