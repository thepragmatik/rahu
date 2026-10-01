# Specification critical review

Date: 2026-10-01. Scope: documentation foundation and correction of the earlier architecture proposal. This review validates design completeness and internal consistency; it does not validate runtime code or model performance.

## Hypothesis

A bounded Java harness with joint System One routing can reach useful dogfooding before custom inference/training. A comprehensive but staged contract should reduce expensive surprises for the next session. The simplest viable comparison is a fixed generation route with a shadow decision model, not a learned router.

## Findings and corrections

| Severity | Finding | Correction | Residual risk |
|---|---|---|---|
| High | Generic reasoning support does not establish accepted efforts or provider behavior | Explicit effort evidence, default/none distinction, mandatory reasoning handling, parameter enforcement and rejection tests | Provider metadata can still be wrong |
| High | Decision concentration was liable to be interpreted as downstream correctness | Separate chosen probability, provider confidence semantics and empirical task success; provisional gate labelled | Calibration may vary by domain/checkpoint |
| High | A spend budget sounded like a guaranteed billing cap | Exact-decimal reservation ledger, uncertain liabilities and admission terminology | Remote fees/delayed usage can overshoot |
| High | Policy replay risked promising deterministic LLM reruns or effect recovery | Three replay definitions, payload availability and no-network/effect default | Moving provider aliases limit reproducibility |
| High | Route switching/compaction could invalidate opaque tool-continuation state | Preserve adapter envelope and prohibit incompatible switches/summarisation | Provider compatibility needs real tests |
| High | Retry/fallback could duplicate paid requests or future tool effects | No default ambiguous retries; call-ID outcomes and indeterminate status; mutation deferred | No durable exactly-once guarantee |
| Medium | Preview-free kernel conflicted with updated user preference | Java 27 preview allowed; toolchain/flags/launcher/cancellation gate | Preview migration and library tooling friction |
| Medium | Summarisation/tool routing were strategic promises without initial release contracts | Minimal compaction, summary pool, relevance policy, safe typed tools and tests in M2 | Semantic summary fidelity requires evaluation |
| Medium | Defaults could silently approve stale/fictional model pools | Operational defaults plus synthetic demo; explicit live IDs and frozen evidence | More first-use configuration |
| Medium | A large joint choice set can hurt quality and introduce position bias | Bounded candidates, no silent truncation, permutation tests and stable IDs | Hierarchical choice may later be needed |
| Medium | A shadow run alone cannot establish quality/cost savings | Separate counterfactual and paired active phases, power-aware promotion gate | Evaluations may be costly/underpowered |
| Medium | Four modules/ports could become abstract framework scaffolding | Create modules only with first executable slice; interfaces justify real boundaries | Implementer may still overbuild |
| Medium | Aesthetics could become decorative output or vague taste | API/CLI rubric, plain text/JSON/error review, later UI rendering gate | Actual output needs implementation review |
| Medium | Read-only tool path checks could be misrepresented as a sandbox | Reject symlinks, bounded reads, race limitations stated, shell/write tools deferred | Malicious concurrent root mutations need stronger isolation |

## Review outcome

Proceed with staged implementation. No expensive training/native build is justified. The next session should falsify protocol/preview/candidate assumptions through tiny tests first. High-severity findings are addressed as requirements, not yet proved fixed in code.

Protocol evidence and current Java facts were checked against primary sources in [the evidence map](../research/evidence.md). Illustrative model names from the earlier conversation were not adopted as working IDs. The earlier preview prohibition is expressly superseded.

## Required follow-up evidence

Prove adapter wire parity and cancellation; test exact effort mapping and opaque continuation; verify packaged preview launcher; run a real local/hosted decision service; exercise long-context compaction; measure end-to-end routing overhead; compare held-out downstream task outcomes. Any failed test may require narrowing the design, not layering another fallback.

## Documentation verification

Local verification covered all 41 files: 36 Markdown documents had valid relative destinations and balanced code fences; all three JSON artifacts parsed. Requirement references R01–R16 and acceptance IDs A01–A21 were checked for coverage. The examples' candidate references resolve within their proposed pools. Manual consistency review clarified exact-decimal configuration and the distinction between an upstream Git tree SHA and a deployed model version. Runtime tests, live model tests and rendered CLI review remain explicitly unperformed.
