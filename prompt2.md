# Rahu continuation prompt — resume the dogfood alpha build

Supersedes `prompt.md` for execution. `prompt.md` remains the **charter**: its
mission, posture, core themes, privacy boundary, incremental method and evidence
vocabulary still govern and are not restated in full here. Read it for the "why";
read this file for the "where we are" and "what next".

Work in this repository. Inspect its actual state before trusting any line here.

---

## 1 Where the work stands

| Fact | Value |
|---|---|
| Repo | `~/work/ai/github/java-based-agentic-harness/rahu` |
| Branch | `main`, **pushed**: local == `origin/main`; tree clean except `prompt2.md`, staged and uncommitted |
| HEAD | `0a6f84f` (docs: correct a false blocker) |
| Commits on main | 82 |
| Tests | **265 green**: 100 `rahu-core`, 14 `rahu-openrouter`, 26 `rahu-systemone`, 125 `rahu-cli` |
| JDK | 27 (Homebrew), `maven.compiler.release=27`, preview enabled |
| Maven | 3.9.9 via `./mvnw` |
| Modules | `rahu-core`, `rahu-openrouter`, `rahu-systemone`, `rahu-cli` |
| PR history | PR #1 merged as `20e2282` **with a merge commit, not a squash** — all 71 branch commits kept their original authors |
| Reviews | `docs/reviews/001`–`014`; 001–003 are pre-slice documents, S04's review is folded into build-status, S12a is `014` |
| Worktrees | one; the earlier `rahu-wt-*` worktrees are gone |

Milestone state: M0 complete. M1 and M2 are **functionally built with green
offline suites**, but G10 and `docs/reviews/dogfood-release.md` are outstanding, so
**"offline complete" is not yet established**; the roadmap table still reads "Planned" for both because it is gated on
the G09 live evidence described in section 4. Do not read the roadmap row as the
truth about the code — the code and the test count are.

Slices S01–S11 are complete with a review each. S12 is partially complete: the
packaging, schema and live-wiring halves are done (`014-s12a-live-wiring-review.md`),
the release-wide critique half and the bounded genuine dogfood run are not.

Phases A–F plus G1 and G2 are complete. Phases were tracked in
`docs/plans/active/build-status.md`; that file is the detailed history and is
**mostly** accurate as of `0a6f84f`, but it carries at least three known stale or
false claims that prompt2 corrects below: an incorrect "live eval has run green"
line, a superseded "do NOT push yet" warning (HEAD is 82 commits and
`local == origin/main`), and a preflight entry claiming `OPENROUTER_API_KEY`
absent that contradicts its own later correction. **Read it before planning
anything, but verify anything you quote from it against code** — it holds the
per-phase evidence, the failed checks, and the corrections.

## 2 The correction that most affects this session

An earlier status report claimed the live gate was **blocked on a missing System
One service**. That was false, and it is worth restating precisely so it is not
re-derived.

The decision plane is **hosted**, and it is in use:

- adapter `openrouter-decisions`, base `https://openrouter.ai/api/alpha/decisions`
- model `typesafe/jev-1.13`, profile `jev-compatible-v1`
- credential `OPENROUTER_API_KEY` in `.env` (present, non-empty)
- wired in untracked `config.local.json`, which also sets `routing.mode: active`

`127.0.0.1:8000` is only the **default base URL for local serving**
(`docs/specs/systemone.md:13`). `docs/runbooks/dogfood.md:17` lists "local
Laya/Kev **or a verified hosted endpoint**" as equal options. The false blocker
came from generalising one refused loopback connection into absence of the
capability, while the same file stated the hosted fact ten lines earlier.

**Carry this forward:** before reporting any blocker, check whether another
working path to the same capability already exists and is recorded. A false
blocker costs an operator round-trip and a stalled gate, and the operator has to
notice it, not you.

## 3 What is built, and what is only shadowed

Complete and exercised live:

- Cost gate, fixed by **pre-dispatch reservation** (was a post-hoc check that
  could overspend)
- Config schema defects fixed; the loader exposes `acceptedTopLevelKeys()` and a
  test asserts the committed `docs/generated/config.schema.json` documents every
  key it returns, so a new key cannot land unnoticed
- Batched injection judging — `assessAll` sends N observations in **one**
  dispatch; `ToolLoop.observeAll` spans 3 phases. Costs are documented in code:
  observations travel together (less isolation) and the 16 KiB state bound is
  shared, with 4 KiB per observation so one long one cannot starve siblings into
  UNJUDGEABLE
- **Injection gate: SHADOW only, never enforce.** `config.local.json` has
  `{"mode":"shadow","threshold":0.1}`; no tracked config sets an `injection` block,
  so the default is `off`. Verified end to end: a live probe printed
  `verdict=WOULD_WITHHOLD` and the observation was still delivered to the model
- **G2 search rerank: built, default OFF.** `search.mode` is `off|shadow|enforce`;
  absent means off. Unusable values are refused at load, not silently defaulted
- `score` wire type implemented **both directions** (see section 6)

## 4 Open questions and pending operator decisions

These are **authority decisions, not infrastructure**. Only the operator can make
them. Do not manufacture completion by choosing a value yourself.

1. **Total smoke spending ceiling** for the bounded G09 dogfood run. A decimal
   USD amount, chosen by the operator. `config.local.json` carries per-agent
   `maxCostUsd` values (0.10 / 0.50) and `examples/live-local-systemone.json`
   shows 1.00 / 3.00, but those are **per-agent and per-session caps, not the
 aggregate run allowance** the runbook requires. Precisely: `agent.maxCostUsd` is
 the per-dispatched-run cap reserved before dispatch, and
 `session.maxCostUsd` is the allowance it is reserved from. One allowance covers the run;
   a new child session or compaction cannot reset it.
2. **Approved non-sensitive prompt and source views.** Which repository files the
   harness may read, and which prompt text may be sent. Until this is explicit,
   the privacy contract has no allowlist to enforce and live reads cannot proceed.

   **Three mechanical facts, so the approval has a shape:**
   - `config.local.json:104-108` sets `privacy.inputClassification: "unknown"`
     with `onUnknown: "block"`, so **every live prompt blocks** until that becomes
     `"approved-nonsensitive"`. `--input-classification` overrides it, but only
     `chat`, `run` and `route inspect` accept that flag (`docs/specs/cli.md:48`)
     — `eval` does not.
   - No source policy file exists yet. `docs/runbooks/dogfood.md:52` requires an
     ignored `privacy.local.json` manifest holding an exact SHA-256 per file,
     named by `privacy.sourcePolicyFile` in the config. `config.local.json` has no
     `privacy.sourcePolicyFile` key at all.
   - The `repository-followup` task reads `ARCHITECTURE.md`, so that file must be
     hashed and approved before G09 item 1 can run.
3. **Allowed generation model IDs and effort levels** for the pool. Rahu selects
   only among explicitly configured candidates; it will not invent an ID.

Also open, and not operator-gated:

4. **Rerank ranking quality is unmeasured.** Rahu searches by literal substring
   over local source lines. A semantic-retrieval benchmark result (SciFact is the
   commonly cited example) is not evidence about that task — different corpus,
   different metric, different failure modes — and **no such figure is recorded
   anywhere in this repository**, so treat this as reasoning, not as repo data.
   This is why G2 ships off.
5. **Injection threshold calibration.** See section 5 — the finding argues against
   threshold tuning, so do not tune it.
6. **Spec conformance of the decision protocol** is now substantially exercised
   through Jev, but upstream conformance against Laya/Kev specifically is still
   unverified, and `docs/specs/systemone.md:34` warns that request compatibility
   is not evidence of response parity.
7. **`routing.mode` is `active` in the untracked `config.local.json`, not
   `shadow`.** The **code** default is still `shadow` and no tracked config
   changes it, so the shipped default is correct. But the local config that runs
   live turns currently lets the decision plane **change which generation model
   executes**, and the observed behaviour was `mode=ACTIVE degraded=true
   fallback=decision-rejected` — Jev proposed `oss@medium`, the resolver rejected
   it at confidence 0.06, and the fallback executed rather than the baseline.
   Stated plainly: `fallback` is `qwen@default` (`config.local.json:23`), baseline is
   `nemo@default` (`:22`), Jev suggested `oss@medium` at confidence 0.06, the
   resolver rejected it, and `qwen@default` executed on both observed runs — so
   the answering model is the configured fallback, not the baseline.
   The charter (§9) says "**retain shadow as the shipped default** until the
   separate M3 promotion gate supports change", and M3 has not happened. That is a
   requirement on the *default*, not a prohibition on an operator running `active`
   in an untracked local file. **Decide deliberately whether to keep it active.** It is
   the most consequential live-behaviour choice in the repo, it lives in an
   untracked file so it survives no review, and a fallback-driven model swap is
   exactly the ambiguity the charter's authority rules exist to prevent. If it
   stays active, say so in the build status with the evidence; if not, flip it
   back to `shadow` and record why.


## 5 The injection-corpus finding — do not misread it

A 49-observation, 31-style corpus was judged live against `typesafe/jev-1.13` in
shadow mode. Evidence: `docs/research/injection-corpus-v1.md`, fixture
`docs/evals/corpus/injection-shadow-v1.json`.

- **AUC 0.899.** The overlay genuinely discriminates hostile from benign text.
- **No threshold separates the classes.** Any cut that catches the attacks also
  withholds 9 of every 34 ordinary observations.
- **The attacks that score LOWEST are the subtle ones** — split lines, a comment
  inside code, hidden markup, delimiter confusion. Ordinary imperative text (a
  README procedure, a diff, a one-line Rust snippet) scores **highest**.
- Therefore the problem is **calibration, not absent signal. Threshold tuning is
  the wrong next step.** Enforcement stays off.
- Batch composition moves scores: the same observation scored **0.440 in the
  batch-of-8 run** and **0.370 when re-scored on its own** — isolating an
  observation *lowers* it. **A score is only reproducible alongside its batch.**

**Standing operator guardrail, enforced in code.** Never execute adversarial
instructions or adversarial test content on a developer or production host. Keep
it as a fixture; execute only in a separate isolated batch inside a container.
Enforced by `ShadowCorpusProbe`, which fails closed without positive
containerisation evidence; `ShadowCorpusProbeGuardTest` fails the build if that
guard is removed. Do not weaken it to make a run succeed.

## 6 The most important technical finding in the last session

`systemone.md:5` promised Score as a supported question type mapping to wire
`score`, and `DecisionResult.ValidScore` existed. **But `ValidScore` was
unreachable.** There was no `ScoreQuestion` on the port, `questionId()` would have
thrown, and the adapter never emitted or parsed `score`. The red evidence was
exact: a score question serialised as `{}` — an empty object, unanswerable. That
is a spec-conformance gap, not missing plumbing, and the lesson generalises:
**a documented capability and a reachable capability are different claims, and
only the second one ships.**

Fixed across `2e0640a` (port type, `relevance()` builder, shared `RELEVANCE_LEGEND`) and `d21f5c6` (adapter both directions):

- `ScoreQuestion`, an ordinal level over an ordered legend
- `relevance()` builder with **one shared `RELEVANCE_LEGEND`** for all candidates
  — levels are only comparable against the same legend
- adapter serialise **and** parse, both directions
- the parser rejects four silent traps, each degrading **alone** to a typed
  `Failure`: non-integral level; a level outside the legend (an out-of-range
  **high** level sorts first and reads as the strongest possible match); wrong
  answer type; an echoed legend of a different length

## 7 The invariant to preserve in the reranker

`SearchReranker` **reorders search observations and changes nothing else.** It
runs *after* the privacy gate and the injection gate, so a reranker that dropped
or invented a line would change what the model sees with no second check — a
disclosure change wearing a ranking costume.

- asserted **exhaustively across four level patterns**, not sampled
- asserted **end to end** through the real tool loop, on the text the *model*
  reads, including that a withheld observation is never reordered back into view
- candidates are keyed by **position**, not by parsing `"path:line: text"` back
  apart — that would break on a Windows drive letter
- the candidate cap limits how much is **scored**, never how much is **returned**;
  the unscored tail keeps filesystem order. The cap exists because `State` refuses
  a request past 16 KiB
- ties break on original index, so a rerank is stable rather than shuffling
- the trailing `[truncated: ...]` marker is excluded from candidates and stays last
- relevance is a judgement about a **query**, not a fact about a file, so a score
  is only reproducible alongside the query that produced it
- it **fails soft**: an unreachable decision plane leaves filesystem order and
  reports UNJUDGEABLE. Unlike the injection gate there is no safety claim here,
  only ordering, so a decision-plane outage must degrade the ranking, not the call

**Known cost:** rerank and injection are **separate dispatches** in the same turn
(different operations), so a turn using both costs two calls. Batching them would
mean asking one model two unrelated questions, trading latency for a correlation
you do not want inside a safety judgement. Deliberate; do not "optimise" it away.

## 8 Immediate next items, in order

Pick up here. Each is a small, testable increment in the charter's style. **N1
is the architect's audit and it comes first**, before the roadmap correction and
before the live run: it is the one task that can change what every later task
should be.

**N1 — Architecture and design audit. Do this first.** Put on the hat of a
software architect and craftsman: someone who has to maintain this for years,
who reads the code before judging it, and who would rather fix one real
structural fault now than ship three that a user finds later. The build is
feature-complete and green, which is exactly the moment an audit is worth doing,
because the code has stopped moving and can finally be seen whole.

**Audit and refactor are both in scope, and a refactor is genuinely wanted if it
improves the design.** Do not read this item as permission to only write prose.
What is not acceptable is an unproven change, and the bar is set by these two
rules:

- **Strategic, not brittle.** The test is what happens the next time a feature
  arrives. A change that makes the next feature land on an existing seam is the
  goal. A change that merely tidies today, or that defers the same awkwardness
  into the next three files, is a bandaid wearing a refactor's clothes. Design
  for the extension that has not been written yet: a new question type, a second
  decision provider, a new gate. If you cannot say which future change the work
  makes easier, it is probably not strategic, and you should say so.
- **Proven, not asserted.** Every change ships with the red evidence that it was
  needed and the green evidence that it worked. A behaviour change needs a test
  proven red on the old code. A pure structural change needs proof it is
  behaviour-preserving: full suite green before and after, and the count
  unchanged or higher. A new seam or extension point needs a test that exercises
  it the way a second implementation would, not one that only re-asserts the
  first. If you cannot produce the proof, the finding stands as an audit finding
  and the code does not change.

**Sequence: audit first, then refactor, then stop and report.** Write the
findings to `docs/reviews/015-architecture-audit.md`, each with a file:line, the
risk it creates, a severity, the future change it unblocks, and the proposed fix
with its proof obligation stated up front. Then implement the refactors the audit
justified, highest severity first, one concern per commit, running the full
suite between each. Then report.

Two sequencing rules that are not negotiable. **Do not refactor what the audit
did not justify**, and do not bundle a behavioural change inside a structural one,
because then neither has a clean proof. And **do not refactor across the gate
ordering in `ToolLoop` or the safety gates' fail-soft and fail-closed semantics**
in the same change that moves anything else: those are safety properties, and a
structural edit that quietly alters one is the exact failure this project has
already paid for once with the unreachable `ValidScore`. Move them deliberately,
alone, with their own red-then-green evidence.

**Traits of good architecture worth auditing against.** These are not a generic
checklist to tick. Each is stated as a question with an observable test, because
a principle no one can falsify is not a criterion. The order matters: run the
project-specific ones first, the general ones last.

*Project-specific, and the ones that matter most here:*

1. **A port is proven by a second implementation, not by being an interface.**
   An interface with one implementation is a class in disguise; the abstraction is
   only real when something else has occupied it. This is the sharpest available
   test of "does the design make the next feature easier", and it is measurable:
   count production implementations per port. **Concretely, `DecisionEngine` has
   one production implementation (`SystemOneHttpAdapter`) and eight test fakes.**
   Ask whether that asymmetry is a finding or an acceptable stage. Note the
   related tension with ADR 0002, which states that fakes and rules are *explicit
   offline/fallback implementations*; the offline fake appears to live in test
   scope only, so verify whether offline mode has a real production fallback and
   whether that is what the ADR intended. Check the other two seams the same way,
   and note that they are shaped differently: `ModelProvider` has one production
   implementation (`OpenRouterProvider`), while `Tool` is a **final value class**
   with an `Executor` function rather than a subtypable port at all. Ask whether
   that is a sound simplification or a missing abstraction, given the charter
   defers all effectful tools for now.
2. **Decisions are recorded where they can be revisited.** The repo already has
   `docs/adr/0001` through `0008` with Context, Decision, Alternatives,
   Validation and revisit sections. Audit the code *against* the ADRs: does each
   still describe the system, and is each decision's validation clause actually
   testable. An accepted ADR that the code has quietly outgrown is a real finding,
   and the fix is to amend or supersede it, not to leave it lying. ADR 0002 even
   carries its own test, "never keep a decision call solely because the
   architecture diagram includes it". Apply it.
3. **Authority has exactly one owner, and it is Java.** ADR 0002 puts candidate
   feasibility, input validation, budgets and authority in Java, and says model
   confidence is not permission. The architectural form of that claim is that no
   decision-plane value can be *acted upon* without passing a Java-side check
   first. Verify by tracing whether any generation or tool path reads a decision
   result without an intervening check. A confidence value that reaches execution
   unexamined would be the most serious possible finding in this audit.
4. **Safety properties are structural, not documentary.** The gate ordering in
   `ToolLoop` is load-bearing, and today it is enforced by the order of calls plus
   test placement. Ask what would catch a future edit that reorders it. If the
   answer is "nothing", the fix is structural: make the pipeline type-level or
   state-machine-enforced so the illegal order does not compile.
5. **Degradation is a designed state, not an accident.** Every remote call can
   fail. For each, name the designed behaviour and check the code matches:
   fail-closed for a safety claim, fail-soft for ordering or convenience. A gate
   that fails soft because it was convenient rather than because it made no safety
   claim is a finding. The reranker's explicit fail-soft is the good pattern.
6. **One concept, one owner, one place to change it.** The `score` bug was a
   documented capability that was unreachable, which means a concept was
   specified in one place and implemented in another. Look for that shape
   generally: anything the spec, the schema, the docs and the code each describe
   separately is a place they can disagree. Four parallel descriptions of one
   thing is a defect waiting to be born.
7. **The configuration surface is a public API.** Config keys are validated
   against a known-key set, a JSON schema is generated and committed, and a test
   fails if the artifact is stale. That is unusually disciplined. Audit it for
   the reverse direction: is every documented key real, and does every key have a
   documented default? Silent defaults are where surprises live.

*General principles, applied last and only where they add something:*

8. **Cohesion before coupling.** Within a module, do the classes that change
   together live together. The module graph is already clean, so this is about
   the inside of `rahu-cli`, which holds four separate concerns (config, live
   turn, tools, wiring) in one module. Whether that is right is a judgement call
   and should be argued, not assumed.
9. **The error taxonomy is part of the interface.** `DecisionResult` is a sealed
   hierarchy of typed failures, and `DecisionQuestions` parses a hostile wire
   format into them. That is strong. Ask whether the same discipline holds at the
   other boundaries, particularly provider errors and filesystem errors, or
   whether those still collapse to strings or exceptions that callers match on.
10. **No behaviour without a test that would fail without it.** For each
    behaviour you rely on elsewhere in this document, name the test. If you
    cannot, that behaviour is folklore, and folklore is what breaks silently.

Two principles I would **not** apply here, so the audit does not waste its time:
"keep functions short" (the largest file is 454 lines and none of the smells
point at a long function), and general DRY pressure (the duplication that matters
in this codebase is duplicated *decisions* about safety, and a shared helper for
those would be worse, not better). Prefer the specific finding over the slogan.

**Verify this baseline before you judge anything. It was measured at `0a6f84f`, and
these are the facts, not opinions:**

- `rahu-core` (2860 main LOC, 54 files) depends on **JUnit and nothing else**.
  `rahu-openrouter` (489) and `rahu-systemone` (527) each depend only on core.
  `rahu-cli` (3987) sits on top. The dependency arrows point inward, which is
  the shape hexagonal architecture wants, so verify it holds rather than assuming
  it.
- core does **not** import okhttp, retrofit or Jackson. The `openrouter.md` and
  `openrouter` strings in `rahu/core/model/*.java` are Javadoc references to a
  spec document, not vendor types. Do not "fix" these.
- No god files. The largest is `ConfigLoader.java` at 454 lines, then
  `LiveTurnDriver` (338) and `ToolLoop` (326). If you propose splitting one,
  justify it against that number, not against a general dislike of long files.
- No `System.out` or `printStackTrace` in main code, no `Thread.sleep`, no
  static mutable state in core.
- Outbound calls carry explicit timeouts (`OpenRouterProvider` 5s connect /
  120s request, `SystemOneHttpAdapter` 3s connect, per-call read).
- Test-to-main ratio is roughly 0.8 across all four modules, 265 tests.

**Where to actually look.** The baseline is healthy, so the interesting findings
will be in the seams, not the files. Each area below is a *lens*; the principles
above are the criteria to judge it by, so do not treat the two lists as
independent to-do items. Spend the audit here, in this order:

1. **The seams between decision plane and generation plane.** This is the
   project's thesis, so it is where an architectural fault would be most
   expensive. `LiveTurnDriver` and `ActiveRouter` decide and execute; a leak
   between them would let a decision-plane concern contaminate generation, or
   worse, let a safety judgement silently become advisory. Trace one turn end to
   end and name every boundary it crosses.
2. **The gate ordering in `ToolLoop`.** Privacy gate, then injection gate, then
   rerank, then delivery. This order is a safety property, not an implementation
   detail. The reranker's "reorders only, never changes membership" invariant
   (section 7) depends on running last. Ask what happens if a future edit moves
   the reranker earlier, and whether anything would catch it. A safety property
   that lives only in a comment and a test's placement is one refactor from
   breaking.
3. **Failure typing as a discipline.** The `score` bug (section 6) was four
   silent traps that each degraded alone to a typed `Failure`. That is a good
   pattern. Ask where else a malformed or surprising input could degrade
   *silently* instead of to a typed failure. Silent degradation is the single
   defect class most likely to reach a user unnoticed, and this codebase has
   already been bitten by it once.
4. **Extensibility at the decision-port seam.** If a second decision provider, a
   second tool set or a new question type arrived tomorrow, how many files would
   change? The `ScoreQuestion` fix is the natural experiment: it was documented
   but unreachable, which suggests the port's extension point is not
   self-evidently discoverable. Judge whether adding a question type is a one-file
   change plus a test, or a hunt.
5. **Testability as a property, not a count.** The ratio is strong. The sharper
   question is what the tests would catch: do they assert behaviour contracts or
   do they restate the implementation? A test that would still pass after an
   inversion of the logic it covers is decoration. Section 10 lists three real
   cases of a passing test that checked nothing.
6. **Resilience and performance.** Both outbound planes are remote and both
   matter on a latency budget. The known cost is two dispatches per turn when
   rerank and injection both run. Ask what a partial decision-plane outage does
   to a turn, and whether each failure mode fails soft or fails closed
   *deliberately* rather than by accident. Note that "deliberately" is the whole
   question: a gate that fails soft for convenience is a defect.
7. **Maintainability as the thing that matters most.** `ConfigLoader` at 454
   lines with a known-key set, a binding step and validation is at the edge of
   where a reviewer stops holding it in their head. Judge whether it wants
   splitting on a seam, and if so name the seam.

**How to conduct it.** Read the code before forming a view; the instinct to
pattern-match "agent framework" and assume what such projects get wrong has cost
this project a false blocker already. Prefer measured claims to stylistic ones.
Where you can measure something, measure it: LOC, dependency directions, coupling
between modules, count of concrete implementations per port, branch coverage of
the gate ordering. A finding you cannot point a line number at is an opinion, and
label it as one. Equally important: **an audit that finds nothing is a failed
audit.** If the architecture genuinely holds up in an area, say so plainly and
move on. Manufacturing findings to look thorough is a worse outcome than a short
honest list, and it wastes the next session's time on phantoms.

Then report the audit as a ranked list: what to fix, what to leave, and what
cannot be judged until the live evidence in section 4 lands. Report the refactors
separately, each with the proof it carries: the test that was red before, or the
before-and-after suite counts for a behaviour-preserving move. The most valuable
outcome may turn out to be a short list, and "the architecture already holds up
here, and here is the evidence" is a legitimate result to report.

**N2 — Correct the roadmap rows, and produce `docs/reviews/dogfood-release.md`.** `docs/roadmap.md` still shows M1 and M2 as
"Planned" while the code is built and green. Bring it in line with reality, or
record explicitly why the row is gated on G09. Do not quietly flip a gate to
look complete; state the evidence level, per the charter's vocabulary rules.

**N3 — Close S12's remaining half.** The release-wide critique (G10) and the
requirement-to-test evidence manifest, written to `docs/reviews/dogfood-release.md`
as `docs/release-gates.md:39` requires (that file does not exist yet), with G01–G10
status, acceptance IDs mapped to actual tests, commands, versions and costs. The suites already exist and are ready:

- `docs/evals/suites/dogfood-alpha-v1.json` — 3 tasks: `repository-followup`,
  `compaction-synthetic`, `authority-explanation`. This is the G09 suite, and
  `compaction-synthetic` is the one that can produce the real compaction-policy
  and summary-route evidence G09 requires.
- `docs/evals/suites/smoke-v1.json` — 6 tasks, the smaller plumbing seed, still
  useful for `untrusted-tool-instruction` and `summary-fidelity`.

**Live eval does not exist yet. This is missing code, not a missing credential.**
`EvalCommand.java:54-62` refuses twice: `config.local.json` is `"mode": "live"`, so
it returns exit 3 on the first branch ("live execution requires an explicit
budget"), and `--live`/`--max-cost-usd` returns exit 3 on the second ("live
execution needs a verified System One service, not available in this build").
`runOfflineTask` is the only task path and stamps the literal
`"offline answer for: ..."` with a `decisions++` counter standing in for a
dispatch. So the intended run shape is **N2a**, a real implementation:

```bash
./bin/rahu eval --suite docs/evals/suites/dogfood-alpha-v1.json --config \
  config.local.json --live --max-cost-usd AMOUNT --report PATH
```

N2a must build: real generation dispatch per task, real decision capture against
the decision engine, and one aggregate ledger across all three tasks (not the
per-turn reservation the chat path uses). **N2b** is then running that command.
Sequential, under one aggregate allowance, invoked via the packaged `bin/rahu`
rather than `mvnw` so the packaged launcher is verified too.

`docs/release-gates.md:33` and `:35` are specific about what counts: at least
one validated classification, relevance and route, plus real
compaction-policy/summary-route evidence across the bounded runs. **A run where
every decision times out into fallback does not establish compatibility**, and
neither does a health response. Do not force the decision model to pick a
particular route in order to call the test successful; real quality is a rubric
judgement. Structure the run to capture those decisions rather than leaving them
to chance.

Note: `--max-cost-usd AMOUNT` is the **aggregate run allowance** the operator
sets. It is not `agent.maxCostUsd` (0.10) or `session.maxCostUsd` (0.50) in
`config.local.json`: `agent.maxCostUsd` is the **per-dispatched-run** cap reserved
before dispatch, and `session.maxCostUsd` is the allowance it is reserved FROM
(`build-status.md:82`) — two levels of a hierarchy, neither of which is the
aggregate run allowance. All
phases share one experiment ledger; a new child session cannot reset the ceiling.
If the allowance will not fit, reduce task and output limits. Do not enlarge the
allowance, change the pool, or disable required effort enforcement to force a
run.

**N4 — Measure the rerank in shadow.** Needs operator approvals 2 and 3 from
section 4 first. A shadow run **cannot** measure whether the ordering helped:
`SearchReranker.java:149-152` returns filesystem order in SHADOW and carries the
proposed order only in `Result.proposed()`, so what shadow gives you is the score
distribution and the order delta, not a quality verdict. Measuring effect on
answer quality requires `enforce` under an approved bounded run. Start with
`search.mode: shadow` (never `enforce` first), run bounded
repository Q&A, and record whether the ordering actually helped. This is a
measurement, and it may well say the rerank is not worth its dispatch. Record that
honestly if so. If the operator prefers a smaller step, run a single-turn
measurement under a small ceiling instead of the full suite.

**N5 — Optionally re-score the injection corpus** via the container batch
(`scripts/run-injection-corpus.sh`). The script refuses a missing config, refuses
a config containing a literal credential, and requires the corpus fixture. **Two
refusals it does not mention: `CONFIG` defaults to `config.corpus.local.json`,
which does not exist here (only `config.local.json` does), so export
`RAHU_CORPUS_CONFIG=config.local.json` or create that untracked file; and it exits
1 unless `OPENROUTER_API_KEY` is exported in the calling shell — which the standing
rule below tells you to unset. Export it for the containerised run only.** Note
the batch-composition caveat in section 5: re-scoring changes the batch, so
compare with care.

**N6 — build phase G3 stays deferred.** (Build phases P1–P3 are distinct from release gates G01–G10; `release-gates.md:11` lists G03 as Required, so do not read this as deferring a release gate.) Do not start it. `DecisionResult.ValidScore` and the
rerank show the decision plane is still the active surface.

**N7 — Explicitly do NOT start these.** The charter (§4) defers them, and a
continuation session reading only this file would not know. Out of scope: streaming,
effectful and shell tools, OS sandbox claims, durable resume and fork, vector
memory, runtime third-party plugins/hooks/MCP, native model inference, training,
contextual-bandit exploration, dashboards, multi-agent delegation. If any looks
attractive, that is the signal to note it and move on, not to build it.

**N8 — Consider the deferred v2 vocabulary question** only if a real consumer
appears. `docs/research/decision-plane-opportunities.md` records that
design/architecture were proposed and deliberately kept out of v1, with reasons
and a revisit trigger. `TaskClass.fromLabel` maps unrecognised labels to UNKNOWN,
so adding labels later is backward-compatible. Do not add them without a consumer
that would route those prompts differently, with measured misclassification
evidence on Rahu's own tasks.

## 9 Environment and mechanical notes

- `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`, then
  `./mvnw verify`. Java 27, preview features on, and preview must stay enabled
  consistently across compile, test JVM, launcher, packaged run and CI.
- **`unset OPENROUTER_API_KEY` before offline `./mvnw verify`.** `DotEnvTest` and
  `LiveWiringTest` read the real environment and fail when it is exported. This
  cost real time once already.
- Regenerate the committed schema after a config key changes:
  `./mvnw -pl rahu-cli exec:java -Dexec.mainClass=rahu.cli.SchemaGenerator`.
  A test fails if the artifact is stale.
- Untracked and gitignored, never commit: `.env`, `config.local.json`,
  `docs/generated/` (except the force-added `config.schema.json`), `.rahu/`,
  `bin/` (except the force-added `bin/rahu`).
- Verify the **packaged** launcher, not just `mvnw verify`.

## 10 Lessons to carry, each one paid for

- **Read the existing test's setup before writing a new one.** Three fixtures I
  wrote contradicted the design they were testing: levels scored outside the
  legend; a `directory` argument the schema rejects (`additionalProperties:
  false`); and a `String.replace` anchored on a key the test config does not
  contain, so that test passed while checking nothing.
- **A green test count is not a critique, and a green offline suite does not
  prove a live claim.** The charter says this; the false blocker in section 2 is a
  live example of the failure mode.
- **Assert the output, not the call order.** Where section 7 says the invariant is
  asserted end to end, that is deliberate: the call-order version would be an
  assumption.
- **Absence of a probe result is not absence of the capability.** See section 2.
- **Every extra dispatch has a price.** State the cost in the class, at the time
  you accept it, rather than discovering it later.
- **Strategic over brittle, proven over asserted.** When a design is refactored,
  the evidence is part of the change, not a summary of it. Prove the extension
  point works the way a *second* implementation would use it, because a seam
  exercised only by its first implementation has not been shown to be a seam at
  all. And prefer the design the next feature lands on over the tidiest diff
  today.
- **Rebase onto current `main` before every merge.** The repo's three merges are
  all merge commits (`20e2282`, `754c024`, `1ab8b09`), not squashes — no squash
  incident is recorded in history — but a squash from a stale branch would still
  silently revert fixes, so keep the ritual and verify each merge with
  `git diff HEAD~1..HEAD`.
- Use sub-agents only if separately authorised (charter, section 6).
- Do not use `gh pr merge --squash` here. Preserve contributor credit by merging
  with a merge commit, or cherry-pick salvaged work so authorship survives.

## 11 Reporting rules for this session

- Use the **Google Developer Style Guide** for all user-facing prose. Second
  person, active voice, front-load the outcome, one idea per sentence, no Latin
  shorthand, serial comma, no exclamation points, no "please". Contractions are
  fine. This is a standing preference, saved in the `plain-english-reporting`
  skill.
- Markdown does not render in this terminal, so the style guide governs sentence
  structure, not appearance. Use short lines, blank lines and indentation for
  layout. **Do not emit bold, heading or fence markers expecting them to render.**
- Keep the charter's evidence vocabulary exactly: **offline complete**, **live
  dogfood verified**, **routing optimisation validated**. Shadow is the default
  until M3 promotion supports a change. Do not imply a passed smoke proves cost
  savings or a passed detector guarantees all PII was found.
- Never report a gate passed from prose. Cite the command output, the evidence
  path, or state the uncertainty explicitly.
- Include no PII, secrets, private payloads or raw reasoning in any handoff.
- Update `docs/plans/active/build-status.md` at each boundary: checked commit,
  slice status, successful commands, failed checks, review links, real blockers,
  and the next work.

## 12 Begin

Read `prompt.md` for the charter and `docs/plans/active/build-status.md` for the
detailed history. Verify the facts in section 1 against the repository rather
than trusting them. Then start at **N1**: the architecture and design audit. Adopt the
architect and craftsman framing in that item before you read the first file. Audit
first, then refactor what the audit justified, and hold to its two rules:
strategic over brittle, proven over asserted.

When you report back, state plainly what you did, what you measured, what remains
blocked and on whom, and any place where the evidence contradicts this file.
