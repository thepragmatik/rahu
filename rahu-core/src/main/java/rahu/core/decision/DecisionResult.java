package rahu.core.decision;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Normalised decision outcome (systemone.md): validated typed result or typed
 * failure. Failures are data, never exceptions to be repaired by a parser.
 */
public sealed interface DecisionResult {

    /** Validated Choice answer with optional probabilities. */
    record ValidChoice(
        String questionId,
        String chosenLabel,
        Map<String, Double> probabilities,
        Optional<Double> rawConfidence,
        String confidenceSemantics) implements DecisionResult {

        public ValidChoice {
            probabilities = Map.copyOf(probabilities);
        }
    }

    /** Validated Boolean (noul) judgment. */
    record ValidNoul(String questionId, boolean value, Optional<Double> probability)
        implements DecisionResult {
    }

    /** Validated Score judgment with zero-based level and legend. */
    record ValidScore(String questionId, int level, java.util.List<String> legend)
        implements DecisionResult {

        public ValidScore {
            java.util.Objects.requireNonNull(legend, "legend");
            legend = List.copyOf(legend);
            if (level < 0 || (!legend.isEmpty() && level >= legend.size())) {
                throw new IllegalArgumentException("score level out of legend range: " + level);
            }
        }
    }

    /** Typed failure kinds (routing.md fallback table). */
    /**
     * Why a decision could not be produced.
     *
     * <p>AUDIT-2026-10-03-t: {@link #UNREACHABLE} and {@link #AMBIGUOUS} exist because
     * the adapter used to report every {@code IOException} as {@code TIMEOUT}, which
     * merged two different facts. A refused connection proves the service never saw the
     * request, so a retry cannot duplicate a billed call; a read timeout does not, so it
     * might. systemone.md:48 requires exactly this: "Uncertain transport outcomes
     * remain traceable even if the decision service is nominally side-effect-free
     * because billing may have occurred."
     *
     * <p>The decision port has no ledger or cost accounting (the reservation in
     * {@code LiveTurnDriver} covers generation only), so nothing downstream re-derives
     * this distinction. It can only exist here, at the boundary.
     */
    enum FailureKind {
        /** The request was never delivered: refused, unresolvable host, connect timeout. */
        UNREACHABLE,
        /** The request may have been processed and billed; a blind retry may duplicate it. */
        AMBIGUOUS,
        /** The service answered, but not with something this contract accepts. */
        PROTOCOL_ERROR,
        TIMEOUT, MALFORMED, UNSUPPORTED, CANCELLED, UNKNOWN
    }

    /** Typed decision failure. */
    record Failure(FailureKind kind, String safeReason) implements DecisionResult {
        public Failure {
            if (safeReason != null && safeReason.length() > 200) {
                safeReason = safeReason.substring(0, 200);
            }
        }
    }

    /** Convenience for tests and fakes: a valid choice with exact probabilities. */
    static ValidChoice validChoice(String label, Map<String, Double> probabilities) {
        return new ValidChoice("q", label, probabilities, Optional.empty(), "none");
    }

    /** Label-set membership check used by the resolver. */
    static boolean labelsCover(Set<String> candidateIds, Map<String, Double> probabilities) {
        return candidateIds.equals(probabilities.keySet());
    }
}
