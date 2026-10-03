package rahu.cli.config;

/**
 * The operational defaults A01 requires to be documented, in one authoritative place.
 *
 * <p>AUDIT-2026-10-03-af. These numbers used to be bare literals inside production
 * {@code if}-expressions, and one of them ({@code 8192}) was written out three times in three
 * different files. Nothing tied them to the spec, to the generated schema, or to each other,
 * so "defaults are documented" was true only in the sense that a default existed.
 *
 * <p>The values themselves are unchanged - this is a relocation, not a re-tuning. What changed
 * is that a default can now be named once and read by the loader's consumers, the schema
 * generator and {@code OperationalDefaultsTest}, which is what makes the agreement checkable
 * instead of aspirational.
 *
 * <p>Both live turn paths resolve these independently ({@code chat} through
 * {@code LiveTurnDriver.run()}, {@code run} through {@code LiveTurnDriver.turn()}), so a
 * divergence here would show up on one command and not the other.
 */
public final class OperationalDefaults {

    private OperationalDefaults() {
    }

    /**
     * Prompt budget in tokens when {@code context.maxPromptTokens} is unset.
     *
     * <p>Deliberately modest: it is the ceiling the router packs against before deciding
     * whether a turn needs compaction, so an over-generous value delays the first compaction
     * until the prompt is already too large to fit.
     */
    public static final int CONTEXT_ALLOWANCE_TOKENS = 8192;

    /** Ceiling on {@code routing.maximumCandidates} when unset. */
    public static final int MAX_CANDIDATES = 32;

    /**
     * Floor on the routing confidence signal when {@code routing.confidenceFloor} is unset.
     *
     * <p>This is 0.0 and it is a real decision, not an oversight. configuration.md once
     * described a 0.65 {@code chosen_probability} gate as the default; the code has always
     * applied 0. A floor of 0.65 rejects a route whose chosen action looks unconfident, which
     * is a concentration heuristic, and routing.md calls its own 0.65 "a provisional
     * concentration gate, not an accuracy guarantee". Shipping it as the default would mean
     * every run without an explicit {@code confidenceFloor} silently discards low-probability
     * choices. 0.0 keeps the documented shadow behaviour: the kernel proposes, and the
     * operator's configured floor is what gates. The spec now records this.
     */
    public static final double CONFIDENCE_FLOOR = 0.0;

    /** Completion ceiling for one generation when {@code agent.maxCompletionTokens} is unset. */
    public static final int MAX_COMPLETION_TOKENS = 2048;

    /** Candidates re-ranked by the tool-relevance reranker when unset. */
    public static final int RERANK_CANDIDATES = 20;

    /**
     * Bytes of tool output retained when {@code tools.resultBytes} is unset.
     *
     * <p>64 KiB, and this is the value {@link rahu.cli.config.ConfigLoader} has always
     * substituted. It looks like a bug at first glance because
     * {@code rahu.core.tools.WorkspaceTools} also declares a {@code DEFAULT_RESULT_BYTES},
     * and that one is {@code 64 * 1024} - the same number. Two constants, one meaning; the
     * core one is the fallback for a directly-constructed tools object, the config one is the
     * fallback for a loaded config, and they agree. Recorded here so the next reader does not
     * "fix" one of them into a 64x divergence.
     */
    public static final int TOOL_RESULT_BYTES = 65536;

    /**
     * The per-run money cap when {@code agent.maxCostUsd} is unset.
     *
     * <p>Zero means "no spend ceiling is enforced from configuration alone"; the provider-side
     * limits still apply. It is deliberately not folded into {@link #MAX_COMPLETION_TOKENS}'s
     * neighbourhood because it is money rather than tokens, and
     * {@code OperationalDefaultsTest} asserts it stays absent from the token table so that
     * raising it has to be a conscious edit.
     */
    public static final java.math.BigDecimal MAX_COST_USD = java.math.BigDecimal.ZERO;
}
