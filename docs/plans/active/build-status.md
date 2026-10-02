# Build status

Last updated: 2026-10-02T19:40+10:00 · HEAD: a84ab64 (F3b) + Phase D/F1/F2 landed · Next: F3c verification record (below, uncommitted at time of writing) then the cost-gate finding; Phases A–F are otherwise complete

## Groundwork (2026-10-02, plan 2026-10-02_114504 Part 0)

- [x] Task 0.1 baseline: `./mvnw verify` BUILD SUCCESS, 136 tests (85 core + 9 openrouter + 7 systemone + 35 cli), clean tree @ 3252e17.
- [x] Task 0.2 refactor: `LiveTurnDriver` extracted to `rahu-cli/src/main/java/rahu/cli/live/`; `ChatCommand.runLive` is now adapter wiring + delegation; still 136 tests; live probe `REFACTOR_OK` byte-identical footer, exit 0. Commit 2dc6fb1.
- [x] Task 0.3 worktrees: `../rahu-wt-tools` (feat/tools-unlock), `../rahu-wt-decisions` (feat/decision-ops), both @ 2dc6fb1; `dependency:go-offline` primed first (rc=0).

## Track T — tools unlock (2026-10-02, plan 2026-10-02_114504 Part 1)

- [x] T1 red: `ToolLoopTest` fails with `cannot find symbol: variable ToolLoop` exactly as planned.
- [x] T2: `rahu-cli/.../live/ToolLoop.java` — bounded loop over the three read-only workspace tools, with two spec-mandated additions beyond the plan sketch: A09 call-ID dedup via `WorkspaceTools.executeToolCall` + `ToolCallLog` (protocol violations become typed failed observations), and `PrivacyGate.admitForDecision` scans on tool arguments AND observations before they re-enter model context (session provenance threaded from `ChatCommand`; unknown fails closed). Loop advertises `rahu.core.model.ToolDescriptor`s and returns `INVALID_REQUEST` on step-cap exhaustion. Tests 2/2. Commit 9ee3a1e.
- [x] T3: `ChatCommand` builds `PathBoundary` from `tools.root` + `ToolRegistry.withWorkspace` + `ToolLoop` (with session provenance and `maxCallsPerStep`, default 8); `LiveTurnDriver` routes generation through `ToolLoop.generate` and prints a `tool:` stderr line per executed call. LIVE proof on worktree: `tool: workspace.read {"path":"pom.xml"}` → answer `rahu-parent`, exit 0, $0.000027. Commit 392d17a.
- [x] Merge per §4.5: rebase main → verify 138/138 → `git merge --no-ff` → diff-stat clean. Merge commit 1ab8b09; post-merge live probe on `main` reproduced `tool: workspace.read` → `rahu-parent`, exit 0.
- [x] Follow-up fix from the merge rule: T2's `git add -A` had swept the machine-local `rahu-cli/.classpath` JDK-container line into VCS (tracked-but-ignored IDE metadata). Fixed the class: untracked all 21 IDE metadata files (.classpath/.project/.settings across modules); commit dc274cd. Final `./mvnw verify` on main: 85+9+7+37 = 138 tests, BUILD SUCCESS.
- Note: worktree copies of gitignored `config.local.json`/`.env` were made from the main checkout (verified config holds key NAMES, not values). Live probes load `.env` only for the process (`set -a` + subshell-scoped unset after), after a first run polluted the persistent shell env and tripped two credential-fixture tests — root cause was the shell, not the code; re-verified green with the env scrubbed.

## Track D — decision ops (2026-10-02, plan 2026-10-02_113646 Phases B/C/E, track boundary from 2026-10-02_114504 Part 2)

- [x] B2: `TaskClass` v1 labels with UNKNOWN fail-closed default; 3/3 tests. Commit 3645461 (rebased).
- [x] B4: `DecisionQuestions` builders for the four spec'd operations (classification / per-tool relevance / compaction / route); 4/4. Commit 41bc2d5.
- [x] User decision (2026-10-02, mid-slice): keep the six v1 labels; DESIGN/ARCHITECTURE logged as v2 candidates in `docs/research/decision-plane-opportunities.md` with re-verify conditions. Commit 02e5d76.
- [x] C3a: `DecisionEngine.askAll` — batching made real: one dispatch, `Map<String, DecisionResult>` keyed by question id, missing answer degrades that question alone; `ask` demoted to a delegating default. `SystemOneHttpAdapter` rewired (per-question loop moved out of `parseAnswers`; validation rules unchanged verbatim). 3/3 batch tests + 7/7 existing adapter tests. Commit f027e03.
- [x] C3b: `ProfileDecider` — batched classification + per-tool relevance over the three workspace tools; unjudgeable tool stays (fail closed), judged-irrelevant narrows; transport failure → UNKNOWN + full permitted set. 5/5. Commit 3e17a70.
- [x] C3: `LiveTurnDriver` sends the batched profile decision each turn and prints `profile: task=... tools=... degraded=...`. Context pressure honestly `0.0` until the compaction track provides an estimator. Commit 08d2b64.
- [x] E2: `ToolRelevanceGate` — advisory narrowing that can never widen (out-of-set tool throws; empty judgment keeps the permitted set). 3/3. Commit 9af8b94.
- [x] Merge per §4.5: rebase main (both tracks touched `LiveTurnDriver`; composed semantically — ToolLoop + profile block coexist, verified by grep) → verify 158/158 on the branch → `--no-ff` merge 754c024 → diff-stat inspected, expected files only.
- [x] Live probes: `profile: task=answer tools=... degraded=false` + `DECISIONS_OK`, exit 0, $0.000007/turn. Still exactly 2 decision dispatches per turn (route + batched profile). Negative controls: live chat without `--input-classification` → exit 4 privacy-blocked; `eval` with live config (correct invocation: `--suite ... --config ...`) → exit 3 typed refusal.
- Note: two test bugs of mine were caught by honest red runs: positional `results.get(0)` on the id-keyed Map, and a fixture-surgery regex that orphaned a trailing comma (invalid JSON, correctly rejected). Replaced by per-id assertions and a dedicated fixture file `batch-response-missing.json`.
- Gotcha repeated from Track T: jdtls rewrote `rahu-cli/.classpath` mid-session (tracked-but-ignored on this branch until the rebase brought dc274cd in); `git checkout --` before rebase. The untrack commit now on main makes this class of noise structurally impossible going forward.

## Phase D + Phase F (2026-10-02, plan 2026-10-02_113646) — resumed after the provider interruption

Resume point: F2 was mid-flight (`ModelProfileCatalog` written, 5/5 green, uncommitted). Everything below was completed on resumption; 194 tests green at F3b (100 core + 14 openrouter + 14 systemone + 66 cli).

- [x] D1 `0c9d311`, D2 `e6cb864`, D2 fix `79aac62` (pre-resumption, landed by the interrupted session): compaction policy honours the System One decision with a deterministic override; the live loop applies it and reports real context pressure instead of the 0.0 placeholder; the fit check and the policy note see the candidate NEXT-REQUEST list, and a fit-check override is no longer mislabelled as `defer`.

- [x] F2a `5da36fb`: catalog fetch caches versioned evidence (`schemaVersion`/`fetchedAt`/models); TTL-gated refetch, staleness bounded, unparsable price stays unknown, unparsable timestamp is maximally stale. 5/5.
- [x] F2b `68dc228`: `config validate --live-check` resolves per-pool-model evidence; `LiveCheckReport` renders `alias: ctx=… in=$…/M out=$…/M tools=… fresh=…` purely, with the admission verdict as its own concern. Real run against the live catalog, exit 0: `nemo: ctx=131072 in=$0.019/M out=$0.030/M tools=true fresh=true` (plan's example format reproduced exactly), plus `granite: … tools=false`. Unresolved evidence refuses with exit 2; without the flag nothing is fetched (evidence timestamp unchanged across runs).
- [x] F1 `b0df19d` (pre-resumption): paid admission evidence-gated — priceless, stale or missing profiles exclude the candidate.
- [x] F3a `236ea2b`: `ActiveRouter` builds candidates from REAL evidence and offers the decision exactly those labels. 7/7. Two guards proved necessary by test: the decision labels must equal the candidate ids (a mismatch degrades every decision to the baseline by construction), and a configured baseline/fallback that evidence excludes must fail at STARTUP (`routing.fallback quality@medium is not an executable candidate (no-tool-support)`) instead of per-turn `NO_FEASIBLE_ROUTE`.
- [x] F3b `a84ab64`: live turns execute the resolved candidate. `LiveTurnDriver` gained `routeDecision`/`executed`; `ChatCommand` builds the router from `ProfileEvidence` (fail-closed: absent or stale evidence → excluded unless `allowStale`). Shadow mode re-probed unchanged (4 candidates, granite excluded, baseline executes, exit 0). No provider rebuild needed: `ToolLoop.generate(ModelRef, ReasoningPolicy, …)` already takes the model per call.
- [x] F3c (local config only, `config.local.json` is gitignored): `routing.mode` flipped `shadow` → `active`. **The model now changes**: `route: suggested=- executed=qwen@default mode=ACTIVE degraded=true fallback=decision-rejected` — Jev chose `oss@medium` at confidence 0.06, the resolver rejected it, and the FALLBACK executed (not the baseline `nemo@default`), with real tool calls and exit 0. A second run also executed `qwen@default`. Fail-closed paths observed live: malformed probabilities (`sum 0.99`) → `fallback=decision-missing`, degraded, fallback executed.
- Test-side defects I hit and fixed rather than worked around: the price renderer dropped the `/M` suffix and rendered `$0.03` beside `$0.019` (now quantised to a 3-decimal floor); `ConfigLoader` already refuses a missing pool, so my ActiveRouter-level guard for it was dead code and was removed; `ExecutionCandidate` exposes `reasoningPolicy()`, not `policy()`.

### Open finding — the session cost allowance is not a pre-dispatch gate (pre-existing, NOT introduced by F3)

Reproduced with `session.maxCostUsd = "0.00"` and active routing: the turn ran, the provider billed `$0.000008`, and the run reported `ledger=0 USD`, exit 0. Cause: `LiveTurnDriver.account()` calls `Ledger.tryReserve` AFTER dispatch, and on an empty reservation it returns silently — so the allowance never stops a turn and a refused reservation leaves the spend unrecorded rather than terminating the run. `session.maxTurns` IS enforced (`beginTurn` throws → exit 3). F3 changed nothing in the ledger path. This is a real unbounded-spend hole for any long live run and wants its own TDD slice (reserve the per-run cap BEFORE dispatch; refuse the turn with exit 3 on refusal) — deliberately not folded into F3.

| Slice | Status | Evidence (commands + results) | Review | Commit(s) |
|---|---|---|---|---|
| S01 | done | `./mvnw verify` BUILD SUCCESS (4 modules); core tests 4/4; `./bin/rahu demo` exit 0 (stdout answer, stderr footer, JSONL trace); javap major version 71 (Java 27 preview agreement) | docs/reviews/004-s01-build-review.md | 264c670 |
| S02 | done | core tests 17/17 (candidate exclusions A04/A05, routing table A03/A06/A07, 200-trial invariant sweep); `./mvnw verify` BUILD SUCCESS | docs/reviews/005-s02-routing-review.md | 036ba39 |
| S03 | done | cli tests 10/10; packaged `config validate`/`route inspect`/exit-2 error paths verified against examples/offline.json; `./mvnw verify` BUILD SUCCESS (27 tests) | docs/reviews/006-s03-config-cli-review.md | ad9ddc0 |
| S04 | done | openrouter tests 7/7 (request mapping, effort/Disabled, tools, ordered calls + continuation capture, usage unknown-not-zero, length-incomplete, 401/429 typed) | (folded into build-status; S04 review in S05 review evidence) | 5865f38 |
| S05 | done | systemone tests 7/7 (envelope shape, probabilities+raw confidence, invalid label rejected, malformed/NaN typed, noul bounds, 500/429, truncated rejected); 38 tests total | docs/reviews/007-s05-systemone-review.md | 1e4c73a |
| S06 | done | 30 new runtime/privacy/authority tests (A12/A13/A24/A25/A27/A33/A34); 76 tests total; verify green | docs/reviews/008-s06-runtime-privacy-review.md | f97d4d9, 8f62289, ce29b13, e7c346b |
| S07 | done | 21 new tool tests (A08/A09/A24 + registry freeze); 92 tests total; verify green; jdtls 1.62.0 snapshot caught 9 write-time defects this slice | docs/reviews/009-s07-tools-review.md | 22a0384, 9bd34d8, 85f9bfb, 1f93741, 5ed0310 |
| S08 | done | 8 new trace/replay tests (A10/A11/A16); 100 tests total; verify green | docs/reviews/010-s08-traces-replay-review.md | df6fbea |
| S09 | done | 17 new context tests (A14/A22/A23/A29/A32) + scripted two-turn chat run; 117 tests total; verify green | docs/reviews/011-s09-context-chat-review.md | 6e22c49, 7a622c2 |
| S10 | done | 9 new eval tests + packaged eval run: smoke-v1 6/6, dogfood 3/3, exit 0, honest offline costs; live refused pending G09 prereqs; 123 tests total | docs/reviews/012-s10-eval-runner-review.md | dae0e30 |
| S11 | done | clean-clone G01 ritual PASS (128 tests, demo/eval 6/6); config.schema.json generated+tested; DotEnv .env key source (env-first); cheap-model pool validated against live catalog; README quickstart | docs/reviews/013-s11-packaging-review.md | 38226b9, dfe1b55, 5ff95cf, ff9add0 |
| S12a | done | live wiring: OpenRouter decisions (Jev) + budgeted live chat; 134 tests; LIVE turn verified (Jev shadow decision + nemo answer, 533ms, $0.000004 settled) | docs/reviews/014-s12a-live-wiring-review.md | 761a0df |

## Open blockers

- G09 live dogfood: BLOCKED — OpenRouter API key ABSENT (presence check only, value never read);
  decision service at 127.0.0.1:8000 ABSENT. Offline work unaffected; live prerequisites list
  recorded per docs/runbooks/dogfood.md.

## Environment pins

- JAVA_HOME: /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home — OpenJDK 27 GA,
  build 27, 2026-09-15 (matches ADR 0004 planning baseline). Set per session:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- Note: operator requested Zulu 27 via sdk; SDKman install failed 3x (broker URL broken,
  corrupt-archive each time) and manual install was blocked by the operator; build uses
  Homebrew OpenJDK 27 per operator instruction ("Use the brew install and proceed").
- Maven: 3.9.9 (wrapper pinned via -Dmaven=3.9.9), runs on JAVA_HOME above.
- Verified 2026-10-01 from repo1.maven.org metadata: junit-jupiter 6.1.3,
  jackson-databind 2.19.0, picocli 4.7.7, maven-shade-plugin 3.6.2,
  maven-surefire-plugin 3.6.0, maven-compiler-plugin 3.16.0 (latest stable; 4.0.0-beta-5
  rejected as beta).
- Package roots: rahu.core / rahu.openrouter / rahu.systemone / rahu.cli.
- Rollback note: build rollback = `rm -rf ~/work/ai/github/java-based-agentic-harness/rahu`;
  nothing outside this directory is modified by the build.

## Preflight record (T0)

- git identity: set; clone over SSH verified (HEAD = baseline 130588a).
- Live prereqs: OPENROUTER_API_KEY absent; System One decision service absent (curl 127.0.0.1:8000).
- Dependency/version checks: latest stable versions recorded above; exact versions used.
