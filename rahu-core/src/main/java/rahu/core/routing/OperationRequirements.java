package rahu.core.routing;

/**
 * Trusted operation requirements (routing.md candidate generation step 3): text
 * modality flags, context allowance including tool schemas/output reserve, and
 * whether tools are exposed. Trusted requirements cannot be removed by decisions.
 */
public record OperationRequirements(boolean isTextAnswer, boolean toolsExposed,
    int contextAllowanceTokens, boolean structuredOutputRequired) {

    public OperationRequirements {
        if (contextAllowanceTokens <= 0) {
            throw new IllegalArgumentException("contextAllowanceTokens must be positive");
        }
    }
}
