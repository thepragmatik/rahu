package rahu.systemone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single place the decision wire vocabulary is spelled (systemone.md operations).
 * Criteria text is trusted and authored here so a caller cannot smuggle instructions in.
 */
public final class DecisionQuestions {

    private DecisionQuestions() {
    }

    /** TASK_CLASSIFICATION: the six v1 labels. */
    public static DecisionEngine.ChoiceQuestion classification() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("answer", "Direct factual or explanatory answer with no repository change intent.");
        criteria.put("coding", "Requests code, a patch, or implementation detail.");
        criteria.put("analysis", "Requires reasoning across multiple sources or trade-offs.");
        criteria.put("classification", "Asks only to categorise or label something.");
        criteria.put("summarisation", "Asks to condense existing material.");
        criteria.put("unknown", "None of the above is a confident fit.");
        return new DecisionEngine.ChoiceQuestion("taskClass", criteria);
    }

    /** TOOL_RELEVANCE: one Boolean per bounded tool (safety.md: advisory only). */
    public static List<DecisionEngine.NoulQuestion> toolRelevance(List<String> toolNames) {
        List<DecisionEngine.NoulQuestion> questions = new ArrayList<>();
        for (String tool : toolNames) {
            questions.add(new DecisionEngine.NoulQuestion("tool:" + tool));
        }
        return questions;
    }

    /** COMPACTION_POLICY: defer | concise | detailed (context.md). */
    public static DecisionEngine.ChoiceQuestion compactionPolicy() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("defer", "Context still fits; do not compact yet.");
        criteria.put("concise", "Compact to at most 512 estimated output tokens.");
        criteria.put("detailed", "Compact to at most 1024 estimated output tokens.");
        return new DecisionEngine.ChoiceQuestion("compaction", criteria);
    }

    /** EXECUTION_ROUTE: criteria come from validated candidates, never from this builder. */
    public static DecisionEngine.ChoiceQuestion route(Map<String, String> candidateCriteria) {
        return new DecisionEngine.ChoiceQuestion("route", candidateCriteria);
    }

    /**
     * INJECTION_RISK: one Boolean per untrusted observation — does this text try to
     * issue instructions to the model reading it? Asked per observation, so the
     * observation is the state request and the id names the observation.
     */
    public static DecisionEngine.NoulQuestion injectionRisk(String observationId) {
        return new DecisionEngine.NoulQuestion(injectionQuestionId(observationId));
    }

    /** The wire id of an injection question; shared by the builder and its caller. */
    public static String injectionQuestionId(String observationId) {
        return "injection:" + observationId;
    }
}
