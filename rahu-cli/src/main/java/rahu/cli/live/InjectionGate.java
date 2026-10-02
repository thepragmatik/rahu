package rahu.cli.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;
import rahu.systemone.DecisionQuestions;

/**
 * Prompt-injection risk overlay for untrusted tool observations (safety.md).
 *
 * <p>The gate is asked about an observation BEFORE that observation is appended to
 * model-visible history. It is an overlay on the existing privacy gate, never a
 * replacement for it: the disclosure boundary is still {@code PrivacyGate}'s job,
 * and an observation the privacy gate refused never reaches this class.
 *
 * <p>Detection is a pure function of the decision answer and the configured
 * threshold. What the caller must DO with a verdict depends on the mode, so that
 * mapping lives in {@link Disposition} and the call site cannot get it subtly
 * wrong:
 *
 * <ul>
 *   <li>{@code OFF} — never asks the decision plane at all.
 *   <li>{@code SHADOW} — judges, reports what would have been withheld, withholds
 *       nothing. This is the mode the guardrail requires before enforcement.
 *   <li>{@code ENFORCE} — a would-withhold observation is replaced with safe
 *       denial metadata; the offending text is never forwarded.
 * </ul>
 *
 * <p>An unjudgeable observation is deliberately NOT withheld. The privacy gate
 * already owns the disclosure boundary, so a decision-plane outage must not blind
 * the tool loop; the verdict stays {@code UNJUDGEABLE} so the trail can show the
 * gap rather than hide it.
 */
public final class InjectionGate {

    /** What to do with a would-withhold observation. */
    public enum Mode {
        OFF, SHADOW, ENFORCE
    }

    /** The detection outcome, independent of mode. */
    public enum Verdict {
        OFF, BELOW_THRESHOLD, WOULD_WITHHOLD, UNJUDGEABLE
    }

    /** Detection verdict plus the configured mode's consequence. */
    public record Disposition(Verdict verdict, Optional<Double> probability, Mode mode) {

        /** True when the observation scored at or above the threshold. */
        public boolean wouldHaveWithheld() {
            return verdict == Verdict.WOULD_WITHHOLD;
        }

        /** True only when the mode enforces: the call site withholds on this alone. */
        public boolean withholds() {
            return mode == Mode.ENFORCE && wouldHaveWithheld();
        }
    }

    /** DecisionEngine.State rejects a request past 16 KiB; observations can exceed it. */
    private static final int MAX_OBSERVATION_CHARS = 16 * 1024;

    /**
     * Per-observation share of the 16 KiB state bound when several observations share
     * one request. The bound is on the REQUEST, so batching N observations divides it
     * between them; without a share, one long observation would consume the whole
     * budget and silently starve its siblings into unjudgeable.
     */
    private static final int MAX_BATCHED_OBSERVATION_CHARS = 4 * 1024;

    private final DecisionEngine decision;
    private final Mode mode;
    private final double threshold;

    public InjectionGate(DecisionEngine decision, Mode mode, double threshold) {
        if (decision == null) {
            throw new IllegalArgumentException("decision engine required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("injection mode required");
        }
        if (Double.isNaN(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("injection threshold in [0,1]: " + threshold);
        }
        this.decision = decision;
        this.mode = mode;
        this.threshold = threshold;
    }

    /**
     * Judges one untrusted observation. Never throws: an unanswerable decision
     * reads as {@link Verdict#UNJUDGEABLE}.
     *
     * @param observationId stable id of the observation, e.g. {@code tool-obs-<callId>}
     * @param observation the untrusted observation text
     */
    public Disposition assess(String observationId, String observation) {
        if (mode == Mode.OFF) {
            return new Disposition(Verdict.OFF, Optional.empty(), mode);
        }
        String questionId = DecisionQuestions.injectionQuestionId(observationId);
        DecisionResult answer;
        try {
            var state = new DecisionEngine.State("INJECTION_RISK", bound(observation), 0.0);
            answer = decision.ask(state, List.of(new DecisionEngine.NoulQuestion(questionId)));
        } catch (RuntimeException e) {
            return new Disposition(Verdict.UNJUDGEABLE, Optional.empty(), mode);
        }
        if (!(answer instanceof DecisionResult.ValidNoul noul)) {
            return new Disposition(Verdict.UNJUDGEABLE, Optional.empty(), mode);
        }
        // With no probability the Boolean answer is the judgment; a positive noul
        // scores as certainty and a negative one as zero.
        double score = noul.probability().orElse(noul.value() ? 1.0 : 0.0);
        Verdict verdict = score >= threshold ? Verdict.WOULD_WITHHOLD : Verdict.BELOW_THRESHOLD;
        return new Disposition(verdict, noul.probability(), mode);
    }

    private static String bound(String observation) {
        if (observation == null) {
            return "";
        }
        return observation.length() <= MAX_OBSERVATION_CHARS ? observation
            : observation.substring(0, MAX_OBSERVATION_CHARS);
    }

    /** One observation to judge, paired with the id its answer is keyed by. */
    public record Observation(String id, String text) {

        public Observation {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("observation id required");
            }
            text = text == null ? "" : text;
        }
    }

    /**
     * Judges several observations in ONE dispatch: the batched sibling of
     * {@link #assess(String, String)}, and the reason a tool turn costs one decision
     * call instead of one per observation. Answers come back keyed by observation id,
     * so the verdicts are the same ones {@code assess} would have produced; only the
     * number of dispatches changes.
     *
     * <p>Two honest costs of sharing a request, both accepted deliberately:
     *
     * <ul>
     *   <li><b>Observations see each other.</b> Untrusted text now travels together, so
     *       one observation could in principle colour the judgment of another. The
     *       per-observation delimiter keeps them textually separate, and the questions
     *       are still one-per-observation — but this is a real reduction in isolation
     *       versus judging each alone, and it is the price of the saved dispatches.
     *   <li><b>The 16 KiB state bound is shared.</b> Each observation is truncated to
     *       {@link #MAX_BATCHED_OBSERVATION_CHARS} so a batch cannot overflow the bound
     *       and throw. A single oversized observation therefore gets less text than it
     *       would alone; {@link #assess} keeps the full 16 KiB for that reason.
     * </ul>
     *
     * <p>Fails closed exactly as the single path does: a transport failure or a
     * missing/failed answer makes that observation {@link Verdict#UNJUDGEABLE} and
     * withholds nothing, and one bad observation never fails its siblings.
     *
     * @return a disposition per input observation, in the same order
     */
    public List<Disposition> assessAll(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        if (mode == Mode.OFF) {
            return observations.stream()
                .map(o -> new Disposition(Verdict.OFF, Optional.empty(), mode)).toList();
        }
        var ids = observations.stream().map(Observation::id).toList();
        var request = batchRequest(observations);
        Map<String, DecisionResult> answers;
        try {
            var state = new DecisionEngine.State("INJECTION_RISK", request, 0.0);
            answers = decision.askAll(state,
                DecisionQuestions.injectionRisk(ids).stream()
                    .map(q -> (DecisionEngine.Question) q).toList());
        } catch (RuntimeException e) {
            return unjudgeableAll(observations.size());
        }
        List<Disposition> out = new ArrayList<>(observations.size());
        for (String id : ids) {
            DecisionResult answer = answers.get(DecisionQuestions.injectionQuestionId(id));
            out.add(dispositionOf(answer));
        }
        return List.copyOf(out);
    }

    /** Per-observation mapping, identical to the single-observation path. */
    private Disposition dispositionOf(DecisionResult answer) {
        if (!(answer instanceof DecisionResult.ValidNoul noul)) {
            return new Disposition(Verdict.UNJUDGEABLE, Optional.empty(), mode);
        }
        double score = noul.probability().orElse(noul.value() ? 1.0 : 0.0);
        Verdict verdict = score >= threshold ? Verdict.WOULD_WITHHOLD : Verdict.BELOW_THRESHOLD;
        return new Disposition(verdict, noul.probability(), mode);
    }

    private List<Disposition> unjudgeableAll(int count) {
        return java.util.Collections.nCopies(count,
            new Disposition(Verdict.UNJUDGEABLE, Optional.empty(), mode));
    }

    /**
     * Joins observations into one request with an explicit per-observation delimiter.
     * The delimiter names the id the question is keyed by, so a decision model reading
     * the batch can tell whose text it is judging. Always under the 16 KiB bound.
     */
    private static String batchRequest(List<Observation> observations) {
        StringBuilder sb = new StringBuilder();
        for (Observation o : observations) {
            sb.append("### observation ").append(o.id()).append('\n');
            String text = o.text();
            sb.append(text.length() <= MAX_BATCHED_OBSERVATION_CHARS ? text
                : text.substring(0, MAX_BATCHED_OBSERVATION_CHARS)).append('\n');
        }
        return sb.toString();
    }
}
