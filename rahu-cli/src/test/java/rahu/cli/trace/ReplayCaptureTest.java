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
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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
            CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5), Optional.of(noul)));
    }

    // ---------------------------------------------------------------- privacy

    @Test
    @DisplayName("the capture contains no prompt text, even when the prompt is sensitive")
    void captureCarriesNoPromptText() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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

    // ==================================================================
    // AUDIT-l item 1: after AUDIT-e made `confidenceField` select the field the
    // floor reads, a capture that used `raw_confidence` stopped being
    // REPLAYABLE - encodeDecision wrote neither rawConfidence nor the semantics
    // identifier, so the replay rebuilt a decision with the field empty and the
    // floor failed for a DIFFERENT reason than the live turn did.
    //
    // Its comment said semantics were omitted "because routing never consults
    // them". True when written; FALSE as of AUDIT-e.
    // ==================================================================

    @Test
    @DisplayName("an ABSENT raw confidence stays absent through a round trip")
    void absentRawConfidenceStaysAbsent() throws IOException {
        // Three mutations survived and all three shared ONE cause: nothing asserted
        // that ABSENCE survives the round trip. Dropping the read-side null check,
        // defaulting it to 0, and writing 0 for an absent score all produced a
        // capture that LOOKED fine and replayed a confidence the provider never
        // gave - the AUDIT-j conflation, one layer into the capture.
        //
        // The distinguishing case is a capture for `raw_confidence` whose decision
        // carries NO provider score. Read as 0.0 it would fail the floor for the
        // wrong reason and, worse, claim a measured confidence of zero.
        var dir = root.resolve("absent-raw");
        var noScore = new DecisionResult.ValidChoice("q", "fast@low",
            Map.of("fast@low", 0.9, "quality@medium", 0.1), Optional.empty(), "none");

        ReplayCapture.write(dir, CANDIDATES, RoutingMode.ACTIVE,
            input("raw_confidence", 0.65), Optional.of(noScore));

        var frozen = ReplayCapture.read(dir);
        var replayed = frozen.decision().orElseThrow();
        assertTrue(replayed instanceof DecisionResult.ValidChoice choice, "a choice");
        assertEquals(Optional.empty(),
            ((DecisionResult.ValidChoice) replayed).rawConfidence(),
            "a decision with no provider score must replay as having none - not as "
                + "0.0, which is a confidence claim the provider never made");

        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(dir.resolve(ReplayCapture.FILE)));
        assertTrue(json.path("decision").path("rawConfidence").isNull(),
            "an absent score must be written as JSON null, not 0: " + json);

        // The RECONSTRUCTED object, not the capture text. Asserting only the file
        // let a mutation that hardcoded the semantics to "captured" survive: the
        // file was right and the object was wrong, which is the whole point of a
        // round trip. systemone.md:36 requires the semantics identifier to travel
        // with the score, so the decoded decision must still name the formula.
        var scored = new DecisionResult.ValidChoice("q", "fast@low",
            Map.of("fast@low", 0.9, "quality@medium", 0.1), Optional.of(0.8),
            "provider_score");
        var dir2 = root.resolve("semantics-round-trip");
        ReplayCapture.write(dir2, CANDIDATES, RoutingMode.ACTIVE,
            input("raw_confidence", 0.65), Optional.of(scored));
        var decoded = (DecisionResult.ValidChoice) ReplayCapture.read(dir2)
            .decision().orElseThrow();
        assertEquals("provider_score", decoded.confidenceSemantics(),
            "the decoded decision must name the formula that produced the score; "
                + "reading it back as a fixed placeholder makes the capture a lie "
                + "about which confidence it recorded");
        assertEquals(Optional.empty(),
            new RouteResolver().resolve(RoutingMode.ACTIVE, frozen.decision(), frozen.input())
                .suggestedId(),
            "and the floor must then fail, because there is no number to compare");
    }

    @Test
    @DisplayName("a raw_confidence capture replays to the same outcome")
    void rawConfidenceCaptureReplaysFaithfully() throws IOException {
        var dir = root.resolve("raw-conf-replay");
        // chosen_probability 0.5 is BELOW the 0.65 floor; raw_confidence 0.9 is
        // above it. Only a capture carrying rawConfidence can replay this as the
        // success the live turn produced.
        var valid = new DecisionResult.ValidChoice("q", "fast@low",
            Map.of("fast@low", 0.5, "quality@medium", 0.5), Optional.of(0.9),
            "provider_score");

        // write() returns the capture FILE; read() takes the run directory. My
        // first draft passed the file to read() (FileNotFound); my "correction"
        // then appended the filename again ("Not a directory"). Read() derives
        // the path from the directory, so pass the directory.
        ReplayCapture.write(dir, CANDIDATES, RoutingMode.ACTIVE,
            input("raw_confidence", 0.65), Optional.of(valid));

        ReplayCapture.Frozen frozen = ReplayCapture.read(dir);
        var replayed = new RouteResolver().resolve(RoutingMode.ACTIVE,
            frozen.decision(), frozen.input());

        assertEquals(Optional.of("fast@low"), replayed.suggestedId(),
            "the replay must reach the same suggestion the live turn did; a capture "
                + "missing rawConfidence degrades instead, which is a DIFFERENT "
                + "outcome produced by a lossy capture: " + replayed);
    }

    @Test
    @DisplayName("the capture records the raw confidence and its semantics identifier")
    void captureRecordsRawConfidenceAndSemantics() throws IOException {
        // systemone.md:36 requires "optional raw provider confidence plus
        // semantics identifier". routing.md:26 requires the probability and the
        // provider's formula be kept SEPARATE - which is unobservable while the
        // capture omits the identifier naming which formula produced the score.
        var dir = root.resolve("semantics");
        var valid = new DecisionResult.ValidChoice("q", "fast@low",
            Map.of("fast@low", 0.9, "quality@medium", 0.1), Optional.of(0.8),
            "provider_score");

        ReplayCapture.write(dir, CANDIDATES, RoutingMode.ACTIVE,
            input("raw_confidence", 0.65), Optional.of(valid));

        String json = Files.readString(dir.resolve(ReplayCapture.FILE));
        assertTrue(json.contains("provider_score"),
            "the semantics identifier must travel with the score it describes: " + json);
        // PARSED, not string-matched. Jackson's pretty printer writes doubles as
        // 0.80000000000000004, so pinning the text would break on a harmless
        // representation change; and merely asserting the KEY is present would pass
        // even if the value were null - which is the defect. Parse and compare.
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(json);
        assertEquals(0.8, parsed.path("decision").path("rawConfidence").asDouble(), 1e-9,
            "rawConfidence must carry the provider score, or a raw_confidence run "
                + "cannot replay: " + json);
        assertEquals("provider_score",
            parsed.path("decision").path("confidenceSemantics").asText(null),
            "the formula identifier must be captured: " + json);
    }

    @Test
    @DisplayName("two captures of the same decision are byte-identical")
    void captureIsDeterministic() throws IOException {
        // Probability maps iterate in insertion order, which need not be stable
        // across runs. Sorted keys keep a diff between two captures about policy,
        // not about map ordering.
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        ReplayCapture.write(a, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
            Optional.of(choice("quality@medium", "quality@medium", "fast@low")));
        Map<String, Double> reordered = new LinkedHashMap<>();
        reordered.put("quality@medium", 0.5);
        reordered.put("fast@low", 0.5);
        ReplayCapture.write(b, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
            Optional.of(new DecisionResult.ValidChoice("q", "quality@medium", reordered,
                Optional.of(0.9), "self-reported")));

        assertEquals(Files.readString(a.resolve(ReplayCapture.FILE)),
            Files.readString(b.resolve(ReplayCapture.FILE)));
    }

    @Test
    @DisplayName("replay is deterministic across repeated invocations")
    void replayIsDeterministic() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.65),
            Optional.of(choice("fast@low", "fast@low", "quality@medium")));
        ReplayCapture.Frozen frozen = ReplayCapture.read(root);

        ReplayOutcome first = ReplayEngine.replay(frozen);
        ReplayOutcome second = ReplayEngine.replay(frozen);
        assertEquals(first, second, "policy replay must be a pure function of its inputs");
    }

    @Test
    @DisplayName("no decision in the capture replays as UNAVAILABLE, never as a guess")
    void absentDecisionIsUnavailable() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE, input("chosen_probability", 0.5),
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

    /**
     * AUDIT-2026-10-03-h: the missing-capture error asserted a cause it never checked.
     *
     * <p>It always said "trace.capture=payloads is required", including for an
     * operator whose config DID say payloads. That sends someone to edit a correct
     * config, which is worse than a terse error. Both real causes are covered here:
     * the mode never routed (offline), and it routed but did not capture.
     */
    @Test
    @DisplayName("a missing capture blames capture, not the config, when the run did route")
    void missingCaptureOnARoutedRunBlamesCapture() throws Exception {
        Path run = root.resolve("routed");
        Files.createDirectories(run);
        // A run that routed but captured nothing: replay.json absent, RouteResolved
        // present. The operator set payloads, so telling them to set payloads is wrong.
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            "{\"type\":\"RouteResolved\",\"payload\":{}}\n");
        var e = assertThrows(IOException.class, () -> ReplayCapture.read(run));
        assertTrue(e.getMessage().contains("recorded RouteResolved"), e.getMessage());
        assertTrue(e.getMessage().contains("not 'payloads' at run time"), e.getMessage());
    }

    @Test
    @DisplayName("a missing capture on an UNROUTED run says routing, not the config")
    void missingCaptureOnAnUnroutedRunSaysRouting() throws Exception {
        Path run = root.resolve("unrouted");
        Files.createDirectories(run);
        // Offline: RunStarted + RunTerminated, no RouteResolved, so there were no
        // routing inputs to capture and no amount of config would have produced them.
        Files.writeString(run.resolve(TraceFiles.EVENTS),
            "{\"type\":\"RunStarted\",\"payload\":{}}\n"
            + "{\"type\":\"RunTerminated\",\"payload\":{}}\n");
        var e = assertThrows(IOException.class, () -> ReplayCapture.read(run));
        assertTrue(e.getMessage().contains("no RouteResolved"), e.getMessage());
        assertTrue(e.getMessage().contains("necessary but not sufficient"), e.getMessage());
    }

    @Test
    @DisplayName("with no events at all the message still avoids blaming the config alone")
    void missingCaptureWithNoEventsIsStillHonest() throws Exception {
        Path run = root.resolve("bare");
        Files.createDirectories(run);
        var e = assertThrows(IOException.class, () -> ReplayCapture.read(run));
        // No evidence either way: say so rather than assert a cause.
        assertFalse(e.getMessage().contains("is required for replay"), e.getMessage());
        assertTrue(e.getMessage().contains("no capture at"), e.getMessage());
    }

    // ------------------------------------------- F-8: a failure decision round-trips

    @Test
    @DisplayName("a FAILED decision is captured and read back with its kind intact")
    void failureDecisionRoundTrips() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE,
            input("chosen_probability", 0.5),
            Optional.of(new DecisionResult.Failure(
                DecisionResult.FailureKind.UNREACHABLE, "decision transport failed")));
        Path file = root.resolve(ReplayCapture.FILE);
        String json = Files.readString(file);

        assertTrue(json.contains("\"failureKind\" : \"UNREACHABLE\""),
            "the kind must be recorded, or the trace cannot say what went wrong: " + json);
        var read = ReplayCapture.read(root);
        assertTrue(read.decision().orElseThrow().toString().contains("UNREACHABLE"),
            "a definite transport failure must survive the round trip");
    }

    @Test
    @DisplayName("a failure kind this build does not know degrades to UNKNOWN, not a crash")
    void unknownFailureKindDegradesRatherThanCrashing() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE,
            input("chosen_probability", 0.5),
            Optional.of(new DecisionResult.Failure(
                DecisionResult.FailureKind.AMBIGUOUS, "decision transport failed")));
        Path file = root.resolve(ReplayCapture.FILE);
        // Simulates a capture written by a NEWER build, or a hand-edited one.
        Files.writeString(file, Files.readString(file)
            .replace("AMBIGUOUS", "SOME_KIND_FROM_THE_FUTURE"));

        // Before AUDIT-2026-10-03-t this threw IllegalArgumentException out of a
        // decode path whose other failures are reported as replay errors. An unnamed
        // exception is not a diagnosis: the operator gets a stack trace.
        var read = ReplayCapture.read(root);
        assertTrue(read.decision().orElseThrow().toString().contains("UNKNOWN"),
            "an unrecognised kind must degrade to the honest answer, not abort replay");
    }

    @Test
    @DisplayName("an UNREACHABLE capture still replays as UNAVAILABLE, never as a guess")
    void unreachableFailureNeverReplaysAsAGuess() throws IOException {
        ReplayCapture.write(root, CANDIDATES, RoutingMode.ACTIVE,
            input("chosen_probability", 0.5),
            Optional.of(new DecisionResult.Failure(
                DecisionResult.FailureKind.UNREACHABLE, "decision transport failed")));

        var outcome = ReplayEngine.replay(ReplayCapture.read(root));

        assertTrue(outcome.degraded(),
            "a failed decision must degrade; it must not invent a route");
        assertEquals(Optional.of("decision-failed"), outcome.fallbackCause(),
            "the cause must be traceable, which is the whole point of the failure kind");
        assertTrue(outcome.suggestedId().isEmpty(),
            "a failed decision has no suggestion to record: " + outcome.suggestedId());
    }
}
