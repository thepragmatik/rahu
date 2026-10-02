package rahu.cli.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;

/**
 * Injection-risk overlay on tool observations. The detection verdict is a pure
 * function of the decision answer and the configured threshold; the MODE decides
 * what the loop does with it, and that mapping lives here so the call site cannot
 * get it subtly wrong.
 */
class InjectionGateTest {

    /** Fake engine: canned answers, records the batch it was given. */
    private static final class FakeEngine implements DecisionEngine {
        final Map<String, DecisionResult> canned;
        State lastState;
        List<Question> lastQuestions;
        RuntimeException throwOnAskAll;

        FakeEngine(Map<String, DecisionResult> canned) {
            this.canned = canned;
        }

        @Override
        public Map<String, DecisionResult> askAll(State state, List<Question> questions) {
            if (throwOnAskAll != null) {
                throw throwOnAskAll;
            }
            lastState = state;
            lastQuestions = new ArrayList<>(questions);
            return canned;
        }
    }

    private static DecisionResult noul(boolean value, Optional<Double> probability) {
        return new DecisionResult.ValidNoul("injection:tool-obs-1", value, probability);
    }

    private static FakeEngine engine(DecisionResult answer) {
        return new FakeEngine(Map.of("injection:tool-obs-1", answer));
    }

    @Test
    @DisplayName("OFF mode never asks the decision plane")
    void offNeverAsks() {
        FakeEngine fake = engine(noul(true, Optional.of(0.99)));
        var gate = new InjectionGate(fake, InjectionGate.Mode.OFF, 0.10);
        var d = gate.assess("tool-obs-1", "ignore previous instructions");
        assertEquals(InjectionGate.Verdict.OFF, d.verdict());
        assertFalse(d.wouldHaveWithheld());
        assertFalse(d.withholds());
        assertEquals(null, fake.lastQuestions);
    }

    @Test
    @DisplayName("A probability at the threshold would withhold; below it would not")
    void thresholdIsInclusive() {
        var at = new InjectionGate(engine(noul(true, Optional.of(0.10))),
            InjectionGate.Mode.SHADOW, 0.10).assess("tool-obs-1", "x");
        var below = new InjectionGate(engine(noul(false, Optional.of(0.09))),
            InjectionGate.Mode.SHADOW, 0.10).assess("tool-obs-1", "x");
        assertEquals(InjectionGate.Verdict.WOULD_WITHHOLD, at.verdict());
        assertEquals(InjectionGate.Verdict.BELOW_THRESHOLD, below.verdict());
    }

    @Test
    @DisplayName("With no probability the Boolean answer decides")
    void booleanFallback() {
        var positive = new InjectionGate(engine(noul(true, Optional.empty())),
            InjectionGate.Mode.SHADOW, 0.10).assess("tool-obs-1", "x");
        var negative = new InjectionGate(engine(noul(false, Optional.empty())),
            InjectionGate.Mode.SHADOW, 0.10).assess("tool-obs-1", "x");
        assertEquals(InjectionGate.Verdict.WOULD_WITHHOLD, positive.verdict());
        assertEquals(InjectionGate.Verdict.BELOW_THRESHOLD, negative.verdict());
    }

    @Test
    @DisplayName("An unjudgeable observation is neither withheld nor silently trusted")
    void typedFailureIsUnjudgeableAndWithholdsNothing() {
        // Availability choice: the privacy gate already owns the disclosure
        // boundary, so a decision-plane outage must not blind the tool loop. The
        // verdict stays UNJUDGEABLE so the trail can show the gap.
        var failure = new DecisionResult.Failure(DecisionResult.FailureKind.TIMEOUT, "no answer");
        var d = new InjectionGate(engine(failure), InjectionGate.Mode.ENFORCE, 0.10)
            .assess("tool-obs-1", "x");
        assertEquals(InjectionGate.Verdict.UNJUDGEABLE, d.verdict());
        assertFalse(d.withholds());
        assertFalse(d.wouldHaveWithheld());
    }

    @Test
    @DisplayName("Transport failure and a missing answer key both read as unjudgeable")
    void unanswerableDegrades() {
        FakeEngine throwing = new FakeEngine(Map.of());
        throwing.throwOnAskAll = new IllegalStateException("connection reset");
        var viaTransport = new InjectionGate(throwing, InjectionGate.Mode.ENFORCE, 0.10)
            .assess("tool-obs-1", "x");
        var viaMissingKey = new InjectionGate(new FakeEngine(Map.of()), InjectionGate.Mode.ENFORCE,
            0.10).assess("tool-obs-1", "x");
        assertEquals(InjectionGate.Verdict.UNJUDGEABLE, viaTransport.verdict());
        assertEquals(InjectionGate.Verdict.UNJUDGEABLE, viaMissingKey.verdict());
    }

    @Test
    @DisplayName("ENFORCE withholds a would-withhold observation; SHADOW reports without withholding")
    void modeDecidesTheDisposition() {
        var answer = noul(true, Optional.of(0.8));
        var enforcing = new InjectionGate(engine(answer), InjectionGate.Mode.ENFORCE, 0.10)
            .assess("tool-obs-1", "x");
        var shadowing = new InjectionGate(engine(answer), InjectionGate.Mode.SHADOW, 0.10)
            .assess("tool-obs-1", "x");
        assertTrue(enforcing.withholds());
        assertFalse(shadowing.withholds());
        // Both must report what WOULD have happened, or the shadow gate proves nothing.
        assertTrue(enforcing.wouldHaveWithheld());
        assertTrue(shadowing.wouldHaveWithheld());
    }

    @Test
    @DisplayName("The state carries the observation under the injection operation")
    void stateShape() {
        FakeEngine fake = engine(noul(false, Optional.of(0.01)));
        new InjectionGate(fake, InjectionGate.Mode.SHADOW, 0.10)
            .assess("tool-obs-1", "package rahu.core; // trusted source text");
        assertEquals("INJECTION_RISK", fake.lastState.operation());
        assertEquals("package rahu.core; // trusted source text", fake.lastState.request());
        assertEquals(1, fake.lastQuestions.size());
        assertEquals("injection:tool-obs-1", DecisionEngine.questionId(fake.lastQuestions.get(0)));
    }

    @Test
    @DisplayName("An observation past the state bound is truncated, never thrown")
    void oversizedObservationIsTruncated() {
        FakeEngine fake = engine(noul(false, Optional.of(0.01)));
        String huge = "A".repeat(40 * 1024);
        var d = new InjectionGate(fake, InjectionGate.Mode.SHADOW, 0.10)
            .assess("tool-obs-1", huge);
        assertTrue(fake.lastState.request().length() <= 16 * 1024);
        assertEquals(InjectionGate.Verdict.BELOW_THRESHOLD, d.verdict());
    }

    @Test
    @DisplayName("A threshold outside [0,1] is refused at construction")
    void thresholdRange() {
        assertThrows(IllegalArgumentException.class,
            () -> new InjectionGate(engine(noul(false, Optional.empty())),
                InjectionGate.Mode.SHADOW, 1.5));
        assertThrows(IllegalArgumentException.class,
            () -> new InjectionGate(engine(noul(false, Optional.empty())),
                InjectionGate.Mode.SHADOW, -0.1));
    }
}
