# Critical review template

## Scope and hypothesis

Date, change/commit, affected requirements, smallest useful result, predicted benefit, simplest alternative and why it was accepted/rejected. Identify evidence that would falsify the change before implementation.

## Evidence

Record actual tests/commands and results, fixture/live service versions, observed behavior and missing verification. Distinguish passing deterministic tests from statistical evaluation. Include public API/CLI output or rendering evidence for design claims.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| High/medium/low | Concrete defect or tradeoff | Change/test/ADR | Remaining uncertainty |

Consider capability errors, pool widening, invalid probabilities, context loss, budget uncertainty, retries, duplicate effects, cancellation, trace gaps, prompt injection, configuration drift and misleading output. Also ask whether a concept/dependency can be removed.

## Decision

Proceed, revise, defer or stop with reasons. High-severity unresolved correctness/authority issues block release. Record requirement/spec/ADR updates and the next check that resolves uncertainty. Avoid claiming no risks merely because tests pass.
