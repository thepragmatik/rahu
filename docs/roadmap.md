# Rahu product roadmap

This roadmap is ordered by evidence and dependencies, not calendar promises. Only the documentation foundation is complete. The M2.5 code-intelligence client is built but unadopted: its exit gate is a cost comparison, not a code change. Runtime stages need implementation evidence before their status changes.

| Stage | Product outcome | Dependencies | Exit gate | Status |
|---|---|---|---|---|
| M0 Specification foundation | Another session can implement without reconstructing the conversation | Repository access | Indexed requirements, ADRs, acceptance cases, critical review and handoff | Complete |
| M1 Routing kernel | A synthetic text request reaches fake providers and owned HTTP adapters against local recording servers through legal joint routing | M0 | Slices S01–S05 offline contracts verified; real smoke waits for mandatory S06 privacy/admission | Built; offline evidence green, **offline complete not yet declared** — see [release gate evidence](reviews/dogfood-release.md). G02, G05, G07 pass with named tests. The N1 architecture audit (2026-10-02) found five defects, two HIGH, all since fixed with red-then-green proof; the declaration waits on a clean acceptance-to-test pass, which found A25–A32 covered only by slice reviews rather than one test each |
| M2 Dogfood alpha | Useful read-only repository assistance, follow-up chat, all seven MVP decisions, packaged CLI | M1 | S06–S12 verified; R01–R25 covered; G01–G10 reported with genuine live evidence or explicit blocker | Built; G03, G04, G06, G08, G10 pass with named tests. **G09 PASSED 2026-10-03 with a defect found and fixed** — live turns against hosted `typesafe/jev-1.13` via `openrouter-decisions` (`config.local.json`) exercised decision→routing→tool→injection→answer→ledger end to end. The run exposed that `NoProgressDetector` was dead code (never called from `main`) and could not stop a stuck tool loop; it is now wired, with a `NO_PROGRESS` failure kind and red-then-green tests. This gate was earlier wrongly recorded as blocked on a `127.0.0.1:8000` loopback probe. Separately **G07 carries a recorded gap**: the tool registry is wired and tested, but no third-party compiled extension example exists. Not "Planned": the code is built and the suites are green |
| M2.5 Code intelligence (LSP) | Exact symbol facts (definition, references, rename) from a pinned jdtls snapshot instead of grep | M1 tool boundary; pinned `jdtls` snapshot | Measured token saving vs today's grep/compile baseline; no leaked server processes | Client **built and tested, deliberately not adopted**. `LspSessionLiveTest` 5/5 green against the pinned snapshot; 26 capabilities; `workspace/symbol` returns real hits. Cost-effectiveness **NOT PROVEN** — no `results/`, trace JSONL or ledger exists in-repo, so the baseline has no source and needs a live funded run. Not wired into `ToolRegistry`. See `reviews/018-jdtls-adoption.md`. |
| M3 Evaluation laboratory | Evidence-based routing control and trace comparison | M2 | Frozen suites, paired evaluations, confidence semantics, promotion decision | Planned |
| M4 Richer single-agent harness | Streaming, resumability, permissioned writes, skills/MCP | M2 reliability; M3 baseline | Separate specs and failure tests; recovery cannot silently duplicate effects | Exploratory |
| M5 Native decisions | Java-hosted inference compared with HTTP services | M3 evidence that inference topology matters | Tokenizer/export parity, calibration, packaging, memory and latency comparisons | Exploratory |
| M6 Learned routing | Domain-aware model/effort routing from real trajectories | M3 plus sufficient lawful labels | Held-out gains, counterfactual coverage, calibration and shift analysis | Research |
| M7 Adaptive policies | Constrained contextual exploration and improved model profiles | M6 reliable estimates | Offline evaluation, bounded experimentation, drift/rollback controls | Research |
| M8 Multi-agent orchestration | Specialists and delegation when a single agent is insufficient | M4 strong single-agent behavior | Measured quality gain after coordination cost; shared budgets/cancellation | Exploratory |

## M1 and M2 release gates

M1 must have an offline path and independently configurable decision/generation services. It must reject illegal efforts, missing capabilities, empty candidates and out-of-pool fallback. It must distinguish no reasoning, provider default, and explicit effort. Its adapters are first checked against synthetic local HTTP fixtures. Real smoke is deferred until S06 privacy/authority/admission is implemented, using approved non-sensitive inputs and a separately bounded request; full integrated dogfood waits for S12. Failure tests are more valuable than adding a fifth module.

M2 adds safe read-only dogfooding, in-process follow-up sessions, minimal System One-directed summarisation, trusted prompt assembly, authority and compiled extension contracts. Summaries preserve pinned instructions and tool-call/result integrity. Single-agent-only orchestration is deliberate and tested; M8 is the later delegation stage. Replay inspects recorded inputs and runs pure policies with fakes; it does not regenerate an identical answer. Termination, trace gaps and estimated-cost limitations must be visible.

The build session executes [S01–S12](plans/active/001-dogfood.md) continuously under [the autonomous contract](autonomous-build.md). Complete [the seven-area map](harness-subsystems.md) and [G01–G10](release-gates.md): clean-checkout offline verification, packaged usability, bounded live smoke, sanitised report and closed high-severity findings. If live credentials/services are unavailable, finish every independent offline deliverable, label it offline complete and leave only genuinely blocked gates open. Do not stop at S01/S02 or infer a live success from fake tests.

## Seven areas through the roadmap

M1 establishes loop/integration/tool contracts and basic extension composition. M2 completes memory/context, explicit safety/permissions, single-agent coordination and documented extensibility, with CLI/session/telemetry integration. M4 deepens tools, memory, isolation and extension ecosystems; M5/M6 deepen decision integration; M8 alone introduces multi-agent coordination. [The coverage map](harness-subsystems.md) names exact owners/tests and deliberate exclusions.

## M3 promotion policy

Shadow mode stays the default until [the evaluation protocol](evals/protocol.md) supports promotion. Active mode may be explicitly enabled for experiments earlier. Select success margins and spending limits before inspecting results. Include router/local-service costs and timeouts. A failed gate may call for a narrower pool or better descriptions rather than a bigger decision model.

## Research stop conditions

- Stop native inference work if network overhead is negligible or Java export parity is unreliable.
- Stop learned routing if fixed routing is on the same quality/cost/latency frontier.
- Stop adaptive exploration if quality floors cannot be checked or labels are biased by selected routes.
- Stop orchestration expansion if coordination costs exceed its measured benefit.
- Stop expanding the runtime until outstanding cancellation, side-effect, capability or trace defects are fixed.

The owner may reprioritise stages; record why and update the active plan instead of treating future rows as commitments.
