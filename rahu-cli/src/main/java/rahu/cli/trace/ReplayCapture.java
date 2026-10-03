package rahu.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import rahu.core.decision.DecisionResult;
import rahu.core.routing.CandidateSet;
import rahu.core.routing.RouteResolution;
import rahu.core.routing.RouteResolver;
import rahu.core.routing.RoutingMode;

/**
 * Captures the frozen inputs a policy replay needs, and reads them back
 * (observability.md, A11).
 *
 * <p>AUDIT-2026-10-03-e. {@link ReplayEngine} and {@link ReplayOutcome} were
 * written and unit-tested, and nothing in {@code src/main} ever referenced
 * either. The replay half of G06 was recorded PASSED on tests whose subject was
 * unreachable. This class is the missing half: it is the only thing that can
 * produce the {@code replay.json} a replay needs, and {@link TraceCommand} is
 * the only thing that can consume one.
 *
 * <h2>What is captured, and what deliberately is not</h2>
 * The capture holds the <em>routing inputs</em> — the candidate ids, the
 * exclusion list, baseline/fallback, the confidence field and floor, and the
 * recorded decision. That is the exact tuple {@link RouteResolver} consumes, so
 * a replay re-derives the resolution rather than reading it back.
 *
 * <p>It holds no prompt text, no answer text, no tool content and no arguments.
 * That is not incidental: cli.md:50 says local replay "must not dump protected
 * payloads by default", and a replay input file is read by humans and archived.
 * Because only routing inputs are stored, a captured trace stays safe to retain
 * and a replay can be run by someone who never had permission to see the prompt.
 *
 * <h2>Privacy re-scan (privacy.md:27)</h2>
 * privacy.md requires re-scanning generated content before it enters history,
 * replay or export, because answers can <em>repeat or infer</em> protected data.
 * A routing-input capture sidesteps that rather than satisfying it: it never
 * contains generated content at all, so there is nothing to re-scan. The
 * candidate ids and the decision's probability map are model labels and numbers.
 * This is the reason the capture is built from the candidate set rather than
 * from the prompt or the transcript.
 *
 * <h2>OnFailure and capture interaction</h2>
 * Payload capture is opt-in via {@code trace.capture = "payloads"}. Under the
 * default {@code metadata} nothing is written here and a replay reports
 * UNAVAILABLE — never fabricated (observability.md:37).
 */
public final class ReplayCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** File name inside a run directory, beside {@code events.jsonl}. */
    public static final String FILE = "replay.json";

    public static final int SCHEMA_VERSION = 1;

    private ReplayCapture() {
    }

    /**
     * Writes the frozen routing inputs for one turn.
     *
     * <p>The resolution itself is NOT stored. Replay's value is re-deriving it
     * through {@link RouteResolver}; persisting the outcome next to the inputs
     * would invite a future caller to compare against a stored answer and report
     * agreement without re-running anything, which is a test that cannot fail.
     *
     * @param decision the recorded decision, if one was made; empty for a turn
     *     that degraded without asking, which replay re-derives as such
     */
    public static Path write(Path runDirectory, CandidateSet candidates, RoutingMode mode,
        RouteResolver.ResolutionInput input, Optional<DecisionResult> decision)
        throws java.io.IOException {

        Files.createDirectories(runDirectory);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("capture", "routing-inputs");

        ArrayNode ids = root.putArray("candidates");
        for (var candidate : candidates.candidates()) {
            ids.add(candidate.id());
        }
        // Exclusions are part of the input, not decoration: RouteResolver consults
        // them, and a replay that dropped them would resolve over a larger set than
        // the live turn did and could report agreement about a different question.
        ArrayNode exclusions = root.putArray("exclusions");
        for (CandidateSet.Exclusion exclusion : candidates.exclusions()) {
            ObjectNode node = exclusions.addObject();
            node.put("ref", exclusion.ref());
            node.put("reason", exclusion.reason());
        }

        ObjectNode routing = root.putObject("routing");
        routing.put("mode", mode.name());
        routing.put("baselineId", input.baselineId().orElse(null));
        routing.put("fallbackId", input.fallbackId().orElse(null));
        routing.put("confidenceField", input.confidenceField());
        routing.put("confidenceFloor", input.confidenceFloor());

        root.set("decision", encodeDecision(decision.orElse(null)));

        Path file = runDirectory.resolve(FILE);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    /**
     * Encodes a decision for the capture.
     *
     * <p>Only the fields the resolver reads are written. The {@code questionId}
     * and confidence semantics are omitted because routing never consults them,
     * and omitting them keeps the capture minimal — every stored field has a
     * reader.
     */
    private static JsonNode encodeDecision(DecisionResult decision) {
        if (decision == null) {
            return null;
        }
        if (decision instanceof DecisionResult.ValidChoice choice) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("kind", "ValidChoice");
            node.put("chosenLabel", choice.chosenLabel());
            ObjectNode probabilities = node.putObject("probabilities");
            // Sorted by key so two captures of the same decision are byte-equal and
            // a diff between two runs reflects a policy change, not map iteration
            // order.
            choice.probabilities().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> probabilities.put(e.getKey(), e.getValue()));
            return node;
        }
        if (decision instanceof DecisionResult.Failure failure) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("kind", "Failure");
            node.put("failureKind", failure.kind().name());
            node.put("safeReason", failure.safeReason());
            return node;
        }
        // ValidNoul/ValidScore are not routing decisions. Encoding them as an
        // unknown kind would let a hand-edited capture replay as something it is
        // not, so they are refused rather than approximated.
        throw new IllegalArgumentException("not a routing decision: " + decision.getClass()
            .getSimpleName());
    }

    /** The frozen inputs read back from a capture, ready for {@link ReplayEngine}. */
    public record Frozen(
        CandidateSet candidates,
        RoutingMode mode,
        RouteResolver.ResolutionInput input,
        Optional<DecisionResult> decision) {
    }

    /**
     * Reads a capture back.
     *
     * <p>Every field is validated rather than trusted. A capture is a file on
     * disk that a human may have edited, so an absent or malformed field yields
     * UNAVAILABLE-with-a-reason at the call site instead of a resolution computed
     * from defaults that were never recorded.
     */
    public static Frozen read(Path runDirectory) throws java.io.IOException {
        Path file = runDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            // AUDIT-2026-10-03-h: this used to assert the cause unconditionally -
            // "trace.capture=payloads is required" - for a run whose config DID say
            // payloads. The true reason can be that the mode never routes (offline
            // records no RouteResolved and captures no inputs), and telling an
            // operator who already set the flag to set it again is worse than saying
            // nothing: it sends them to edit a correct config.
            //
            // So state the OBSERVED fact, list the possible causes, and let the trace
            // itself discriminate: a payloads run writes replay.json, so its absence
            // alongside a RouteResolved event means capture did not happen.
            boolean routed = false;
            Path events = runDirectory.resolve(TraceFiles.EVENTS);
            if (Files.isRegularFile(events)) {
                try {
                    for (String line : Files.readAllLines(events)) {
                        if (line.contains("\"RouteResolved\"")) {
                            routed = true;
                            break;
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // Unreadable events.jsonl: fall through to the generic message
                    // rather than guess.
                }
            }
            throw new java.io.FileNotFoundException("no capture at " + file
                + (routed
                    ? "; this run recorded RouteResolved, so trace.capture was not"
                      + " 'payloads' at run time (a later config change does not"
                      + " apply to an existing run)"
                    : "; this run recorded no RouteResolved, so it had no routing"
                      + " inputs to capture (offline mode routes nothing) -"
                      + " trace.capture=payloads is necessary but not sufficient"));
        }
        JsonNode root = MAPPER.readTree(file.toFile());
        int version = root.path("schemaVersion").asInt(-1);
        if (version != SCHEMA_VERSION) {
            throw new java.io.IOException("unsupported capture schemaVersion " + version
                + " (this build reads " + SCHEMA_VERSION + ")");
        }

        var models = new java.util.ArrayList<rahu.core.ExecutionCandidate>();
        for (JsonNode id : root.path("candidates")) {
            models.add(syntheticCandidate(id.asText()));
        }
        var exclusions = new java.util.ArrayList<CandidateSet.Exclusion>();
        for (JsonNode node : root.path("exclusions")) {
            exclusions.add(new CandidateSet.Exclusion(node.path("ref").asText(),
                node.path("reason").asText("unrecorded")));
        }

        JsonNode routing = root.path("routing");
        if (routing.isMissingNode()) {
            throw new java.io.IOException("capture has no routing block");
        }
        String confidenceField = routing.path("confidenceField").asText(null);
        double floor = routing.path("confidenceFloor").asDouble(Double.NaN);
        if (confidenceField == null || !Double.isFinite(floor)) {
            throw new java.io.IOException(
                "capture is missing confidenceField/confidenceFloor; refusing to"
                    + " substitute defaults that were never recorded");
        }

        RoutingMode mode;
        try {
            mode = RoutingMode.valueOf(routing.path("mode").asText(""));
        } catch (IllegalArgumentException e) {
            throw new java.io.IOException("capture has an unknown routing mode: "
                + routing.path("mode").asText());
        }

        var input = new RouteResolver.ResolutionInput(
            new CandidateSet(models, exclusions),
            Optional.ofNullable(routing.path("baselineId").textValue()),
            Optional.ofNullable(routing.path("fallbackId").textValue()),
            confidenceField, floor);

        return new Frozen(input.candidateSet(), mode, input, decodeDecision(root.path("decision")));
    }

    /**
     * Rebuilds a candidate from its id alone.
     *
     * <p>A replay resolves ids, so nothing else about the candidate is read by the
     * resolver. The remaining fields are reconstructed to satisfy the record's
     * invariants — the id must contain the reasoning policy separator, and the
     * policy is split from it rather than invented, so a replay cannot accidentally
     * claim a different reasoning policy than the live turn used.
     */
    private static rahu.core.ExecutionCandidate syntheticCandidate(String id) {
        int at = id.indexOf('@');
        if (at <= 0 || at == id.length() - 1) {
            throw new IllegalArgumentException("captured candidate id lacks a reasoning"
                + " policy suffix: " + id);
        }
        String alias = id.substring(0, at);
        String suffix = id.substring(at + 1);
        return new rahu.core.ExecutionCandidate(id, new rahu.core.ModelRef(alias),
            decodePolicy(suffix), java.util.Map.of(), "captured", "captured");
    }

    /**
     * Decodes a candidate id's policy suffix, using the SAME vocabulary
     * CandidateFactory encodes: "default", or an Effort name lowercased.
     *
     * <p>Decoding with a private vocabulary would be a quiet correctness trap: an
     * id the live run could not produce would still replay happily, so the
     * replay would report agreement about a candidate that never existed. Unknown
     * suffixes are refused instead.
     */
    private static rahu.core.ReasoningPolicy decodePolicy(String suffix) {
        if ("default".equals(suffix)) {
            return rahu.core.ReasoningPolicy.ProviderDefault.INSTANCE;
        }
        for (rahu.core.ReasoningPolicy.Effort effort : rahu.core.ReasoningPolicy.Effort.values()) {
            if (effort.name().toLowerCase(java.util.Locale.ROOT).equals(suffix)) {
                return rahu.core.ReasoningPolicy.ExplicitEffort.of(effort);
            }
        }
        throw new IllegalArgumentException("captured candidate id carries an unknown"
            + " reasoning policy suffix: " + suffix);
    }

    private static Optional<DecisionResult> decodeDecision(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        String kind = node.path("kind").asText("");
        if ("ValidChoice".equals(kind)) {
            java.util.Map<String, Double> probabilities = new java.util.LinkedHashMap<>();
            node.path("probabilities").fields().forEachRemaining(
                e -> probabilities.put(e.getKey(), e.getValue().asDouble()));
            return Optional.of(new DecisionResult.ValidChoice("captured",
                node.path("chosenLabel").asText(""), probabilities,
                Optional.empty(), "captured"));
        }
        if ("Failure".equals(kind)) {
            return Optional.of(new DecisionResult.Failure(
                DecisionResult.FailureKind.valueOf(node.path("failureKind").asText("UNKNOWN")),
                node.path("safeReason").asText("captured failure")));
        }
        throw new IllegalArgumentException("unknown captured decision kind: " + kind);
    }

    /**
     * Compares a re-derived resolution with a live one, for reporting only.
     *
     * <p>Exposed so the command can say what matched without reimplementing the
     * comparison inline, and so the equality rule is testable on its own.
     */
    public static boolean agrees(RouteResolution live, ReplayOutcome replayed) {
        return live.suggestedId().orElse(null)
            .equals(replayed.suggestedId().orElse(null))
            && live.executedId().orElse(null).equals(replayed.executedId().orElse(null))
            && live.degraded() == replayed.degraded()
            && live.terminalReason().map(Enum::name).orElse(null)
                .equals(replayed.terminalReason().orElse(null));
    }
}