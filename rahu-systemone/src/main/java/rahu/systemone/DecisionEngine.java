package rahu.systemone;

import java.util.LinkedHashMap;
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

    /** Ask a batch of questions; returns typed per-question results in order. */
    DecisionResult ask(State state, Iterable<Question> questions);

    /** Marker interface for questions. */
    interface Question {
    }
}
