package rahu.systemone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rahu.core.decision.DecisionResult;

/**
 * Decision engine port (systemone.md / extensibility.md): typed bounded
 * questions, typed outcomes. HTTP adapter implements this; fakes in core tests
 * also implement it. Decision models never write summaries or prose.
 */
public interface DecisionEngine {

    /** Bounded untrusted state (systemone.md: 16 KiB UTF-8 bound). */
    record State(String operation, String request, double contextPressure) {

        public State {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation required");
            }
            if (request != null && request.length() > 16 * 1024) {
                throw new IllegalArgumentException("state request exceeds 16 KiB bound");
            }
            if (contextPressure < 0.0 || contextPressure > 1.0) {
                throw new IllegalArgumentException("contextPressure in [0,1]");
            }
        }
    }

    /** Choice question: finite labelled criteria. */
    record ChoiceQuestion(String questionId, Map<String, String> criteria)
        implements Question {

        public ChoiceQuestion {
            criteria = criteria == null ? Map.of() : new LinkedHashMap<>(criteria);
            if (questionId == null || questionId.isBlank() || criteria.isEmpty()) {
                throw new IllegalArgumentException("choice question needs id and criteria");
            }
        }
    }

    /** Boolean (noul) question. */
    record NoulQuestion(String questionId) implements Question {

        public NoulQuestion {
            if (questionId == null || questionId.isBlank()) {
                throw new IllegalArgumentException("noul question needs an id");
            }
        }
    }

    /**
     * Ordered Score question: an ordinal level over a caller-supplied legend
     * (systemone.md: wire type {@code score}). The legend is ordered best-last, so
     * level indexes into it directly and levels from two candidates are comparable
     * ONLY because they were asked against the same legend — a per-candidate legend
     * would make the levels meaningless to compare.
     */
    record ScoreQuestion(String questionId, List<String> legend) implements Question {

        public ScoreQuestion {
            legend = legend == null ? List.of() : List.copyOf(legend);
            if (questionId == null || questionId.isBlank() || legend.isEmpty()) {
                throw new IllegalArgumentException("score question needs id and a legend");
            }
            for (String level : legend) {
                if (level == null || level.isBlank()) {
                    throw new IllegalArgumentException("score legend entries must be named");
                }
            }
        }
    }

    /**
     * Ask a batch of questions over ONE dispatch (systemone.md line 9: independent
     * questions may share a request). Returns one typed result per question id, keyed
     * by question id in batch order. A question without a usable answer maps to a
     * typed Failure for that question alone — it never fails its siblings.
     */
    Map<String, DecisionResult> askAll(State state, List<Question> questions);

    /**
     * Ask a batch and return the FIRST question's result. All questions still travel
     * in the single request; per-question isolation applies. Single-question callers
     * are unchanged by this shape.
     */
    default DecisionResult ask(State state, Iterable<Question> questions) {
        List<Question> batch = new ArrayList<>();
        questions.forEach(batch::add);
        if (batch.isEmpty()) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "no supported questions in request");
        }
        String firstId = questionId(batch.get(0));
        DecisionResult result = askAll(state, batch).get(firstId);
        if (result == null) {
            return new DecisionResult.Failure(DecisionResult.FailureKind.PROTOCOL_ERROR,
                "missing answer for question " + firstId);
        }
        return result;
    }

    /** Marker interface for questions. */
    interface Question {
    }

    /** The wire id of a question. */
    static String questionId(Question q) {
        if (q instanceof ChoiceQuestion c) {
            return c.questionId();
        }
        if (q instanceof NoulQuestion n) {
            return n.questionId();
        }
        if (q instanceof ScoreQuestion s) {
            return s.questionId();
        }
        throw new IllegalArgumentException("unsupported question type: " + q.getClass());
    }
}
