package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RELEVANCE (search rerank) question builders and the Score question type.
 *
 * <p>systemone.md lists Score as a supported question type with wire type
 * {@code score}, but until now the port had no ScoreQuestion and ValidScore was
 * unreachable. These tests pin both the type and the builders.
 */
class SearchRelevanceQuestionTest {

    /** The real shared legend, not a copy: these tests assert the constant's shape. */
    private static final List<String> LEGEND = DecisionQuestions.RELEVANCE_LEGEND;

    @Test
    @DisplayName("A score question carries an ordered legend and maps to the score wire id")
    void scoreQuestionIsTyped() {
        var q = new DecisionEngine.ScoreQuestion("relevance:src/A.java:12", LEGEND);
        assertEquals("relevance:src/A.java:12",
            DecisionEngine.questionId(q),
            "a score question must resolve to its own id, not throw as unknown");
        assertEquals(LEGEND, q.legend());
    }

    @Test
    @DisplayName("An empty legend or blank id is refused: an unlegended score is meaningless")
    void scoreQuestionRefusesUnusableInput() {
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.ScoreQuestion("x", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.ScoreQuestion("  ", LEGEND));
        assertThrows(IllegalArgumentException.class,
            () -> new DecisionEngine.ScoreQuestion(null, LEGEND));
    }

    @Test
    @DisplayName("Relevance is one score question per candidate, all built alike")
    void relevanceIsOnePerCandidate() {
        var questions = DecisionQuestions.relevance(List.of("src/A.java:12", "src/B.java:3"));
        assertEquals(2, questions.size());
        assertEquals("relevance:src/A.java:12", DecisionEngine.questionId(questions.get(0)));
        assertEquals("relevance:src/B.java:3", DecisionEngine.questionId(questions.get(1)));
        for (var q : questions) {
            assertEquals(LEGEND, ((DecisionEngine.ScoreQuestion) q).legend(),
                "every candidate is scored against the SAME legend, or levels do not compare");
        }
    }

    @Test
    @DisplayName("The relevance question id is shared by the builder and its caller")
    void questionIdIsShared() {
        assertEquals("relevance:src/A.java:12",
            DecisionQuestions.relevanceQuestionId("src/A.java:12"));
    }

    @Test
    @DisplayName("A score level outside the legend is refused at construction")
    void validScoreRejectsOutOfRangeLevel() {
        // Guards the failure mode where an off-by-one level silently sorts last
        // instead of being reported.
        assertThrows(IllegalArgumentException.class,
            () -> new rahu.core.decision.DecisionResult.ValidScore("q", 4, LEGEND));
        assertThrows(IllegalArgumentException.class,
            () -> new rahu.core.decision.DecisionResult.ValidScore("q", -1, LEGEND));
        assertTrue(new rahu.core.decision.DecisionResult.ValidScore("q", 3, LEGEND).level() == 3);
    }

    @Test
    @DisplayName("Choice questions are unaffected by adding the score type")
    void choiceStillRoundTrips() {
        Map<String, String> criteria = Map.of("a", "first");
        var q = new DecisionEngine.ChoiceQuestion("route", criteria);
        assertEquals("route", DecisionEngine.questionId(q));
    }
}