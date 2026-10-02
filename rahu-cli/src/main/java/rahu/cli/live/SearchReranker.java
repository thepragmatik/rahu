package rahu.cli.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rahu.core.decision.DecisionResult;
import rahu.systemone.DecisionEngine;
import rahu.systemone.DecisionQuestions;

/**
 * Optional relevance rerank for search observations (context.md ordering).
 *
 * <p>{@code WorkspaceTools.search} returns matches in filesystem order, which is
 * alphabetical, not useful. This asks the decision plane to score each candidate and
 * reorders them, so the lines most likely to answer the question come first.
 *
 * <p><b>This reorders and nothing else.</b> The privacy gate has already admitted this
 * text and the reranker runs downstream of it, so a reranker that dropped or invented a
 * line would change what the model can see with no second check. Membership is therefore
 * preserved exactly: a candidate with no usable score keeps its place rather than being
 * dropped, and no candidate is ever added. Scoring can only move a line up or down.
 *
 * <p>Modes mirror {@link InjectionGate}, because both are optional decision-plane
 * overlays on model-visible text and the same evidence rule applies:
 *
 * <ul>
 *   <li>{@code OFF} — never asks; costs nothing.
 *   <li>{@code SHADOW} — scores and reports the order it would have applied, applies
 *       nothing. The mode to run in before enabling it.
 *   <li>{@code ENFORCE} — applies the order.
 * </ul>
 *
 * <p>Honest costs, accepted deliberately:
 *
 * <ul>
 *   <li><b>It costs a decision call per search.</b> One dispatch per search, batched
 *       across candidates — but still a dispatch a search without rerank would not make.
 *       Hence OFF costing nothing, and the candidate cap.
 *   <li><b>The candidate cap is lossy for quality, not for content.</b> Only the first
 *       {@code maxCandidates} are scored; the rest keep filesystem order at the tail.
 *       Nothing is dropped, but a strong match below the cap cannot be promoted. The cap
 *       exists because {@link DecisionEngine.State} refuses a request past 16 KiB, and
 *       scoring every candidate of a 100-match search would exceed it.
 *   <li><b>Relevance is a judgement about a query, not a fact about the file.</b> The
 *       same hit set scores differently for a different question, so a rerank is only
 *       reproducible alongside the query that produced it.
 * </ul>
 *
 * <p>Fails soft to filesystem order. Unlike the injection gate this withholds nothing —
 * there is no safety claim to uphold, only ordering — so an unreachable decision plane
 * degrades the ranking rather than the tool call.
 */
public final class SearchReranker {

    /** What to do with the scored order. */
    public enum Mode {
        OFF, SHADOW, ENFORCE
    }

    /** The detection outcome, independent of mode. */
    public enum Verdict {
        OFF, RERANKED, UNJUDGEABLE
    }

    /**
     * @param ordered what the model sees: the scored order in ENFORCE, filesystem order
     *     otherwise. Never differs from {@code proposed} in membership, only in order.
     * @param proposed the order the scores imply, whatever the mode applied. In SHADOW
     *     this is the evidence that the rerank was worth enabling; in OFF it is the input.
     */
    public record Result(List<String> ordered, List<String> proposed, Verdict verdict) {

        public Result {
            ordered = List.copyOf(ordered);
            proposed = List.copyOf(proposed);
        }
    }

    /** DecisionEngine.State refuses a request past 16 KiB; a search body can exceed it. */
    private static final int MAX_REQUEST_CHARS = 16 * 1024;

    /** The cap ceiling, so a config typo cannot ask for an unbounded batch. */
    private static final int MAX_CANDIDATE_LIMIT = 1000;

    private final DecisionEngine decision;
    private final Mode mode;
    private final int maxCandidates;

    public SearchReranker(DecisionEngine decision, Mode mode, int maxCandidates) {
        if (decision == null) {
            throw new IllegalArgumentException("decision engine required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("rerank mode required");
        }
        if (maxCandidates <= 0 || maxCandidates > MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException(
                "maxCandidates must be within 1-" + MAX_CANDIDATE_LIMIT + ": " + maxCandidates);
        }
        this.decision = decision;
        this.mode = mode;
        this.maxCandidates = maxCandidates;
    }

    /**
     * Scores the leading candidates against the query and reorders them by descending
     * relevance. Never throws: any failure to obtain usable scores yields the input
     * order with {@link Verdict#UNJUDGEABLE}.
     *
     * <p>Candidates are identified by position ({@code hit-<index>}) rather than by
     * parsing the rendered {@code path:line: text} back apart. Parsing would break on a
     * Windows drive letter, and the id only has to key the answer — the candidate text
     * itself travels in the request, so the model still sees what it is scoring.
     */
    public Result rerank(String query, List<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return new Result(List.of(), List.of(), mode == Mode.OFF ? Verdict.OFF : Verdict.RERANKED);
        }
        if (mode == Mode.OFF || hits.size() < 2) {
            // One candidate has no order to improve, so scoring it would be a pure cost.
            return new Result(hits, hits, Verdict.OFF);
        }

        int scored = Math.min(hits.size(), maxCandidates);
        var questions = DecisionQuestions.relevance(idsFor(scored));
        Map<String, Integer> levels = new LinkedHashMap<>();
        try {
            var state = new DecisionEngine.State("RELEVANCE",
                boundRequest(query, hits.subList(0, scored)), 0.0);
            var answers = decision.askAll(state,
                questions.stream().map(q -> (DecisionEngine.Question) q).toList());
            for (int i = 0; i < scored; i++) {
                levels.put(DecisionQuestions.relevanceQuestionId("hit-" + i),
                    levelOf(answers.get(DecisionQuestions.relevanceQuestionId("hit-" + i))));
            }
        } catch (RuntimeException e) {
            return new Result(hits, hits, Verdict.UNJUDGEABLE);
        }

        if (levels.values().stream().anyMatch(l -> l == null)) {
            // Partial answers still rank what they can; the unscored candidate keeps its
            // relative position rather than being discarded for someone else's failure.
            return apply(hits, scored, levels);
        }

        Result result = apply(hits, scored, levels);
        if (mode == Mode.SHADOW) {
            return new Result(hits, result.proposed(), Verdict.RERANKED);
        }
        return new Result(result.proposed(), result.proposed(), Verdict.RERANKED);
    }

    /**
     * Reorders the scored prefix, leaving the unscored tail in place after it. A
     * candidate with no score sorts as UNSCORED, keeping ties stable so a rerank never
     * reshuffles results the model already understood.
     */
    private Result apply(List<String> hits, int scored, Map<String, Integer> levels) {
        record Scored(int index, String line, int level) {
        }
        var prefix = new ArrayList<Scored>(scored);
        for (int i = 0; i < scored; i++) {
            Integer level = levels.get(DecisionQuestions.relevanceQuestionId("hit-" + i));
            prefix.add(new Scored(i, hits.get(i), level == null ? Integer.MIN_VALUE : level));
        }
        // Ties break on the original index, so equal scores keep filesystem order.
        prefix.sort(Comparator.comparingInt(Scored::level).reversed()
            .thenComparingInt(Scored::index));
        var out = new ArrayList<String>(hits.size());
        prefix.forEach(s -> out.add(s.line()));
        out.addAll(hits.subList(scored, hits.size()));
        return new Result(out, out, Verdict.RERANKED);
    }

    /** The usable level of a score answer, or null when it has none. */
    private static Integer levelOf(DecisionResult answer) {
        return answer instanceof DecisionResult.ValidScore score ? score.level() : null;
    }

    private static List<String> idsFor(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("hit-" + i);
        }
        return ids;
    }

    /**
     * The query and the scored candidates, bounded to the state limit. Bound the whole
     * request rather than each candidate: the limit is on the request, so truncating a
     * single line would leave the batch still able to overflow.
     */
    private static String boundRequest(String query, List<String> candidates) {
        var sb = new StringBuilder();
        sb.append("query: ").append(query == null ? "" : query).append("\ncandidates:\n");
        for (String candidate : candidates) {
            sb.append(candidate).append('\n');
            if (sb.length() > MAX_REQUEST_CHARS) {
                return sb.substring(0, MAX_REQUEST_CHARS);
            }
        }
        return sb.toString();
    }
}