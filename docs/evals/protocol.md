# Routing evaluation protocol

## Research question

Does System One joint model/effort routing improve the useful quality/cost/latency frontier relative to a predeclared fixed route, after including decision overhead, summarisation, retries and failures? The first experiment trains nothing and tests bounded candidate descriptions.

## Data and freezing

Create at least 40 development tasks for fixture/debugging and an independently authored held-out set sized before promotion. Include repository Q&A, code analysis, classification, summarisation, reasoning and read-only tool workflows. Add long-context, unknown-domain, injected-tool-text and ambiguous-request cases. Forty is a starting fixture size, not sufficient statistical proof. Split related tasks/documents by source to prevent leakage. Synthetic smoke cases cannot support claims about dogfood quality.

Freeze task IDs/content hashes, rubric, baseline candidate, candidate pool/order/descriptions, decision checkpoint, adapter/harness commit, catalog snapshot, generation IDs/providers/parameters, and environment. Separate development tuning from evaluation. Moving aliases/variable provider assignment are limitations. Record failures and excluded cases with reasons instead of silently filtering them away.

## Experiment phases

1. **Offline plumbing:** fakes verify task execution, trace/replay and metric accounting.
2. **Shadow:** execute the fixed baseline, record System One suggestions and overhead. This measures policy stability, not alternative quality or savings.
3. **Counterfactual subset:** preselect a stratified bounded subset; execute candidate alternatives offline from the user's perspective, with read-only tools and isolated inputs. Equalise evidence and avoid sharing outputs between candidates.
4. **Paired active comparison:** run baseline and active policy on the same held-out tasks, randomise run order/time blocks, repeat stochastic tasks where needed, and report uncertainty. No live side-effect tools.

Quality rubric is task-specific: exact labels and schema for classification, test-backed facts for code, required facts/provenance for summaries, and rubric-based assessment for answers. Blind model identity in human review. A model judge may assist but validate agreement with humans and report judge identity/bias. Router confidence is not a quality label.

## Metrics

| Metric | Definition and limit |
|---|---|
| Task success | Predeclared rubric pass; failed/time-limited runs count as failure |
| Cost per success | Total spend across all runs divided by successes; undefined if none |
| Total cost | Decision provider/local amortised compute, generation, summaries, retries, cache categories and uncertainty |
| Router latency p50/p95 | End-to-end HTTP duration; service compute separately; include cold and warm distributions |
| Total latency p50/p95 | Request start to final result; decision/compaction/tool time included |
| First-token latency | Available only after streaming instrumentation; otherwise unavailable |
| Model/effort regret | Difference from best actually tested feasible candidate on a stated subset; no unobserved oracle |
| Probability calibration | Brier/ECE against independently adjudicated route labels; bins/sample sizes stated |
| Selective risk | Downstream failure rate vs coverage when applying a gate; measures actual policy behavior |
| Tool relevance | Precision/recall against annotated needed categories; false exclusion tracked |
| Stability | Route switches per run, permutation sensitivity, fallback/escalation and timeout rates |

Report missing/estimated charges and local hardware assumptions. Server model milliseconds are not user latency. A distribution's concentration/confidence is not probability of downstream success; temperature calibration on another domain does not transfer automatically.

## Promotion gate

Before collecting held-out results, commit the allowed success-rate loss, confidence level, spend ceiling, latency tolerance and minimum sample calculation. Proposed starting quality loss margin is two percentage points; the owner/researcher must validate its suitability and required sample size. Treat it as a policy proposal, not established evidence.

Promote only if the lower confidence bound of paired success-rate difference exceeds the negative predeclared margin, no critical-domain regression is hidden by averaging, all authority invariants pass, and cost per success or end-to-end latency improves by the predeclared useful margin. Use paired bootstrap intervals for continuous metrics and paired binary methods appropriate to task clustering; report sample size and uncertainty. A small underpowered trial produces **inconclusive**, not success.

Keep active routing opt-in until a documented promotion decision changes the default. Roll back to fixed baseline after capability/quality drift or meaningful failure regression. Budget tuning must not use held-out results repeatedly without a new evaluation set.

## Longer-term mathematical direction

Estimate quality `Q(x,a)`, cost `C(x,a)` and latency `L(x,a)` over feasible candidates; compare Pareto fronts before arbitrary weighted utilities. Contrastive request/action embeddings inspired by CLIP and RouterDC are a hypothesis. New model embeddings do not guarantee zero-shot routing quality. Evaluate unseen model/effort pairs separately.

Logged selected actions alone do not identify counterfactual quality. Future contextual bandits need logged action propensities, overlap, bounded exploration, and honest off-policy estimation. Do not apply importance weighting when behavior probabilities are unknown or candidate support is zero. Dogfood logs may contain sensitive code: dataset permission, redaction and retention precede training.

Use [the experiment template](../templates/experiment.md) for each result, including negative findings and stop decisions.

## Dogfood build verification

The [alpha suite](suites/dogfood-alpha-v1.json) covers same-session follow-up and a bounded synthetic compaction source as well as read-only policy. [Artifact contracts](../specs/artifacts.md) define its loader/report and one aggregate ledger. [Release gates](../release-gates.md) distinguish offline evidence and real protocol smoke. This suite establishes usability/compatibility and does not replace the predeclared held-out M3 quality study.
