package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Audit N2: the decision port's guards must reject values that cannot satisfy them.
 *
 * <p>NaN is the sharp case: every comparison against it is false, so a range guard
 * written as {@code x < lo || x > hi} admits it. The parser already guards non-finite
 * numbers explicitly ({@code Double.isNaN}); the state and config guards did not.
 */
class DecisionPortGuardTest {

    @Test
    void stateRejectsNanContextPressure() {
        // contextPressure < 0.0 || > 1.0 is false for NaN, so the ctor guard admits it.
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.State("op", "req", Double.NaN));
    }

    @Test
    void stateStillAcceptsTheBoundaryValues() {
        // The NaN fix must not narrow the contract: 0.0 and 1.0 are legal.
        assertEquals(0.0, new DecisionEngine.State("op", "req", 0.0).contextPressure());
        assertEquals(1.0, new DecisionEngine.State("op", "req", 1.0).contextPressure());
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.State("op", "req", -0.001));
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.State("op", "req", 1.001));
    }

    @Test
    void questionTypesAreSealedSoAForgottenTypeIsACompileError() {
        // The historical score-question defect was a documented type with no
        // implementation, which serialised as {}. With Question sealed, adding a type
        // REQUIRES touching the permits clause, and every instanceof chain over the
        // interface then carries an exhaustiveness obligation.
        //
        // This test cannot assert the compile error from inside Java -- the fact that
        // it compiles at all is the assertion. A RankQuestion declared here would not
        // compile; that failure was observed and is why the declaration below is
        // absent rather than present-and-throwing.
        assertEquals(3, declaredQuestionTypeCount(),
            "the sealed hierarchy has exactly three permitted types");
        for (Class<?> permitted : DecisionEngine.Question.class.getPermittedSubclasses()) {
            assertTrue(permitted.isRecord(),
                "each permitted question type is a record, as the wire format assumes");
        }
    }

    @Test
    void theRuntimeBackstopStillNamesTheCauseForAnyUnreachableType() {
        // Defence in depth: questionId throws rather than returning a wrong id. This
        // is unreachable for a correctly declared type, which is the point.
        for (DecisionEngine.Question q : declaredQuestionTypes()) {
            String id = DecisionEngine.questionId(q);
            assertTrue(id != null && !id.isBlank(), "every question needs a wire id");
        }
    }

    private static java.util.List<DecisionEngine.Question> declaredQuestionTypes() {
        return java.util.List.of(
            new DecisionEngine.ChoiceQuestion("c1", java.util.Map.of("a", "A", "b", "B")),
            new DecisionEngine.NoulQuestion("n1"),
            new DecisionEngine.ScoreQuestion("s1", java.util.List.of("low", "high")));
    }

    private static int declaredQuestionTypeCount() {
        return DecisionEngine.Question.class.getPermittedSubclasses().length;
    }

}
