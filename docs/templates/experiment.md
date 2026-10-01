# Experiment template

## Question and preregistered criteria

Hypothesis, baseline, alternatives, useful improvement threshold, maximum quality loss, task slices, sample/power reasoning, spending ceiling, stop conditions and primary metric. Commit these before held-out results.

## Frozen inputs

Task/source hashes, rubric, development/evaluation split, candidate order/descriptions, catalog/config hashes, harness/adapter commit, decision checkpoint, generation/provider identities, inference controls and hardware. Note moving aliases and unavailable metadata.

## Procedure and outcomes

Task pairing/randomisation, repeated trials, evaluator blinding, human/judge agreement and failure accounting. Record quality, total cost, total latency, uncertainty, estimated charges and missing values. Counterfactual regret is computed only on tested alternatives.

## Critical interpretation

Confounds, selection bias, position bias, calibration/coverage, statistical uncertainty, domain regressions and sensitivity to assumptions. State inconclusive results explicitly. Compare the quality/cost/latency frontier before selecting a weighted score.

## Decision and reproducibility

Promote, keep shadow, narrow pool, revise descriptions or stop. Link sanitised artifacts and exact commands; never publish private payloads/credentials. List residual risks and next falsifiable experiment.
