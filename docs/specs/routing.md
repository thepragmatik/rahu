# Routing specification

## Legal action space

For operation `o`, request state `x`, pool `P`, and frozen catalog `K`, Java constructs `A(x,o,P,K)`. Each candidate contains one model, one reasoning policy, and one provider policy. Selection and fallback must belong to this set. Candidate IDs use the configured alias and policy name, for example `fast@low`; IDs never depend on list index or display text.

Effort vocabulary v1 is `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. `default` means omit effort controls and accept the documented provider default; it is not equivalent to `none`. Unknown future values are retained in catalog raw metadata but excluded until the adapter and configuration explicitly support them. Models with mandatory reasoning cannot receive none/disabled. Budget-based reasoning is deferred; do not invent an equivalence between token budgets and effort labels.

## Candidate generation

1. Resolve model aliases to exact catalog IDs; reject duplicates and dynamic model routers in controlled live pools.
2. Check configured provider/data constraints and evidence freshness.
3. Require the operation's text modality, context allowance including tool schemas/output reserve, tool support if tools are exposed, and structured output if required. Trusted requirements cannot be removed by task classification.
4. Intersect configured efforts with supported-effort evidence. When a documented catalog field lists efforts, use it. When it documents null as all gateway efforts, apply adapter-known vocabulary and provider restrictions. When omitted, explicit effort support is unknown; use only provider default if ordinary generation capabilities are otherwise evidenced, or a reviewed expiring override.
5. Apply context/output and conservative estimated-spend admission. Unknown critical context/pricing evidence prevents paid admission under the default policy.
6. Create stable ordered candidates, capped at 32 by default; reject an oversized set with advice to narrow the pool. Do not silently truncate.

Record exclusions with reason codes, provenance and snapshot hash. Baseline and fallback aliases must resolve to feasible candidates for this operation; configure separate operation references where needed. A tool-compatible fallback cannot be replaced by an answer-only candidate during a pending tool continuation.

## Decision and resolution

Send bounded state and trusted candidate descriptions to System One. Descriptions include task strengths backed by evidence or explicitly labelled operator assumptions, supported tools/context, effort, and estimated cost. Model names alone are poor quality labels. Avoid language claiming a universal smartest or cheapest model.

Validate the returned candidate ID. Probabilities, when present, must cover exactly the submitted choices, be finite and in [0,1], and sum to 1 within 0.0001. Reject material violations; only roundoff may be normalised, with an event. Ties resolve using stable candidate order and are marked ambiguous. Keep probability of the chosen action, provider confidence and its formula separate. Confidence thresholds operate on a named field; default `chosen_probability` is 0.65. This is a provisional concentration gate, not an accuracy guarantee.

| Condition | Required behavior |
|---|---|
| Valid active decision above gate | Execute suggested feasible candidate |
| Valid shadow decision | Record suggestion, execute feasible baseline |
| Low concentration or missing probability | Use feasible configured fallback; label uncertainty |
| Timeout, protocol error, unsupported result | Use fallback if still admissible; label degraded decision |
| No feasible fallback/baseline/candidate | Terminate before generation with `NO_FEASIBLE_ROUTE` |
| Provider rejects selected effort | Mark snapshot evidence suspect; one constrained fallback at most; never drop effort silently |

Generation failure fallback is separate from router failure. Every attempt counts toward step and spend admission limits. An ambiguous paid outcome is not retried by default. No automatic widening of the pool, no recursive escalation, and no hardcoded frontier model. Quality escalation follows an operator-defined ordered subset; quality cannot be assumed from price.

## Stability and evaluation

Route once per generation operation in MVP, reusing the active candidate across an assistant/tool continuation batch where required by the provider. Re-evaluate only after the batch is complete, on typed failure, or for a distinct summarisation operation. Provider-specific continuation compatibility may pin the route longer. Record each reason for rerouting. Cache and hysteresis are later optimisations; keys must include full relevant state, candidates/order, catalog, configuration, prompt and decision-model versions.

Permutation and duplicate-choice tests detect position bias and candidate-description ambiguity. Joint choice is a feasibility mechanism, not evidence that System One knows actual quality. Evaluate model and reasoning effort regret independently when counterfactual results exist.
