# S13 architecture and design audit (N1)

Date: 2026-10-02. Change: architecture and design audit of the whole codebase at
`7ae0e48`, plus the five refactors the audit justified. Affected requirements: all of
them, since this is a cross-cutting review; G10 is the release-wide critique this
document discharges.

Hypothesis: the build is feature-complete and green, which is the moment an audit is
worth doing, because the code has stopped moving and can finally be seen whole. The
predicted shape was faults in the seams rather than in the files.

That prediction was right, and the faults were worse than expected. Five findings, two
of them HIGH, and three of the five share one root cause: **a documented or configured
capability that the code did not implement.** That is the same class as the unreachable
`DecisionResult.ValidScore`, and it had appeared three more times since.

## Baseline, measured rather than quoted

`unset OPENROUTER_API_KEY && ./mvnw clean verify` at `7ae0e48`: BUILD SUCCESS, **265
tests** (100 `rahu-core`, 14 `rahu-openrouter`, 26 `rahu-systemone`, 125 `rahu-cli`).
This confirms the counts in `prompt2.md` section 1 exactly.

Main-code LOC, measured: `rahu-core` 2860, `rahu-openrouter` 489, `rahu-systemone` 527,
`rahu-cli` 3987. Largest files: `ConfigLoader` 454, `LiveTurnDriver` 338, `ToolLoop` 326.
No `System.out` or `printStackTrace` in main code. `rahu-core` imports no okhttp,
retrofit or Jackson. The dependency arrows point inward.

**Discrepancy to record:** `prompt2.md` section 1 states HEAD is `0a6f84f`. The real
HEAD at audit time was `7ae0e48`, one commit later. Every other fact in that table
checked out.

## Findings

### F-1 — A withheld search observation was re-delivered verbatim

- Location: `rahu-cli/src/main/java/rahu/cli/live/ToolLoop.java:241-250` (fixed in
  `4067ea0`)
- Severity: **HIGH**
- Risk: `observeAll` computed the gated text at `:241`, then handed the **raw**
  observation `texts.get(i)` to the reranker at `:249`. In injection `ENFORCE` those
  two differ, so for a `workspace.search` call the reranker re-delivered exactly the
  content the injection gate had withheld. The gate's own verdict was discarded one
  line later. Shadow mode could not surface it, which is why the earlier live probe
  reporting `verdict=WOULD_WITHHOLD` looked safe.
- Unblocks: any future second gate or transform inserted into the observation path. The
  bug was a stale reference, and a stale reference is what a new stage invites.
- Proof obligation, discharged: a red test that puts the search term **on** the poison
  line, run against the old code.

The instructive half is the test. `ToolLoopRerankTest.rerankCannotResurfaceWithheldText`
already claimed to cover exactly this invariant, and it passed. Its fixture put the
search term on line 1 and the poison on line 2, so `workspace.search` never matched the
poison line and the assertion could not fail. It was decoration that read like
coverage. This is the fifth recorded instance of this project's own lesson
(`prompt2.md` section 10: read the existing fixture before trusting it).

### F-2 — The advisory tool-relevance judgment changed nothing

- Location: `rahu-cli/src/main/java/rahu/cli/live/LiveTurnDriver.java:152-165` and
  `:143-146` (fixed in `ada86eb`)
- Severity: **MEDIUM**
- Risk: every live turn asks the decision plane which tools are relevant.
  `ProfileDecider` computed the narrowed set, `TurnProfile` carried it,
  `LiveTurnDriver` **printed** it — and then handed the un-narrowed `ToolRegistry` to
  the tool loop. `tools.md` line 7 requires "the generation model receives only the
  permitted relevant tools". The judgment was computed, paid for, printed, and
  discarded: a decision-plane call per turn spent on nothing.
- Unblocks: a second decision provider or a second tool set. Relevance is the seam that
  a tool plugin would ride, and it was decorative.
- Proof obligation, discharged: red test asserting on the descriptors the model is
  offered, not on the profile object.

Note for the record: `TurnProfile.from` maps an empty judged set back to the full
permitted set, so a decision judging **every** tool irrelevant is indistinguishable
from an unjudgeable one. "Narrow to nothing" is inexpressible. Changing that is a
semantics change to fail-closed narrowing and needs its own decision.

### F-3 — The loopback plaintext exemption was a string prefix match

- Location: `rahu-core/src/main/java/rahu/core/privacy/PrivacyGate.java:65-77` (fixed
  in `4002166`)
- Severity: **HIGH**
- Risk: `endpointPolicy` recognised loopback with `startsWith`, so the plaintext
  exemption — the single place the privacy contract permits unencrypted outbound
  traffic — was granted to any host whose name merely began with a loopback name:

  ```
  http://localhost.evil.example/api     allowed
  http://127.0.0.1.evil.example/api     allowed
  http://localhosting.example           allowed
  http://localhost@evil.example         allowed
  ```

  None are loopback. Approved prompt content would have gone out in cleartext to them.
  The endpoints come from config, so this needed a mistyped or malicious host, not an
  adversary. Now decided by the parsed host via `URI.getHost()`; unparseable or
  authority-less URIs are refused, because this sits on the disclosure path.
- Unblocks: any additional endpoint policy (a proxy, a mTLS gateway, a Unix socket).
- Proof obligation, discharged: 6 of 14 new cases red on the old comparison.

One of my own test assertions was wrong and the code was right: I asserted `127.0.0.10`
is not loopback. It is — the whole `127.0.0.0/8` range is. Test corrected, and the
range is now asserted in both directions.

### F-4 — `tools.enabled` and `tools.exclusions` were parsed, then ignored

- Location: `rahu-cli/src/main/java/rahu/cli/ChatCommand.java:143-146` and
  `rahu-cli/src/main/java/rahu/cli/config/ConfigLoader.java:307-317` (fixed in
  `990647f`)
- Severity: **MEDIUM**
- Risk: both keys are documented in `configuration.md` line 24 and both appear in the
  committed schema, and both were bound into `RahuConfig.ToolsConfig` — then read by no
  production path. The registry was built with `ToolRegistry.withWorkspace(boundary)`,
  which returns all three tools whatever the config says, and the boundary was built
  with the single-argument constructor that discards `exclusions()`. An operator who
  disabled a tool, or excluded a path, got the default behaviour anyway.
- Unblocks: any operator-facing tool restriction. This is the whole point of a
  configuration surface being a public API.
- Proof obligation, discharged: the enabled/exclusions cases do not **compile** against
  the old code, because the seam they test did not exist. That is a stronger statement
  than a red assertion.

### F-5 — Directory exclusions matched case-sensitively

- Location: `rahu-core/src/main/java/rahu/core/tools/PathBoundary.java:105` (fixed in
  `990647f`)
- Severity: **MEDIUM**
- Risk: `excludedDirs.contains(name)` while `excludedNames.contains(lower)` sat two
  lines above. `.GIT/config` read where `.git/config` would not. A case-sensitive
  exclusion list is a bypass on any case-preserving filesystem, which is the default on
  macOS and Linux.
- Unblocks: nothing directly; it removes a trap from a boundary others will extend.
- Proof obligation, discharged: red on the old comparison.

## What holds up, with evidence

Stated plainly because an audit that finds nothing is a failed audit, and so is one that
cannot say what is sound.

**The decision/generation seam is clean, and it is the project's thesis.** I traced one
turn end to end through `LiveTurnDriver`. Every decision-plane value passes a Java-side
check before it is acted on: `RouteResolver.resolve` (`rahu-core/.../RouteResolver.java:45-75`)
rejects an out-of-set, non-normalised or below-floor choice; `ToolRelevanceGate.apply`
(`rahu-core/.../ToolRelevanceGate.java:15-26`) can only narrow and throws on an
out-of-set name; `CompactionPolicyDecider` keeps the deterministic fit check as the
final say. **No confidence value reaches execution unexamined.** That was the audit's
most serious possible finding and it does not exist.

**The error taxonomy at the decision boundary is the strongest thing in the codebase.**
`DecisionResult` is a sealed hierarchy, and the four score-parser traps each degrade
**alone** to a typed `Failure` without collapsing the batch. A subagent independently
verified by driving the real adapter over a loopback socket that an unknown `Question`
implementation throws loudly rather than degrading quietly. I checked its one sharp
edge: `Question` is a plain marker interface, not `sealed`, so a new type compiles
anywhere and fails at `questionId()` — late, but loud, never silent.

**The filesystem boundary is genuinely good.** `PathBoundary.resolve` rejects absolute
paths, `~`, `..`, escapes, symlinks at every segment *and* via `toRealPath`, and
`WorkspaceTools` returns a typed `ToolResult.invalid`/`failed` rather than throwing.
A subagent attacked the argument parser with an escaped-`"`-needle and could not make
it misparse; `CanonicalJson` preserves the backslashes. No finding.

**Degradation is designed, not accidental, almost everywhere.** `SearchReranker` fails
soft to filesystem order with `UNJUDGEABLE` and states why: it makes no safety claim,
only an ordering claim. `InjectionGate` makes a safety claim and documents that an
unjudgeable observation is deliberately *not* withheld because the privacy gate owns the
disclosure boundary. That distinction is the design, and the code matches it.

**The cost gate genuinely is pre-dispatch.** `LiveTurnDriver.java:204-213` reserves and
returns exit 3 *before* `toolLoop.generate` at `:216`, and `CostGateTest` asserts on the
observable consequence — was the provider billed — rather than on call ordering. This is
the test-quality discipline the project claims, and here it is real.

## Ranked lists

**Fix now — done in this session, one concern per commit, full suite green between:**

| # | Finding | Sev | Commit | Tests |
|---|---|---|---|---|
| F-1 | Withheld search observation re-delivered | HIGH | `4067ea0` | 265 → 266 |
| F-2 | Relevance judgment discarded | MED | `ada86eb` | 266 → 267 |
| F-3 | Loopback exemption by prefix | HIGH | `4002166` | 267 → 281 |
| F-4 | `tools.enabled`/`exclusions` ignored | MED | `990647f` | 281 → 287 |
| F-5 | Case-sensitive directory exclusions | MED | `990647f` | (same commit) |

Final state: **287 tests green** (114 core, 14 openrouter, 26 systemone, 133 cli).

**Leave, with reasons:**

- **`ConfigLoader` at 454 lines.** Sixteen `bindX` methods, each three to six lines, one
  per config section, plus a known-key set and four primitive readers. The obvious seam
  is "one binder per section", but each section is already its own method, so splitting
  would move files without moving decisions. The real pressure is that it is the only
  place that knows every key, which is what makes the accepted-key test valuable.
  Splitting it now would be tidying, not design.
- **`DecisionEngine` has one production implementation and eight test fakes.** This is
  the sharpest available test of whether a port is real, and the asymmetry is genuine.
  But it is an acceptable *stage*, not a defect: `LiveWiring.decision` already selects
  between two adapter names over one interface, so the second implementation is a
  configuration away, and building a second one now to satisfy a metric would be
  inventing a consumer. ADR 0002 says fakes and rules are "explicit
  offline/fallback implementations", and the offline path genuinely needs no decision
  plane at all — `ChatCommand.runOffline` answers deterministically without one. The ADR
  is imprecise rather than the code being wrong.
- **`Tool` as a final value class with an `Executor` function** rather than a
  subtypable port. Sound, not a missing abstraction: the charter defers all effectful
  tools, so there is no second implementation to accommodate, and a value class is
  strictly harder to misuse than an interface hierarchy.
- **Two `confidenceFloor` defaults.** `ConfigLoader:218` uses 0.65 and
  `ActiveRouter:43` declares `DEFAULT_CONFIDENCE_FLOOR = 0.0`. The latter is unreachable
  through the loader, which always supplies a value. It is a latent trap rather than a
  live bug; recorded as F-6 below rather than fixed, because changing a routing default
  on an audit's judgement is exactly the authority decision the charter reserves.
- **Rerank and injection are separate dispatches.** Deliberate, documented in code, and
  the reasoning is sound: batching them means asking one model two unrelated questions,
  trading latency for a correlation you do not want inside a safety judgement. Not
  "optimised" away.

**Cannot be judged until the live evidence in `prompt2.md` section 4 lands:**

- Whether the advisory relevance narrowing (F-2, now real) actually improves answers.
  Narrowing is now wired, so this becomes measurable where it previously was not.
- Whether `routing.mode: active` in the untracked `config.local.json` is the right live
  behaviour. Still an operator decision, still living in a file no review sees.
- Spec conformance against Laya/Kev specifically. `systemone.md:34` is right that
  request compatibility is not response parity.
- Rerank ranking quality. No benchmark in this repository measures it, and a
  benchmark from a different corpus is not evidence about this one.

## Open findings recorded without a change

**F-6 — Two sources of truth for `routing.confidenceFloor`.**
`ConfigLoader.java:218` (0.65) and `ActiveRouter.java:43` (`DEFAULT_CONFIDENCE_FLOOR =
0.0`). Unreachable today; a trap for whoever routes a hand-built config. Unblocks:
nothing yet. Fixing it means choosing which number is authoritative, which is an
operator decision.

**F-7 — `Question` is not `sealed`.** `DecisionEngine.java:106`. A new question type
compiles without registration and fails at `questionId()` instead. Sealing it with an
explicit `permits` clause would move that to compile time — which is precisely what the
`ScoreQuestion` incident argues for. Not done here because it is a breaking change to a
port with one production implementation and no second implementor to negotiate with;
the day a second provider arrives it becomes obvious and cheap.

**F-8 — Transport faults are collapsed to one kind.**
`SystemOneHttpAdapter.java:117-121` maps all transport failures to `TIMEOUT`, so a read
timeout (ambiguous: the request may have been processed and billed) is
indistinguishable from a connect failure (definite: nothing left the host). The ledger
handles ambiguity correctly — `markUncertain` retains full liability — so the cost
consequence is sound even though the diagnostic is coarse.

**F-9 — `tools.resultBytes` is also inert.** Same shape as F-4: bound at
`ConfigLoader.java:316`, read by no production path, while `WorkspaceTools` uses its own
`MAX_READ_BYTES` constant. Left for a follow-up with F-4's siblings rather than bundled
here, since it is a behaviour change to tool output bounds and deserves its own proof.

## Method, and what I would not claim

Findings F-1, F-3, F-4 and F-5 were found by reading the seams named in `prompt2.md`.
F-2 and the two vacuous-test findings came from writing the red test before trusting the
green one — the test that caught F-1's fixture was written because the fixture looked
wrong, and it was.

I delegated two read-only lenses (failure typing; test quality and port extensibility)
with instructions to cite `file:line` and to say plainly when a boundary held up. I
verified their highest-severity claims myself before acting on them — the loopback
prefix match and the case-sensitive exclusion list — and both were real. The remaining
subagent findings I have recorded as opinions rather than facts, because I did not
re-verify them.

What this audit does **not** claim: that the codebase is now correct. It claims that five
specific defects had specific proofs, that the seams named as most consequential hold
up under tracing, and that the remaining findings are recorded with enough precision to
act on.

## Residual risk

- Three of five findings were "a capability that was specified but not implemented".
  The accepted-key test catches a key that lands unnoticed; nothing catches a key that
  lands without effect. That gap is structural and is the most valuable follow-up.
- `ConfigLoaderTest` and `CostGateTest` build configs with `tools.enabled` listing all
  three tools, which is exactly why F-4 was invisible: the fixture never varied the key.
- F-8's ambiguity collapse means a live billing surprise would be attributed to the
  wrong cause in a trace.