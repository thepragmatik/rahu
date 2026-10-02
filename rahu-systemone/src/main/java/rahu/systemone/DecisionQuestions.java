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

    /**
     * INJECTION_RISK for a batch: one Boolean per observation, all in ONE dispatch.
     * Same questions {@link #injectionRisk(String)} would build individually; the
     * batch form exists so a tool turn with several observations costs one dispatch
     * instead of one each. Per-question isolation still applies, so an unanswerable
     * observation never fails its siblings.
     */
    public static List<DecisionEngine.NoulQuestion> injectionRisk(List<String> observationIds) {
        List<DecisionEngine.NoulQuestion> questions = new ArrayList<>();
        for (String observationId : observationIds) {
            questions.add(injectionRisk(observationId));
        }
        return questions;
    }

    /** The wire id of an injection question; shared by the builder and its caller. */
    public static String injectionQuestionId(String observationId) {
        return "injection:" + observationId;
    }

    /**
     * The shared relevance legend. Every candidate is scored against THIS legend so
     * levels compare; a per-candidate legend would make the numbers incomparable and
     * the rerank meaningless.
     */
    public static final List<String> RELEVANCE_LEGEND = List.of(
        "irrelevant to the query",
        "topically related but does not satisfy the query",
        "satisfies the query",
        "the single best match for the query");

    /**
     * RELEVANCE: one ordered Score question per search candidate, so the results can be
     * reordered by relevance instead of left in filesystem order. Batched into one
     * dispatch by {@link #relevance(List)} for the same reason injection risk is.
     *
     * @param candidateIds stable ids of the candidates, e.g. {@code src/A.java:12}
     */
    public static List<DecisionEngine.ScoreQuestion> relevance(List<String> candidateIds) {
        List<DecisionEngine.ScoreQuestion> questions = new ArrayList<>();
        for (String candidateId : candidateIds) {
            questions.add(new DecisionEngine.ScoreQuestion(relevanceQuestionId(candidateId),
                RELEVANCE_LEGEND));
        }
        return questions;
    }

    /** The wire id of a relevance question; shared by the builder and its caller. */
    public static String relevanceQuestionId(String candidateId) {
        return "relevance:" + candidateId;
    }
}
