package rahu.cli.live;

import java.util.List;
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
}
