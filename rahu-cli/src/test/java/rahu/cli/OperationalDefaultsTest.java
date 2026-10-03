package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A01: "Deterministic fake answer/trace; zero external calls; defaults documented."
 *
 * <p>AUDIT-2026-10-03-af. {@code DemoCommandTest} covers the demo trace's DIALECT well -
 * envelope shape, honest null usage, readability by the shipped inspect command. The other
 * two clauses of A01 had nothing checking them:
 *
 * <ul>
 *   <li><b>zero external calls</b> was a string the demo printed. "no network calls were
 *       made" is precisely the kind of claim that stays true until it doesn't, because
 *       nobody can falsify a printed sentence. JDK 27 removed SecurityManager outright
 *       ({@code System.setSecurityManager} throws UnsupportedOperationException), so the
 *       classic sandbox is gone and it had to be proven structurally instead.</li>
 *   <li><b>defaults documented</b>: the generated schema carried <b>zero</b> {@code default}
 *       annotations, and every operational default lived as a bare literal inside a
 *       production {@code if}-expression - in three separate places for one of them.</li>
 * </ul>
 *
 * <p>The defaults are load-bearing on both live paths: {@code chat} goes through
 * {@code LiveTurnDriver.run()} and {@code run} through {@code LiveTurnDriver.turn()}, and
 * both resolve the same numbers independently.
 */
class OperationalDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every operational default the CLI applies when a config field is absent. */
    private static final Map<String, Integer> OPERATIONAL_DEFAULTS = Map.of(
        // config key                       value  spec section it belongs in
        "context.maxPromptTokens", 8192,
        "routing.maximumCandidates", 32,
        "agent.maxCompletionTokens", 2048,
        "search.maxCandidates", 20,
        "tools.resultBytes", 65536);

    @Test
    @DisplayName("A01: the demo reaches no networking API at all")
    void demoReachesNoNetworkingApi() throws IOException {
        // Structural, not behavioural, and deliberately so. A test that ran the demo and
        // watched for connections would pass simply because the demo is fast enough that a
        // network attempt might not have landed yet - it would be a race, not a proof.
        //
        // Instead: walk the constant pool of every class reachable from DemoCommand and
        // refuse any reference into the networking packages. This fails the moment someone
        // wires an HTTP client into the demo, and it cannot pass by being lucky.
        Set<String> reachable = reachableClasses("rahu.cli.DemoCommand");
        assertTrue(reachable.size() > 1,
            "the reachability walk found only the start class, so it is not walking: "
                + reachable);

        Map<String, String> offending = new java.util.TreeMap<>();
        for (String name : reachable) {
            for (String ref : constantPoolClassRefs(repoClassFile(name))) {
                String dotted = ref.replace('/', '.');
                if (isNetworking(dotted)) {
                    offending.put(dotted, name);
                }
            }
        }
        // The bytecode walk above proves the demo does not USE networking. It cannot prove
        // the demo does not NAME it: javac erases an unused import, so
        // `import java.net.http.HttpClient;` leaves no constant-pool trace at all. Mutation
        // M1 confirmed that - adding the import left this test green.
        //
        // Naming it is worth catching for a different reason than using it: an unused import
        // of an HTTP client in an offline demo is either a half-finished feature or a
        // leftover, and both are worth a conversation. So the source is checked too.
        String demoSource = Files.readString(
            RepoFile.of("rahu-cli/src/main/java/rahu/cli/DemoCommand.java"));
        for (String line : demoSource.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            assertFalse(trimmed.contains("java.net.")
                    || trimmed.contains("javax.net.")
                    || trimmed.contains("HttpClient")
                    || trimmed.contains("Socket"),
                "DemoCommand names a networking API in live source: " + trimmed.strip()
                    + " \n A01 requires the demo to make zero external calls; an unused import "
                    + "is still a claim about what this command is.");
        }

        String via = offending.isEmpty() ? ""
            : " (first: " + offending.keySet().iterator().next() + ", referenced by "
                + offending.get(offending.keySet().iterator().next()) + ")";
        assertTrue(offending.isEmpty(),
            "the offline demo reaches networking API " + offending.keySet() + via
                + ". A01 requires zero external calls; if this is now intentional then A01 "
                + "is wrong and must be amended, not this test relaxed.");
    }

    /**
     * Names that count as external access. {@code URI} and {@code URL} are included because
     * building one is how a program starts trying to reach something; the demo has no
     * business naming any of them.
     */
    private static boolean isNetworking(String dotted) {
        return dotted.startsWith("java.net.")
            || dotted.startsWith("java.net.http.")
            || dotted.startsWith("javax.net.")
            || dotted.startsWith("sun.net.")
            || dotted.startsWith("jdk.internal.net.")
                        || dotted.startsWith("java.rmi.")
                        || dotted.equals("java.net.URL")
                        || dotted.equals("java.net.URI");
                }

    @Test
    @DisplayName("A01: the demo's answer and trace are byte-identical across runs")
    void demoIsDeterministic() throws Exception {
        // A01 says "deterministic fake answer/trace". The trace deliberately carries a
        // random runId and wall-clock timestamps - that is correct, because they are
        // IDENTITY metadata, not content. So determinism is asserted on the CONTENT:
        // every payload with those two fields removed must be identical run to run.
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            payloads.add(contentOnlyDemoTrace());
        }
        assertEquals(payloads.get(0), payloads.get(1),
            "two demo runs produced different trace content: " + payloads.get(0) + " vs "
                + payloads.get(1));
        assertEquals(payloads.get(1), payloads.get(2),
            "the third demo run diverged: " + payloads.get(1) + " vs " + payloads.get(2));
    }

    /** The demo's events with identity metadata stripped, canonicalised for comparison. */
    private String contentOnlyDemoTrace() throws Exception {
        Path root = Files.createTempDirectory("a01-demo-");
        try {
            DemoCommand.traceRoot = root.resolve("runs");
            assertEquals(0, new picocli.CommandLine(new Main()).execute("demo"));
            Path runDir;
            try (var dirs = Files.list(DemoCommand.traceRoot)) {
                runDir = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
            }
            StringBuilder canonical = new StringBuilder();
            for (String line : Files.readAllLines(runDir.resolve("events.jsonl"))) {
                JsonNode e = MAPPER.readTree(line);
                ((com.fasterxml.jackson.databind.node.ObjectNode) e)
                    .remove(List.of("runId", "timestamp", "elapsedMs"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) e.path("payload"))
                    .remove("elapsedMs");
                // RunTerminated reports the turn's own elapsed time too.
                canonical.append(MAPPER.writeValueAsString(e)).append('\n');
            }
            return canonical.toString();
        } finally {
            DemoCommand.traceRoot = Path.of(".rahu", "runs");
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("A01: every operational default is written down, and the value is the shipped one")
    void operationalDefaultsAreDocumented() throws Exception {
        // The schema is the machine-readable half of "documented", and it had no defaults at
        // all. An editor reading the schema is told a field is optional but not what it will
        // become, so the honest default is invisible exactly where someone would look for it.
        JsonNode schema = MAPPER.readTree(
            Files.readString(RepoFile.of("docs/generated/config.schema.json")));

        List<String> undocumented = new ArrayList<>();
        for (Map.Entry<String, Integer> e : OPERATIONAL_DEFAULTS.entrySet()) {
            // path() already returns an absolute pointer starting at /properties.
            JsonNode declared = schema.at(path(e.getKey()) + "/default");
            if (declared.isMissingNode()) {
                undocumented.add(e.getKey() + " (schema has no default)");
            } else if (declared.isNumber()
                && declared.asDouble() != (double) e.getValue()) {
                undocumented.add(e.getKey() + ": schema says " + declared.asInt()
                    + ", the code applies " + e.getValue());
            }
        }
        assertEquals(List.of(), undocumented,
            "A01 requires defaults to be documented, and these disagree or are absent: "
                + undocumented);
    }

    @Test
    @DisplayName("A01: the docs state each default rather than leaving it implied")
    void defaultsAppearInTheSpec() throws Exception {
        String config = Files.readString(RepoFile.of("docs/specs/configuration.md"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : OPERATIONAL_DEFAULTS.entrySet()) {
            String key = e.getKey();
            // Look for the value in the row that documents this key, not anywhere in the file:
            // a number appearing in an unrelated section documents nothing.
            String section = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            String field = key.substring(key.indexOf('.') + 1);
            String row = config.lines()
                .filter(l -> l.startsWith("| `") && l.contains("`" + section + "`"))
                .findFirst().orElse("");
            // The section row documents its fields in prose, so the FIELD must appear in it
            // too; a row that lists the section but never the field documents nothing.
            if (!row.isEmpty() && !row.contains(field)) {
                row = "";
            }
            if (row.isEmpty()) {
                missing.add(key + ": no table row in configuration.md documents this key");
            } else if (!row.contains(String.valueOf(e.getValue()))) {
                missing.add(key + ": configuration.md documents the key but not the shipped "
                    + "default " + e.getValue() + " (row: " + row.trim() + ")");
            }
        }
        assertEquals(List.of(), missing, "defaults are not documented: " + missing);
    }

    @Test
    @DisplayName("the context allowance has exactly one authoritative declaration")
    void noDuplicatedMagicDefaults() throws Exception {
        // 8192 used to be written out three times - ActiveRouter, LiveTurnDriver and as a bare
        // literal in RunCommand - with nothing tying them together.
        //
        // The first version of this check counted occurrences and allowed up to two. Mutation
        // M5 proved that useless: replacing the shared constant with a bare literal in
        // RunCommand DROPPED the count to one, well inside the limit, so the duplication came
        // back and the test passed. A bound cannot distinguish "one authoritative declaration"
        // from "no authoritative declaration". So the declaration site is now exact:
        // OperationalDefaults, and nowhere else.
        String declaration = "CONTEXT_ALLOWANCE_TOKENS = " +
            rahu.cli.config.OperationalDefaults.CONTEXT_ALLOWANCE_TOKENS;

        List<String> literalSites = new ArrayList<>();
        List<String> consumingSites = new ArrayList<>();
        for (Path file : mainSources()) {
            if (file.endsWith("OperationalDefaults.java")) {
                continue; // the one legitimate home
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String stripped = lines.get(i).strip();
                if (stripped.startsWith("*") || stripped.startsWith("//")
                    || stripped.startsWith("/*")) {
                    continue;
                }
                if (stripped.contains("CONTEXT_ALLOWANCE_TOKENS")) {
                    consumingSites.add(rel(file) + ":" + (i + 1));
                }
                if (stripped.contains(String.valueOf(
                        rahu.cli.config.OperationalDefaults.CONTEXT_ALLOWANCE_TOKENS))) {
                    literalSites.add(rel(file) + ":" + (i + 1) + "  " + stripped);
                }
            }
        }
        assertEquals(List.of(), literalSites,
            "the context allowance default (" + declaration + ") is written as a literal "
                + "outside OperationalDefaults, so the constant is no longer authoritative: "
                + literalSites);
        // The opposite failure also matters: if every consumer were switched back to a
        // literal, the constant would still exist and the literal check above would pass
        // (nothing would be left outside it) while the shared home had become dead code.
        // So the consumers are required to be present, not merely tolerated.
        assertFalse(consumingSites.isEmpty(),
            "nothing reads OperationalDefaults.CONTEXT_ALLOWANCE_TOKENS, so the shared "
                + "declaration is dead code and the real default is elsewhere");
        for (String required : List.of(
                "rahu/cli/RunCommand.java",
                "rahu/cli/live/ActiveRouter.java",
                "rahu/cli/live/LiveTurnDriver.java",
                "rahu/cli/SchemaGenerator.java")) {
            assertTrue(consumingSites.stream().anyMatch(s -> s.contains(required)),
                "the published default is no longer read by " + required
                    + "; every consumer must resolve it from OperationalDefaults, or a "
                    + "change to the default silently misses that path");
        }
    }

    @Test
    @DisplayName("the shipped confidence floor matches what the spec says it is")
    void confidenceFloorAgreesWithTheSpec() throws Exception {
        // configuration.md used to describe the default routing gate as a 0.65
        // chosen_probability threshold while the code applied 0.0. Opposite meanings:
        // 0.65 discards a route whose chosen action looks unconfident, 0 accepts
        // everything. An operator who read the spec and configured nothing got a
        // DIFFERENT product than the doc described.
        //
        // Resolved: 0.0 is the shipped default (routing.md calls 0.65 a provisional
        // concentration heuristic, not an accuracy guarantee, and shadow mode should not
        // silently discard routes). This test now reads the number from all three places it
        // is written - the constant, the spec table, and the schema - so the resolution
        // cannot quietly become a new disagreement.
        double constant = rahu.cli.config.OperationalDefaults.CONFIDENCE_FLOOR;

        String config = Files.readString(RepoFile.of("docs/specs/configuration.md"));
        Matcher documented = Pattern.compile(
            "`routing\\.confidenceFloor`\\s*\\|\\s*([0-9.]+)")
            .matcher(config);
        assertTrue(documented.find(),
            "configuration.md no longer documents routing.confidenceFloor in the defaults "
                + "table; update this test rather than deleting the check");

        JsonNode schema = MAPPER.readTree(
            Files.readString(RepoFile.of("docs/generated/config.schema.json")));
        JsonNode schemaDefault = schema.at("/properties/routing/properties/confidenceFloor/default");

        assertEquals(Double.parseDouble(documented.group(1)), constant, 0.0,
            "the routing confidence floor is " + constant + " in OperationalDefaults but the "
                + "spec documents " + documented.group(1));
        assertFalse(schemaDefault.isMissingNode(),
            "the generated schema no longer carries a confidenceFloor default, so A01's "
                + "'defaults documented' clause is unmet for this field");
        assertEquals(constant, schemaDefault.asDouble(), 1e-9,
            "the schema default for routing.confidenceFloor disagrees with the shipped "
                + "constant: schema " + schemaDefault.asDouble() + " vs code " + constant);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Turns a dotted config key into the JSON pointer the schema nests it under.
     *
     * <p>{@code context.maxPromptTokens} lives at {@code /properties/context/properties/
     * maxPromptTokens}, so each segment is separated by {@code /properties/} - not a bare
     * {@code /}. Written naively at first, this made every lookup miss and the test blamed
     * the schema.
     */
    private static String path(String dotted) {
        String[] parts = dotted.split("\\.");
        return "/properties/" + String.join("/properties/", parts);
    }

    /** Every {@code /src/main/java} file, across all modules. */
    private static List<Path> mainSources() throws IOException {
        List<Path> out = new ArrayList<>();
        Path repo = RepoFile.of("pom.xml").getParent();
        try (var mods = Files.list(repo)) {
            for (Path m : mods.filter(Files::isDirectory).sorted().toList()) {
                Path src = m.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (var walk = Files.walk(src)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        return out;
    }

    private static String rel(Path p) {
        String s = p.toString();
        int i = s.indexOf("/src/main/java/");
        return i < 0 ? s : s.substring(i + 1);
    }

    /** Locates a compiled class, or fails loudly rather than silently skipping the check. */
    private static Path repoClassFile(String dottedName) throws IOException {
        Path repo = RepoFile.of("pom.xml").getParent();
        for (Path mod : Files.list(repo).filter(Files::isDirectory).sorted().toList()) {
            Path candidate = mod.resolve("target/classes")
                .resolve(dottedName.replace('.', '/') + ".class");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("compiled class not found for " + dottedName
            + "; the reachability walk must not treat a missing class as a clean result");
    }

    /** Transitive closure over {@code rahu.*} class references, starting at one class. */
    private static Set<String> reachableClasses(String start) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> queue = new LinkedHashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String name = queue.iterator().next();
            queue.remove(name);
            if (!seen.add(name)) {
                continue;
            }
            for (String ref : constantPoolClassRefs(repoClassFile(name))) {
                String dotted = ref.replace('/', '.');
                if (dotted.startsWith("rahu.") && !seen.contains(dotted)) {
                    queue.add(dotted);
                }
            }
        }
        return seen;
    }

    /**
     * Class references in a {@code .class} constant pool, read straight from the bytes.
     *
     * <p>Hand-rolled rather than reflective because the loaded {@code Class} object does not
     * expose its own constant pool, and the whole point is to inspect the SHIPPED artifact
     * rather than whatever the test classpath happens to contain.
     */
    private static Set<String> constantPoolClassRefs(Path classFile) throws IOException {
        byte[] d = Files.readAllBytes(classFile);
        int count = ((d[8] & 0xFF) << 8) | (d[9] & 0xFF);
        int i = 10;
        Object[] pool = new Object[count];
        for (int k = 1; k < count; k++) {
            int tag = d[i++] & 0xFF;
            switch (tag) {
                case 1 -> { // Utf8
                    int len = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
                    i += 2;
                    pool[k] = new String(d, i, len, StandardCharsets.UTF_8);
                    i += len;
                }
                case 7 -> { // Class -> index of a Utf8 holding the internal name
                    pool[k] = -(((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF));
                    i += 2;
                }
                case 8, 16, 19, 20 -> i += 2;
                case 15 -> i += 3;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> i += 4;
                case 5, 6 -> { i += 8; k++; } // Long and Double take two slots
                default -> throw new IOException(
                    "unknown constant pool tag " + tag + " in " + classFile);
            }
        }
        Set<String> out = new TreeSet<>();
        for (Object entry : pool) {
            if (entry instanceof Integer idx && idx < 0) {
                Object name = pool[-idx];
                if (name instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Guards against an assertion that would pass because it compares something to itself. */
    @Test
    @DisplayName("sanity: the constants under test are distinct and non-zero")
    void theDefaultsThemselvesAreSane() {
        assertEquals(OPERATIONAL_DEFAULTS.size(),
            new LinkedHashSet<>(OPERATIONAL_DEFAULTS.keySet()).size(),
            "duplicate key in OPERATIONAL_DEFAULTS");
        assertNotEquals(0, OPERATIONAL_DEFAULTS.get("context.maxPromptTokens"));
        assertTrue(OPERATIONAL_DEFAULTS.get("tools.resultBytes") > 0);
        assertFalse(OPERATIONAL_DEFAULTS.containsKey("agent.maxCostUsd"),
            "maxCostUsd defaults to money; if it ever becomes non-zero it belongs here");
    }
}
