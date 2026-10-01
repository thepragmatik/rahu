# Rahu product roadmap

This roadmap is ordered by evidence and dependencies, not calendar promises. Only the documentation foundation is complete. Runtime stages need implementation evidence before their status changes.

| Stage | Product outcome | Dependencies | Exit gate | Status |
|---|---|---|---|---|
| M0 Specification foundation | Another session can implement without reconstructing the conversation | Repository access | Indexed requirements, ADRs, acceptance cases, critical review and handoff | Complete |
| M1 Routing kernel | A text request reaches a fake provider and then real HTTP adapters through legal joint routing | M0 | Slices S01–S05 verified; live smoke run explicit and bounded | Planned |
| M2 Dogfood alpha | Useful read-only repository assistance, bounded context, inspectable traces | M1 | S06–S10 verified; all R01–R16 covered; real dogfood report | Planned |
| M3 Evaluation laboratory | Evidence-based routing control and trace comparison | M2 | Frozen suites, paired evaluations, confidence semantics, promotion decision | Planned |
| M4 Richer single-agent harness | Streaming, resumability, permissioned writes, skills/MCP | M2 reliability; M3 baseline | Separate specs and failure tests; recovery cannot silently duplicate effects | Exploratory |
| M5 Native decisions | Java-hosted inference compared with HTTP services | M3 evidence that inference topology matters | Tokenizer/export parity, calibration, packaging, memory and latency comparisons | Exploratory |
| M6 Learned routing | Domain-aware model/effort routing from real trajectories | M3 plus sufficient lawful labels | Held-out gains, counterfactual coverage, calibration and shift analysis | Research |
| M7 Adaptive policies | Constrained contextual exploration and improved model profiles | M6 reliable estimates | Offline evaluation, bounded experimentation, drift/rollback controls | Research |
| M8 Orchestration | Specialists and delegation when a single agent is insufficient | M4 strong single-agent behavior | Measured quality gain after coordination cost; shared budgets/cancellation | Exploratory |

## M1 and M2 release gates

M1 must have an offline path and independently configurable decision/generation services. It must reject illegal efforts, missing capabilities, empty candidates and out-of-pool fallback. It must distinguish no reasoning, provider default, and explicit effort. Its live adapter smoke checks use a separately bounded test request; the full run ledger and tool lifecycle arrive in M2. Failure tests are more valuable than adding a fifth module.

M2 adds safe read-only dogfooding and minimal System One-directed summarisation. Summaries preserve pinned instructions and tool-call/result integrity. Replay inspects recorded inputs and runs pure policies with fakes; it does not regenerate an identical answer. Termination, trace gaps and estimated-cost limitations must be visible.

Before declaring dogfood alpha, complete the active plan, run a clean-checkout offline verification, run a separately budgeted live smoke task, publish a sanitised report with exact versions, and close high-severity review findings. If live credentials/services are unavailable, label the result offline verified and leave the live gate open.

## M3 promotion policy

Shadow mode stays the default until [the evaluation protocol](evals/protocol.md) supports promotion. Active mode may be explicitly enabled for experiments earlier. Select success margins and spending limits before inspecting results. Include router/local-service costs and timeouts. A failed gate may call for a narrower pool or better descriptions rather than a bigger decision model.

## Research stop conditions

- Stop native inference work if network overhead is negligible or Java export parity is unreliable.
- Stop learned routing if fixed routing is on the same quality/cost/latency frontier.
- Stop adaptive exploration if quality floors cannot be checked or labels are biased by selected routes.
- Stop orchestration expansion if coordination costs exceed its measured benefit.
- Stop expanding the runtime until outstanding cancellation, side-effect, capability or trace defects are fixed.

The owner may reprioritise stages; record why and update the active plan instead of treating future rows as commitments.
