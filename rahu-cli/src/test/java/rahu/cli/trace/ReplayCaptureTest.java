package rahu.cli.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rahu.core.ExecutionCandidate;
import rahu.core.ModelRef;
import rahu.core.ReasoningPolicy;
import rahu.core.decision.DecisionResult;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.RouteResolver;
import rahu.core.routing.RoutingMode;

/**
 * AUDIT-2026-10-03-e: the replay path was unreachable, and the engine that would
 * have served it hardcoded the resolver's confidence field and floor.
 *
 * <p>These tests pin the properties that make a replay worth anything: it
 * re-derives rather than reads back, it uses the recorded inputs rather than
 * defaults, it refuses inputs it cannot faithfully reconstruct, and it contains no
 * path to the network.
 */
class ReplayCaptureTest {

    @TempDir
    Path root;

    private static final CandidateSet CANDIDATES = new CandidateSet(
        java.util.List.of(
            candidate("fast@low"),
            candidate("quality@medium")),
        java.util.List.of(new CandidateSet.Exclusion("stale@none", "unknown-evidence")));

    private static ExecutionCandidate candidate(String id) {
        int at = id.indexOf('@');
        return new ExecutionCandidate(id, new ModelRef(id.substring(0, at)),
            id.endsWith("low")
                ? ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW)
                : ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.MEDIUM),
            Map.of(), "cat", "cfg");
    }

    private static RouteResolver.ResolutionInput input(String field, double floor) {
        return new RouteResolver.ResolutionInput(CANDIDATES,
            Optional.of("fast@low"), Optional.of("quality@medium"), field, floor);
    }

    private static DecisionResult choice(String label, String... ids) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (String id : ids) {
            probabilities.put(id, 0.5);
        }
        return new DecisionResult.ValidChoice("q", label, probabilities,
            Optional.of(0.9), "self-reported");
    }

    // ------------------------------------------------------------- round trip

    @Test
    @DisplayName("a capture round-trips to identical resolver inputs")
    void roundTripPreservesInputs() throws IOException {
        RouteResolver.ResolutionInput original = input("chosen_probability", 0.72);
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, original,
            Optional.of(choice("quality@medium", "fast@low", "quality@medium")));

        ReplayCapture.Frozen frozen = ReplayCapture.read(root);

        assertEquals(CANDIDATES.candidates().size(), frozen.candidates().candidates().size());
        assertEquals(CANDIDATES.exclusions(), frozen.candidates().exclusions());
        assertEquals("chosen_probability", frozen.input().confidenceField());
        assertEquals(0.72, frozen.input().confidenceFloor(), 1e-9);
        assertEquals(Optional.of("fast@low"), frozen.input().baselineId());
        assertEquals(Optional.of("quality@medium"), frozen.input().fallbackId());
        assertEquals(RoutingMode.ACTIVE, frozen.mode());
        // The reasoning policy is reconstructed from the id, not invented: a replay
        // must not be able to claim a policy the live turn did not use.
        assertEquals(ReasoningPolicy.ExplicitEffort.of(ReasoningPolicy.Effort.LOW),
            frozen.candidates().candidates().get(0).reasoningPolicy());
    }

    @Test
    @DisplayName("exclusions survive, because the resolver consults them")
    void exclusionsArePartOfTheInput() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        ReplayCapture.Frozen frozen = ReplayCapture.read(root);

        assertEquals(1, frozen.candidates().exclusions().size());
        assertEquals("stale@none", frozen.candidates().exclusions().get(0).ref());
        assertEquals("unknown-evidence", frozen.candidates().exclusions().get(0).reason());
    }

    // --------------------------------------- the defect this increment found

    @Test
    @DisplayName("replay uses the CAPTURED confidence field and floor, not defaults")
    void replayUsesCapturedConfidenceNotDefaults() throws IOException {
        // The defect: ReplayEngine hardcoded "chosen_probability"/0.65 - the config
        // DEFAULTS. A run configured with anything else was replayed against inputs
        // it never used. Set a floor the defaults would have accepted but this run
        // did not, and a floor the reverse, and assert the outcome tracks the
        // capture rather than the constant.
        DecisionResult decision = choice("fast@low", "fast@low", "quality@medium");

        RouteResolver.ResolutionInput strict = input("chosen_probability", 0.99);
        Path strictDir = root.resolve("strict");
        ReplayCapture.write(strictDir, CANDIDATES, RoutingMode.ACTIVE, strict,
            Optional.of(decision));
        ReplayOutcome strictOutcome = ReplayEngine.replay(ReplayCapture.read(strictDir));
        assertEquals(ReplayOutcome.ReplayStatus.REPLAYED, strictOutcome.status(),
            "a 0.99 floor should be replayable and should have rejected the 0.5 choice");

        RouteResolver.ResolutionInput permissive = input("chosen_probability", 0.0);
        Path permissiveDir = root.resolve("permissive");
        ReplayCapture.write(permissiveDir, CANDIDATES, RoutingMode.ACTIVE, permissive,
            Optional.of(decision));
        ReplayOutcome permissiveOutcome =
            ReplayEngine.replay(ReplayCapture.read(permissiveDir));

        // The two captures differ ONLY in the floor, so any difference in outcome is
        // attributable to the captured floor being used. Under the old hardcoded
        // 0.65 both would have produced byte-identical outcomes, which is exactly
        // how the defect would have hidden.
        assertNotEquals(strictOutcome.degraded(), permissiveOutcome.degraded(),
            "the captured confidenceFloor must change the replayed outcome;"
                + " identical outcomes mean the engine used a constant instead");
    }

    @Test
    @DisplayName("the defaults-based overload is deprecated, not the path in use")
    void defaultsOverloadIsDeprecated() throws Exception {
        // Structural: the String-baseline overload is the only place defaults can
        // enter, and it is marked for removal. A future refactor that re-adds an
        // un-captured entry point would have to delete this test deliberately.
        assertTrue(java.util.Arrays.stream(ReplayEngine.class.getMethods())
            .filter(m -> m.getName().equals("replay"))
            .anyMatch(m -> m.isAnnotationPresent(Deprecated.class)),
            "the defaults-based replay overload must stay @Deprecated");
    }

    // --------------------------------------------------------- refuse, not guess

    @Test
    @DisplayName("a missing capture reports absent rather than substituting defaults")
    void missingCaptureIsRefused() {
        IOException e = assertThrows(IOException.class, () -> ReplayCapture.read(root));
        assertTrue(e.getMessage().contains("trace.capture=payloads"),
            "the message must name the fix, not just the failure: " + e.getMessage());
    }

    @Test
    @DisplayName("a capture with no confidenceFloor is refused, not defaulted")
    void missingConfidenceFloorIsRefused() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        Path file = root.resolve(ReplayCapture.FILE);
        String json = Files.readString(file)
            .replace("\"confidenceFloor\" : 0.5", "\"unrelated\" : 0.5");
        Files.writeString(file, json);

        IOException e = assertThrows(IOException.class, () -> ReplayCapture.read(root));
        assertTrue(e.getMessage().contains("confidenceFloor"),
            "must name the missing field: " + e.getMessage());
    }

    @Test
    @DisplayName("an unknown candidate policy suffix is refused")
    void unknownPolicySuffixIsRefused() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        Path file = root.resolve(ReplayCapture.FILE);
        Files.writeString(file, Files.readString(file).replace("\"fast@low\"", "\"fast@turbo\""));

        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> ReplayCapture.read(root));
        assertTrue(e.getMessage().contains("reasoning policy"),
            "must explain that the suffix is the policy: " + e.getMessage());
    }

    @Test
    @DisplayName("a capture whose schemaVersion is unknown is refused")
    void unknownSchemaVersionIsRefused() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        Path file = root.resolve(ReplayCapture.FILE);
        Files.writeString(file, Files.readString(file)
            .replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 99"));

        IOException e = assertThrows(IOException.class, () -> ReplayCapture.read(root));
        assertTrue(e.getMessage().contains("schemaVersion"), e.getMessage());
    }

    @Test
    @DisplayName("a non-routing decision kind is refused at write time")
    void nonRoutingDecisionIsRefused() {
        DecisionResult noul = new DecisionResult.ValidNoul("q", true, Optional.of(0.5));
        assertThrows(IllegalArgumentException.class, () -> ReplayCapture.write(root,
            CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5), Optional.of(noul)));
    }

    // ---------------------------------------------------------------- privacy

    @Test
    @DisplayName("the capture contains no prompt text, even when the prompt is sensitive")
    void captureCarriesNoPromptText() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        String captured = Files.readString(root.resolve(ReplayCapture.FILE));

        // cli.md:50: replay must not dump protected payloads. The capture is built
        // from routing inputs, so there is nothing to dump - asserted rather than
        // assumed, because a future field added to the capture could reintroduce it.
        for (String leak : new String[] {"bob@example.com", "+61", "password", "ssn",
            "api_key", "sk-", "Authorization", "prompt", "content", "argument"}) {
            assertFalse(captured.toLowerCase(java.util.Locale.ROOT)
                .contains(leak.toLowerCase(java.util.Locale.ROOT)),
                "capture must not contain " + leak + ":\n" + captured);
        }
    }

    @Test
    @DisplayName("the capture stores no resolution, so a replay must re-derive")
    void captureStoresNoResolution() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        String captured = Files.readString(root.resolve(ReplayCapture.FILE));

        // A stored resolution next to the inputs would let a future caller "compare"
        // against an answer instead of re-running the policy - a comparison that
        // cannot fail. The inputs are stored; the outcome is not.
        assertFalse(captured.contains("\"suggested\""), captured);
        assertFalse(captured.contains("\"executed\""), captured);
        assertFalse(captured.contains("\"degraded\""), captured);
    }

    // ------------------------------------------------------------ determinism

    @Test
    @DisplayName("two captures of the same decision are byte-identical")
    void captureIsDeterministic() throws IOException {
        // Probability maps iterate in insertion order, which need not be stable
        // across runs. Sorted keys keep a diff between two captures about policy,
        // not about map ordering.
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        ReplayCapture.write(a, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(choice("quality@medium", "quality@medium", "fast@low")));
        Map<String, Double> reordered = new LinkedHashMap<>();
        reordered.put("quality@medium", 0.5);
        reordered.put("fast@low", 0.5);
        ReplayCapture.write(b, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.of(new DecisionResult.ValidChoice("q", "quality@medium", reordered,
                Optional.of(0.9), "self-reported")));

        assertEquals(Files.readString(a.resolve(ReplayCapture.FILE)),
            Files.readString(b.resolve(ReplayCapture.FILE)));
    }

    @Test
    @DisplayName("replay is deterministic across repeated invocations")
    void replayIsDeterministic() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.65),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        ReplayCapture.Frozen frozen = ReplayCapture.read(root);

        ReplayOutcome first = ReplayEngine.replay(frozen);
        ReplayOutcome second = ReplayEngine.replay(frozen);
        assertEquals(first, second, "policy replay must be a pure function of its inputs");
    }

    @Test
    @DisplayName("no decision in the capture replays as UNAVAILABLE, never as a guess")
    void absentDecisionIsUnavailable() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("f", 0.5),
            Optional.empty());
        ReplayOutcome outcome = ReplayEngine.replay(ReplayCapture.read(root));

        assertEquals(ReplayOutcome.ReplayStatus.UNAVAILABLE, outcome.status());
        assertTrue(outcome.unavailableReason().contains("no captured decision"));
    }

    // ------------------------------------------------------------- no network

    @Test
    @DisplayName("the replay path references no provider or dispatch client")
    void replayPathHasNoNetworkDependency() throws Exception {
        // A11: "replay makes no external calls". Proven structurally over the
        // production classes on the replay path, because a behavioural test cannot
        // prove the ABSENCE of a call - an implementation that only reaches the
        // network under some condition would pass.
        String[] sources = {
            "rahu/cli/trace/ReplayCommand.java",
            "rahu/cli/trace/ReplayCapture.java",
            "rahu/cli/trace/ReplayEngine.java",
            "rahu/cli/trace/ReplayOutcome.java"};
        for (String source : sources) {
            String text = readSource(source);
            for (String forbidden : new String[] {"HttpClient", "http.", "OkHttp", "URL(",
                "Socket", "openStream", "OpenRouterProvider", "DecisionEngine",
                "gate.admit", "Tools.", "SystemOneHttpAdapter"}) {
                assertFalse(text.contains(forbidden),
                    source + " must not reference " + forbidden
                        + "; replay makes no external calls");
            }
        }
    }

    private String readSource(String moduleRelative) throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("src/main/java/" + moduleRelative))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "cannot locate " + moduleRelative);
        return Files.readString(dir.resolve("src/main/java/" + moduleRelative));
    }
}