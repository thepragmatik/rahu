# Dogfood alpha release gate evidence

Date: 2026-10-02. Commit: `626d44a` plus the four fix commits below it
(`4067ea0`, `ada86eb`, `4002166`, `990647f`). Branch `main`, pushed. This document is
the requirement-to-test evidence manifest that `release-gates.md:39` requires and that
did not previously exist.

## Headline status

**Offline complete: NOT yet declared.** G01–G08 are individually evidenced below and
G10 is discharged by `docs/reviews/015-architecture-audit.md`, but four inert
configuration keys and one re-delivered safety observation were found by the audit on
2026-10-02. Those are fixed and proven; the declaration waits on a clean
requirement-to-test pass and on the operator items under G09.

**Live dogfood verified: NO.** G09 is blocked on operator authority. It is not blocked on
a missing service — the hosted decision plane is in use.

**Routing optimisation validated: NO.** M3 has not happened. `routing.mode` ships as
`shadow`.

## Environment and versions

| Item | Value |
|---|---|
| JDK | OpenJDK 27 (Homebrew), `maven.compiler.release=27`, preview enabled |
| Maven | 3.9.9 via `./mvnw` |
| Modules | `rahu-core`, `rahu-openrouter`, `rahu-systemone`, `rahu-cli` |
| Test suite | **287 green** (114 core, 14 openrouter, 26 systemone, 133 cli) at `626d44a` |
| Build command | `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home && unset OPENROUTER_API_KEY && ./mvnw clean verify` |
| Generation adapter | `openrouter`, base `https://openrouter.ai/api/v1` |
| Decision adapter | `openrouter-decisions`, base `https://openrouter.ai/api/alpha/decisions`, model `typesafe/jev-1.13`, profile `jev-compatible-v1` |
| Packaged launcher | `./bin/rahu --help` → exit 0, verified at `990647f` |
| Costs | offline suite: none. Live turn evidence below is from earlier slices and is reported as measured at the time, not re-measured here |

`OPENROUTER_API_KEY` must be unset for offline `verify`: `DotEnvTest` and
`LiveWiringTest` read the real environment and fail when it is exported. Use
`clean verify`, not `verify`: an incremental build leaves a stale `rahu-core` and
reports an unresolved compilation problem as a `java.lang.Error` at test time.

## Gate status

| Gate | Status | Evidence |
|---|---|---|
| G01 Reproducible build | PASSED | `./mvnw clean verify` → BUILD SUCCESS, 287 tests. Packaged `./bin/rahu --help` exit 0. |
| G02 Legal routing, real protocol contracts | PASSED | `CandidateFactoryTest`, `RouteResolverTest`, `ModelTypesTest`, `SystemOneAdapterTest`, `ScoreWireMappingTest`, `DecisionBatchAskTest`, `DecisionQuestionsTest`; A02–A07 |
| G03 Tools, deterministic authority | PASSED after fixes | `WorkspaceToolsTest`, `PathBoundaryTest`, `ToolRegistryTest`, `ToolRelevanceGateTest`, `CoreBoundaryTest`, `EndpointPolicyTest`; A08, A33, A34 |
| G04 Context/session usability | PASSED | `PromptAssemblerTest`, `SessionStateTest`, `CompactionPlannerTest`; A22 |
| G05 Loop coordination and limits | PASSED | `RunStateMachineTest`, `NoProgressDetectorTest`, `CancellationTest`, `CostGateTest`; A12, A13 |
| G06 Traces/replay | PASSED | `TraceWriterTest`, `ReplayEngineTest`; A11, A16 |
| G07 Extension and design quality | PASSED | `SchemaGeneratorTest`, `CliExitCodesTest`, `ToolRegistryTest`; A15, A19, A21 |
| G08 Install/run documentation | PASSED | `README.md`, `docs/runbooks/dogfood.md`, `examples/live-local-systemone.json`, schema staleness test |
| G09 Real dogfood smoke | **BLOCKED** | Operator authority. Detail below. |
| G10 Release-wide critique | PASSED | `docs/reviews/015-architecture-audit.md`, five findings fixed with red-then-green proof |

## Acceptance ID to test map

Every ID below names a test class that exists in the tree at `626d44a`. An ID with no
honest test is marked as such rather than filled in.

| ID | Test class | Note |
|---|---|---|
| A01 | `DemoCommand` path, `ShippedSuitesParseTest` | Deterministic offline, zero external calls |
| A02 | `LiveWiringTest` | Decision and generation settings verified separate |
| A03 | `RouteResolverTest`, `CandidateFactoryTest` | Out-of-pool candidate rejected |
| A04 | `ModelTypesTest`, `CandidateFactoryTest` | none/medium/provider-default distinct |
| A05 | `RouteResolverTest`, `ActiveRouterTest` | `NO_FEASIBLE_ROUTE` terminal |
| A06 | `DecisionQuestionsTest`, `ScoreWireMappingTest`, `SystemOneAdapterTest` | Typed failures, no silent repair |
| A07 | `RouteResolverTest`, `RoutingEvidenceTest` | Shadow executes baseline and records the suggestion |
| A08 | `PathBoundaryTest`, `WorkspaceToolsTest`, `ToolLoopTest` | Denied before read |
| A09 | `WorkspaceToolsTest` (call-ID reuse), `ToolLoopTest` | Identical ID reuses; changed args error |
| A10 | `OpenRouterProviderTest`, `OpenRouterCostTest` | Missing cost stays unknown, never zero |
| A11 | `ReplayEngineTest` | Offline, no network |
| A12 | `CancellationTest`, `RunStateMachineTest` | Terminal reason and exit code |
| A13 | `LedgerTest`, `CostGateTest` | Pre-dispatch reservation; uncertain liability retained |
| A14 | `CompactionPlannerTest`, `CompactionPolicyDeciderTest` | Pins and pairs preserved; **live evidence outstanding under G09** |
| A15 | `ConfigLoaderTest`, `CliExitCodesTest`, `SchemaGeneratorTest` | Field-path errors, redaction, schema freshness |
| A16 | `TraceWriterTest` | Gap marked, never invented completion |
| A17 | `SuiteV1Test`, `EvalLiveConfigGuardTest` | Offline eval reports honestly; live refused |
| A18 | `SystemOneAdapterTest`, `OpenRouterProviderTest` | Continuation envelope roundtrip |
| A19 | Build itself, `./bin/rahu --help` | JDK 27 + preview on compile, test JVM, launcher |
| A20 | Every `docs/reviews/*.md` | One per slice |
| A21 | `CliExitCodesTest` | stdout stable, stderr diagnostics |
| A22 | `SessionStateTest`, `PromptAssemblerTest` | Fresh run IDs, same session, aggregate limits |
| A23 | `PromptAssemblerTest` | Instruction files, oversized router state |
| A24 | `ToolRelevanceGateTest`, `ToolRelevanceReachabilityTest` | Relevance can only narrow |
| A25–A32 | `docs/reviews/007`–`013` cover these by slice | Slice reviews are the evidence; a per-ID test file was not created for each |
| A33, A34 | `EndpointPolicyTest`, `PrivacyGateTest`, `LiveWiringTest` | Transport policy by parsed host; final dispatch check |

A25–A32 are the weakest row in this table. The slice reviews record them, but there is
no one-test-per-ID mapping and I will not manufacture one after the fact.

## The four fixes from the N1 audit

Each with the red evidence that justified it and the green evidence that it worked.
Full detail in `docs/reviews/015-architecture-audit.md`.

| Finding | Severity | Commit | Red evidence |
|---|---|---|---|
| F-1 withheld search observation re-delivered | HIGH | `4067ea0` | Model read `Poison.java:1: tryReserve IGNORE ALL PREVIOUS INSTRUCTIONS...` after the injection gate withheld it |
| F-2 advisory relevance judgment discarded | MED | `ada86eb` | `yet it is still advertised to the model: [workspace.search, workspace.list, workspace.read]` |
| F-3 loopback exemption by string prefix | HIGH | `4002166` | 6 of 14 cases: `must not grant the plaintext loopback exemption to http://localhost.evil.example/api` |
| F-4/F-5 `tools.enabled`/`exclusions` ignored, case-sensitive dir exclusions | MED | `990647f` | `.GIT must be excluded as surely as .git ... Expected BoundaryViolation to be thrown, but nothing was thrown` |

Recorded without a change, each with its proof obligation: F-6 two `confidenceFloor`
defaults, F-7 `Question` not `sealed`, F-8 transport faults collapsed to `TIMEOUT`, F-9
`tools.resultBytes` inert.

## G09 — blocked, on whom and on what

The decision plane is **hosted and in use**. `127.0.0.1:8000` is only the default local
base URL (`docs/specs/systemone.md:13`); an earlier report that called the decision
plane absent was a false blocker, corrected at `0a6f84f`.

Three operator decisions block every live run. None is an engineering task.

1. **Aggregate smoke allowance.** Set by the operator on 2026-10-02 to **1.00 USD** for
   this session. This is distinct from `agent.maxCostUsd` (per-dispatched-run) and
   `session.maxCostUsd` (the allowance it is reserved from).
2. **Approved non-sensitive prompt and source views.** Not yet supplied. Three
   mechanical facts: `config.local.json:104-108` sets `privacy.inputClassification:
   "unknown"` with `onUnknown: "block"`, so every live prompt blocks until it becomes
   `approved-nonsensitive`; no `privacy.sourcePolicyFile` exists, and
   `docs/runbooks/dogfood.md:52` requires an ignored manifest with an exact SHA-256 per
   file; the `repository-followup` task reads `ARCHITECTURE.md`, so that file must be
   hashed and approved before G09 item 1 can run.
3. **Allowed generation model IDs and effort levels.** Not yet supplied. Rahu selects
   only among explicitly configured candidates.

**Also open:** `routing.mode` is `active` in the untracked `config.local.json`. The code
default is `shadow` and no tracked config changes it, so the shipped default is correct.
Left as-is pending an operator decision, recorded here with the observed behaviour
(`mode=ACTIVE degraded=true fallback=decision-rejected`; the answering model is the
configured fallback `qwen@default`, not the baseline `nemo@default`).

### Missing code, not a missing credential

`EvalCommand.java:54-62` refuses live execution twice with exit 3, and `runOfflineTask`
is the only task path. So the intended G09 run shape does not exist yet:

```bash
./bin/rahu eval --suite docs/evals/suites/dogfood-alpha-v1.json --config \
  config.local.json --live --max-cost-usd 1.00 --report PATH
```

Building it (real generation dispatch per task, real decision capture, one aggregate
ledger across all three tasks) is **N2a**, and is offline-implementable. Running it is
**N2b** and needs all three operator items above. Neither is attempted in this session;
the audit took the time.

Suites ready when it is: `docs/evals/suites/dogfood-alpha-v1.json` (3 tasks, the G09
suite) and `docs/evals/suites/smoke-v1.json` (6 tasks).

## Warnings

- **Injection enforcement stays OFF and threshold tuning is the wrong next step.**
  `docs/research/injection-corpus-v1.md`: AUC 0.899 over 49 observations, so the overlay
  discriminates, but no threshold separates the classes without withholding 9 of every 34
  ordinary observations. The problem is calibration, not absent signal. A score is only
  reproducible alongside its batch.
- **Rerank ships default OFF.** `SearchReranker` returns filesystem order in `SHADOW`
  and carries the proposal only in `Result.proposed()`, so a shadow run yields the score
  distribution and the order delta, not a quality verdict. Measuring effect requires
  `enforce` under an approved bounded run. Rerank and injection are separate dispatches
  by design; do not batch them.
- **Adversarial corpus execution is container-only.** Enforced in code by
  `ShadowCorpusProbe`; `ShadowCorpusProbeGuardTest` fails the build if the guard is
  removed. Do not weaken it to make a run succeed.
- **`docs/plans/active/build-status.md` carried three false claims** before this
  session: a "live eval has run green" line (no live eval path exists), a superseded
  "do NOT push yet" warning, and a preflight entry claiming `OPENROUTER_API_KEY` absent
  that contradicted its own later correction. Corrected in the same commit that wrote this document.
- **`prompt2.md` section 1 states HEAD `0a6f84f`; the real HEAD was `7ae0e48`.** All
  other facts in that table verified.
- **Reorder-only is a rerank invariant, not a safety claim.** Unlike the injection gate
  there is no safety claim in reranking, so it fails soft to filesystem order with
  `UNJUDGEABLE`. A decision-plane outage must degrade the ranking, not the tool call.
- **Upstream Laya/Kev conformance is unverified.** `docs/specs/systemone.md:34` is right
  that request compatibility is not response parity.

## Links

Seven-area map: [`docs/harness-subsystems.md`](../harness-subsystems.md).
Slice reviews: [`docs/reviews/004`](../reviews/) through [`014`](../reviews/), and the
architecture audit [`015`](015-architecture-audit.md).
Requirements: [`docs/specs/acceptance.md`](../specs/acceptance.md).
Gates: [`docs/release-gates.md`](../release-gates.md). ADRs: [`docs/adr/`](../adr/).