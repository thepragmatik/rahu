package rahu.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Question builders are the single place the wire vocabulary is spelled (DRY). */
class DecisionQuestionsTest {

    @Test
    @DisplayName("Classification offers exactly the six v1 labels")
    void classificationLabels() {
        var q = DecisionQuestions.classification();
        assertEquals("taskClass", q.questionId());
        assertEquals(6, q.criteria().size());
        assertTrue(q.criteria().containsKey("unknown"));
    }

    @Test
    @DisplayName("Tool relevance is one noul per tool, never a choice over tools")
    void relevanceIsPerTool() {
        var questions = DecisionQuestions.toolRelevance(List.of("workspace.read", "workspace.search"));
        assertEquals(2, questions.size());
        assertTrue(questions.get(0) instanceof DecisionEngine.NoulQuestion);
        assertEquals("tool:workspace.read", questions.get(0).questionId());
    }

    @Test
    @DisplayName("Compaction policy offers exactly defer/concise/detailed")
    void compactionLabels() {
        var q = DecisionQuestions.compactionPolicy();
        assertEquals("compaction", q.questionId());
        assertEquals(3, q.criteria().size());
        assertTrue(q.criteria().keySet().containsAll(List.of("defer", "concise", "detailed")));
    }

    @Test
    @DisplayName("Route criteria are supplied by the caller, not invented here")
    void routeUsesCallerCandidates() {
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("nemo@default", "cheap fast model");
        candidates.put("qwen@default", "cheap long-context model");
        var q = DecisionQuestions.route(candidates);
        assertEquals("route", q.questionId());
        assertEquals(candidates.keySet(), q.criteria().keySet());
    }
}
