package rahu.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed generation outcome (ARCHITECTURE.md ModelOutcome): answer, proposed tool
 * batch, or typed failure; usage and continuation envelope kept separate.
 */
public sealed interface ModelOutcome {

    /** Completed answer (possibly with proposed tool calls). */
    record Completed(String answer, List<ToolCall> proposedToolCalls, Usage usage,
        String finishReason, Optional<String> observedModel, Optional<String> observedProvider,
        ContinuationEnvelope continuation) implements ModelOutcome {

        public Completed {
            Objects.requireNonNull(answer, "answer");
            proposedToolCalls = proposedToolCalls == null ? List.of()
                : List.copyOf(proposedToolCalls);
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(finishReason, "finishReason");
            Objects.requireNonNull(continuation, "continuation");
        }

        public boolean hasToolCalls() {
            return !proposedToolCalls.isEmpty();
        }
    }

    /** Typed generation failure (openrouter.md failure contract). */
    record Failed(FailureKind kind, String safeReason, Usage usage) implements ModelOutcome {

        public Failed {
            Objects.requireNonNull(kind, "kind");
            if (safeReason != null && safeReason.length() > 200) {
                safeReason = safeReason.substring(0, 200);
            }
        }

        public enum FailureKind {
            AUTH_REJECTED, INVALID_REQUEST, RATE_LIMITED, TIMEOUT, NETWORK,
        /** Loop-side, not provider-side: a tool call repeated with an unchanged outcome. */
        NO_PROGRESS,
            REFUSAL, EMPTY_RESPONSE, MALFORMED_RESPONSE, SERVER_ERROR, UNKNOWN
        }
    }
}
