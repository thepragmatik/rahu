# Audit register — all findings, one place

Date: 2026-10-03. This file consolidates five separate audits into a single
ledger. It exists because the results were scattered across five documents and
the operator could not tell what was done, what was left, or what was next.

## Sources, and how to read them

| # | Document | Scope | HEAD at audit |
|---|---|---|---|
| 015 | `015-architecture-audit.md` | Whole-codebase architecture and design (N1) | `7ae0e48` |
| 016 | `016-failure-typing-audit.md` | Failure typing at every boundary; decision-port extensibility (N2) | `5d81b85` |
| 017 | `017-three-audit-findings.md` | Three adversarial read-only audits + the audit of the audits | `dcfc0e1` |
| 018 | `018-jdtls-adoption.md` | jdtls snapshot, client state, cost baseline | `57fdc6b` |
| — | `dogfood-release.md` | G01–G10 gate evidence; live G09 smoke | `c4cb6b7` |

**Rule applied to every finding:** a finding is real only if the cited file was
read at HEAD by me. A line number in a report is a claim, not evidence. Four
incoming claims were stale or misleading and are listed as such below.

---

## The single pattern behind most findings

Three of the five architecture findings, and a repeated class across all
audits, are the same defect:

> **A capability that was specified or configured, but which the code did not
> actually implement.**

Instances found: `DecisionResult.ValidScore` (declared in `systemone.md`,
unreachable in the adapter), `tools.enabled`, `tools.exclusions`,
`tools.resultBytes`, `routing.confidenceField`, `decision.confidenceSemantics`,
`OperationRequirements.isTextAnswer` / `.structuredOutputRequired`,
`NoProgressDetector` (implemented, tested, never called from `main`).

The accepted-key test catches a config key that lands unnoticed. **Nothing
catches a key that lands without effect.** That is the structural gap and it is
the highest-value follow-up in this repository.

A second, related pattern: **a test that passes without power.** `ToolLoopRerankTest`
claimed to prove the rerank cannot resurface withheld text. Its fixture put the
search term on line 1 and the poison on line 2, so `workspace.search` never
matched and the assertion could not fail. Same root cause as the
`ConfigLoaderTest` fixtures that always listed all three tools, which is exactly
why `tools.enabled` being ignored was invisible. Now automated as check 2 of
`scripts/verify-true.sh`.

---

## Closed — fixed with proof

Every row below had a red test against the old code, then green.

| ID | Sev | Finding | Fix commit |
|---|---|---|---|
| F-1 | HIGH | A withheld search observation was re-delivered verbatim to the reranker — the injection gate's own verdict discarded one line later. Unreachable in shadow mode, which is why the earlier `WOULD_WITHHOLD` probe looked safe. | `4067ea0` |
| F-2 | MED | The tool-relevance judgment was computed, paid for, printed — then discarded. The model received the full tool set. `tools.md` requires the narrowed set. | `ada86eb` |
| F-3 | HIGH | The loopback plaintext exemption was a `startsWith` match, so `http://localhost.evil.example/api` and `http://user:pass@evil.example` were treated as loopback and exempt from encryption. | `4002166` |
| F-4 | MED | `tools.enabled` and `tools.exclusions` were parsed, schema-documented, then read by no production path. | `990647f` |
| F-5 | MED | Directory exclusions matched case-sensitively while the name list two lines above did not. `.GIT/config` was readable where `.git/config` was blocked. | `990647f` |
| L-1 | HIGH | `tryReserve` used `max(reserved, uncertain)` instead of adding both, so the smaller bucket vanished from admission. **My own regression.** My test was blind to it because it used equal buckets, where max and add agree. | `58c63d7` |
| L-2 | HIGH | A failed generation never settled its pre-dispatch reservation. Leaked in `open` forever — and a read timeout is exactly the "probably billed" case. | `58c63d7` |
| L-3 | HIGH | `Ledger.settle(..., boolean successful)` never read the boolean; a failure booked a confident settled charge. | `58c63d7` |
| L-4 | HIGH | `remaining()` threw on overshoot, crashing `/status` — the one command an operator runs when money is wrong. | `58c63d7` |
| L-5 | HIGH | `ToolLoop.observationOf` read `content` (always `""`) instead of `safeReason`, so a safety refusal reached the model as a bare `invalid: ` with no reason. | `58c63d7` |
| L-6 | MED | `WorkspaceTools.read` returned SUCCESS+empty for inverted or out-of-range ranges — byte-identical to an empty file. | `58c63d7` |
| E-1 | HIGH | Three raw exception types escaped the tool boundary. | `cdef376` |
| E-2 | HIGH | An empty decision body threw NPE out of `askAll`. | `5d81b85` |
| E-3 | HIGH | The loopback rule was **duplicated**, so the F-3 fix was incomplete on one path. | `5d81b85` |
| E-4 | MED | NaN passed both `[0,1]` confidence guards — a NaN comparison is always false. | `5d81b85` |
| N-1 | HIGH | `NoProgressDetector` was unreachable from `main`. The loop was stopped only by `maxCallsPerStep`. Wired, with a `NO_PROGRESS` failure kind. | `6794220` |
| N-2 | — | The detector fingerprinted a whole batch, so a stuck call inside a varying batch could never match itself. Fixed per-call. | `6794220` |

---

## Open — verified real, deliberately not fixed

Each is real and confirmed. None is a quick fix; each needs its own proof.

| ID | Sev | Finding | Why deferred |
|---|---|---|---|
| F-6 | LOW | Two sources of truth for `routing.confidenceFloor` (`ConfigLoader` 0.65, `ActiveRouter` 0.0). Unreachable today; a trap for a hand-built config. | Choosing the authoritative number is an operator decision. |
| ~~F-7~~ | ~~MED~~ | **FIXED** — `Question` **is** `sealed` (`DecisionEngine.java:116`, `permits ChoiceQuestion, NoulQuestion, ScoreQuestion`). This row was stale for as long as F-9 and F-11 and claimed otherwise; found only by re-reading the table against source (AUDIT-r). | — |
| F-8 | MED | All transport failures collapse to `TIMEOUT`. A read timeout (ambiguous) is indistinguishable from a connect failure (definite). The ledger handles the ambiguity correctly via `markUncertain`, so cost is sound and only the diagnostic is coarse. | Would need a new `FailureKind`. |
| ~~F-9~~ | ~~MED~~ | **FIXED** (AUDIT-p, `0e130e9`) — `tools.resultBytes` now bounds read, list and search, and non-positive caps are refused at load. Was listed here while already fixed. | — |
| ~~F-11~~ | ~~HIGH~~ | **FIXED** (AUDIT-e) — `RouteResolver` reads it via `confidenceOf(c, in.confidenceField())`. Was listed here while already fixed. | — |
| ~~C-1~~ | — | **FIXED** (AUDIT-u) — see below. The *rounds to a settled zero* half was right. The *"destroys the exact-decimal guarantee"* rationale in this row and in review 017 was **wrong** and is retracted: `asDouble()` then `Math.round` agreed with exact decimal on 400k sampled inputs. | — |
| ~~C-2~~ | ~~MED~~ | **FIXED** (AUDIT-q) — fails closed on a typo'd, null or absent label; DEFER now only from an explicit label that passed the fit check. Was listed here while spec-mandated (`systemone.md:40`). | — |
| ~~C-3~~ | — | **FIXED** (`9bb0078`, AUDIT-2026-10-03-r) — the estimate is now an unclamped measurement; the bound is applied at each port that requires one. Row left struck rather than deleted so the audit trail stays. | — |
| ~~row 91~~ | — | **CORRECTED (AUDIT-s)** — this row was wrong twice. It said "five" while naming four, and one of the four (`routing.confidenceField`) is the key the table's own F-11 claims is fixed. Of the remainder: `decision.confidenceSemantics` is now **refused at load** (it was never a real key); `OperationRequirements.isTextAnswer` / `.structuredOutputRequired` are still inert but are future scaffolding, not inert config — see the AUDIT-l sweep. | — |
| G07 gap | MED | No third-party compiled extension example exists. The registry is wired and tested. | Needs an out-of-tree module built and run. |
| A25–A32 | MED | Eight acceptance IDs rest on slice reviews rather than one test each. This is the last row blocking the M1 offline declaration. | Eight small named tests. |

---

## What holds up, with evidence

An audit that finds nothing is a failed audit, and so is one that cannot say what
is sound.

- **The decision/generation seam is clean, and it is the project's thesis.** Every
  decision-plane value passes a Java-side check before it is acted on. No confidence
  value reaches execution unexamined. That was the most serious possible finding and
  it does not exist.
- **The error taxonomy at the decision boundary is the strongest thing in the
  codebase.** `DecisionResult` is a sealed hierarchy; four score-parser traps each
  degrade **alone** to a typed `Failure` without collapsing the batch.
- **The filesystem boundary is genuinely good** — `PathBoundary` is the strongest
  code in the repo. A subagent attacked the argument parser with an escaped-quote
  needle and could not make it misparse. No finding.
- **The cost gate genuinely is pre-dispatch.** It reserves and returns exit 3 before
  the loop runs, and `CostGateTest` asserts on whether the provider was billed, not on
  call ordering.
- **Charter clauses all hold:** no JSON/HTTP out of core; decisions cannot grant
  permissions on any live path; fallbacks cannot widen the pool; an impossible
  model/effort pair is unrepresentable. Concurrency is clean and vacuously so — zero
  thread spawns in four modules.
- **Interruption preservation is clean.** Every blocking call re-sets the interrupt
  flag before returning and maps it to a distinct failure kind.

---

## The audits audited

`017-three-audit-findings.md` exists because two of three incoming reports
contained claims already fixed when they arrived. A review process that publishes
only its hits cannot itself be reviewed.

| Claim | Verdict |
|---|---|
| Loopback prefix is a live fail-open | **Stale** — fixed at `4002166`; report read pre-fix code |
| Rerank test passes vacuously | **Stale** — fixed at `4067ea0`; reviewer caught itself reading a pre-fix file |
| `DecisionResult` is sealed | **Misleading** — true, but no `permits` and every consumer uses `instanceof`, so nothing is enforced |
| Ledger defects | **Fixed mid-audit** — `dcfc0e1` landed while the audits ran |
| Empty 200 body NPE | Confirmed, fixed |
| NUL byte escapes as `InvalidPathException` | Confirmed, fixed |
| NaN passes `[0,1]` guards | Confirmed, fixed |

**Precedent worth preserving:** a regression test written against equal buckets
cannot detect a `max`-for-`add` bug. The assertion had to be rebuilt with
deliberately unequal values to see it. Whenever a fix's test seems too
comfortable, that is the reason.

---

## The one structural recommendation

Three of five architecture findings were "specified but not implemented", and the
accepted-key test cannot catch that class. The recommendation is therefore:

**Sweep for inert configuration keys, and add a test that fails when a key is
accepted but never read.**

Mechanically: for every field bound in `ConfigLoader`, assert at least one
production reader exists. This is check 1 of `scripts/verify-true.sh` generalised
from guard classes to config fields. It closes F-9, F-11, the five remaining
accepted-and-ignored fields, and prevents the class from recurring.

---

## jdtls / M2.5 — built, deliberately not adopted

| Question | Answer |
|---|---|
| Is the server capable? | Yes. 114 plugins including the LTK refactoring engine. No capability gap. |
| Is there a client? | **No.** Nothing in this repo drives it. |
| Why no CLI? | `jdtls` is a stdio JSON-RPC server with no query subcommand. It waits for `initialize` and blocks. Correct behaviour, not a defect. |
| Which build? | The **snapshot**, not Homebrew 1.61.0 — roughly seven weeks newer, and the one Hermes itself is configured to use. Never a hardcoded Homebrew path. |
| Cost-effectiveness? | **Not proven.** `LspSessionLiveTest` 5/5 green, but no `results/`, trace JSONL or ledger exists, so the baseline has no source. |

`rahu-core` is JDK-only by architecture, so the client needs a small local JSON
encoder with no new dependency.

---

## AUDIT-2026-10-03-a — G06 is recorded as PASSED, but no live turn writes a trace

Found while building the inert-key detector. Severity: **HIGH**. Status: **OPEN**.
This supersedes the G06 row in `dogfood-release.md`.

### The claim

`dogfood-release.md` line 54 records:

> | G06 Traces/replay | PASSED | `TraceWriterTest`, `ReplayEngineTest`; A11, A16 |

### The evidence against it

The detector reads every `RahuConfig` record component and looks for a production
accessor call. `trace.directory`, `trace.capture` and `trace.onFailure` have zero.
That led to a larger question, and the answer is worse than an inert key.

1. **Nothing in `main` constructs a `TraceWriter`.** `grep -rn "new TraceWriter"
   --include=*.java */src/main/java` returns nothing. The only constructor is its own
   definition at `rahu-cli/.../trace/TraceWriter.java:28`.
2. **Nothing in `main` references `TraceWriter`, `TraceReader`, `ReplayEngine` or
   `TraceEvent`** outside the `rahu.cli.trace` package itself. The subsystem is
   self-contained and unreachable.
3. **`LiveTurnDriver` contains the string "trace" zero times** (case-sensitive count
   over the whole file).
4. **A real turn writes no trace.** Ran `rahu chat` with
   `examples/offline.json`, stdin `what is this project`, then `/exit`. Exit code 0,
   a real answer printed. File count under `.rahu/runs` was **4 before and 4 after**.
5. **The only traces on disk are demo-shaped and synthetic.** All four existing
   `events.jsonl` files carry `"configHash":"synthetic"` and
   `"catalogHash":"synthetic"`, and all four have mtimes of Oct 1-2 — none from any
   chat turn.

`DemoCommand` does write a trace, but it does not use the subsystem. It hand-builds
a `StringBuilder` of four hardcoded events with fixed values and calls
`Files.writeString`. It also hardcodes `.rahu/runs/<uuid>` rather than reading
`trace.directory`, which is why `trace.directory` is inert even for the one command
that produces a trace.

### Why this is worse than the inert-key class

`tools.enabled` was a documented key with no effect. This is the whole G06
deliverable with no effect. The gate policy says "trace failure cannot become
success", and the recorded status is PASSED on the strength of two unit tests
whose subject production code never calls.

This is the **sixth** instance of the same root cause, and the largest so far:
`ValidScore`, `tools.enabled`, `tools.exclusions`, `tools.resultBytes`,
`confidenceField`, and now `TraceWriter`.

### Correcting the record

G06 must read **BUILT, NOT WIRED** until a live turn demonstrably writes a trace and
`ReplayEngine` reads one back. `TraceWriterTest` and `ReplayEngineTest` remain true
statements about the subsystem; they are simply not evidence about the product.

I have not yet fixed this. The fix is a real piece of work: construct a `TraceWriter`
from `trace.directory` in `LiveTurnDriver`, emit `RunStarted` / `RouteResolved` /
`ModelCompleted` / `RunTerminated` from the real values the driver already holds,
honour `trace.capture` and `trace.onFailure`, and make `DemoCommand` use the
subsystem instead of its hand-built string. It needs its own red-then-green
proof, and the proof must be a **turn that writes a file**, not a unit test.

### Process note

The inert-key detector found this as a side effect, not as a target. That is the
argument for building the detector rather than fixing the known list: the known list
had twelve entries and did not include this.


---

## AUDIT-2026-10-03-a — PARTIAL FIX (live path wired; offline path still dead)

Commit in flight. Status: **PARTIALLY REMEDIATED** — do not record G06 as PASSED.

### What is now fixed

`LiveTurnDriver` owns a `RunTracer` per turn. Five real events, verified by
reading an actual trace file produced by a real driver run:

```
1 RunStarted      {"sessionId":"sample","turnIndex":0,
                   "configHash":"6fbed0d5...","catalogHash":"ba4b3163..."}
2 RouteResolved   {"suggested":null,"executed":"fast@low","mode":"SHADOW",
                   "degraded":true,"fallbackCause":"decision-missing",
                   "excludedCandidates":2}
3 ModelRequested  {"requestedModel":"demo-fast","maxCompletionTokens":4096}
4 ModelCompleted  {"requestedModel":"demo-fast","finishReason":"stop",
                   "usage":{"promptTokens":11,"completionTokens":5,"costMicros":500}}
5 RunTerminated   {"reason":"ANSWER_COMPLETE","generationSteps":1,"elapsedMs":32}
```

Compare the four pre-existing traces, every one of which carries
`"configHash":"synthetic"` and `"catalogHash":"synthetic"`. The token counts
(11/5) are the provider's real reported usage, not a placeholder.

`trace.directory` is now honoured: the trace lands in the configured directory,
one subdirectory per run id. I proved this by mutating the wiring to hard-code
`.rahu/runs`; four of the five tests went red, so the test has real power rather
than merely passing.

Privacy checked, not assumed: the produced trace contains no prompt text, no
tool arguments and no reasoning. `grep` for the actual user prompt against the
real trace returns 0 matches. Payloads carry hashes and counters only.

Refusals are traced too. A privacy-blocked input returns before a run handle
exists, so it is recorded under a `refused-<uuid>` id with `RunRefused` then
`RunTerminated`, and the category only — never the offending data (privacy.md).
A refusal with no record cannot be told apart from a turn that never ran.

A16: `TraceFailureException` propagates. A broken sink stops the turn (exit 5)
rather than producing a partial trace readable as complete.

### What is NOT fixed — and must not be claimed as fixed

**`ChatCommand.runOffline` writes no trace.** Verified by running the built
binary against `examples/offline.json` with `trace.directory` pointed at a temp
dir: 0 files written.

`runOffline` is a separate loop that never constructs a `LiveTurnDriver`. It has
its own `session.beginTurn()` / `turn.complete()` and it calls no tracer. So
half the chat entry points still produce no observability at all.

`DemoCommand` also still hand-builds a hardcoded four-event trace instead of
using `RunTracer`, though it now does write something.

### Gate status

G06 remains **BUILT, NOT WIRED — PARTIALLY**. It is honest to say the live path
is wired and proven. It is not honest to say the gate passes, because the
operator's default offline configuration still produces no trace, and an
operator dogfooding offline would see an empty observability story.

Also outstanding for a true G06: `trace.capture` and `trace.onFailure` are still
parsed-but-unread. The wiring honours `onFailure=stop` by construction (the
exception propagates), but that is code behaviour, not config reading. A
`capture=full` request is still silently ignored. That is the same inert-key
shape the audit exists to catch, and it should be the next increment.

### Evidence

`RunTraceWiringTest` — 5 tests, each driving a real turn through the real driver
and asserting on the file that appeared. Not one of them constructs a
`TraceWriter` directly, because that is the shape of test that passed against
the broken state.


---

## AUDIT-2026-10-03-b — OFFLINE PATH: privacy gap + missing trace (both fixed)

Commit in flight. Follows AUDIT-2026-10-03-a. Found by reading `runOffline`
rather than trusting the gate record, then confirmed against a packaged build.

### Correction to finding (a)

I described the offline loop as duplicated driver logic and offered to collapse
the duplication. That was wrong. `runOffline` calls no model, no tools and no
router; there is no duplication to collapse. Recorded because the wrong framing
would have led to a much larger and less justified change.

### Finding 1 — the offline path had no privacy gate (severity: high)

`runOffline` admitted input with no `PrivacyGate` call at all, then echoed the
raw input back to stdout. Verified, not inferred:

```
$ printf 'my email is bob@example.com and my card is 4111111111111111\n' \
    | ./bin/rahu chat --config=offline.json
offline: composed a bounded read-only answer for "my email is bob@example.com
and my card is 4111111111111111". (No model was called; fake provider path.)
exit 0
```

Both values printed verbatim; exit 0. The live loop refuses the same input.

Why this mattered more than a missing feature: **offline is the DEFAULT mode**
(configuration.md) and privacy defaults to `strict` + `block`, so the least
protected path in the product was the one a new user meets first. cli.md is
explicit — "safe diagnostics and provenance checks apply even when no model call
is planned". There was no privacy check anywhere on that path.

### Finding 2 — the offline path wrote no trace

Same run: `trace.directory` was honoured nowhere; 0 files. product.md makes a
complete route/tool/termination trace the FIRST thing a new user sees, offline,
specifically so the concepts are learnable without API keys. That first-run
experience was unreachable under the default configuration, which is the exact
opposite of what the spec intends offline mode to be for.

### What the fix does NOT do

It does not invent events. Offline mode resolves no route and calls no model, so
the trace records only `RunStarted` + `RunTerminated`, and the refusal path
`RunStarted` + `RunRefused` + `RunTerminated`. No synthetic `RouteResolved`, no
synthetic `ModelRequested` — writing those would reproduce exactly the
`"synthetic"` hashes this audit used to discredit the four demo traces.

Verified on the packaged binary: 1 trace per turn, real 64-hex config hash,
`RunTerminated.reason=ANSWER_COMPLETE`, refusal on a `refused-` id with
`PRIVACY_BLOCKED`, exit 4 blocked / 0 admitted, and **zero** PII in any trace.

### Shared, not copied

`TurnTrace` now owns the trace root plus the config and catalog hashes, and both
loops call it. Copying the live pattern into the offline loop would have created
two implementations that start identical and drift on the first change — which is
how one fix becomes two half-fixes. `RunStarted` is emitted once, in
`TurnTrace.begin`.

### The test's own gap, found by mutation testing

I re-introduced the input echo as a mutation and **all tests still passed**. The
test covered the refused path's stdout but never the admitted path's, so half the
defect had no coverage. Added
`admittedTurnDoesNotEchoInput` with the reasoning recorded in the test.

This is the second time in this audit that a green suite turned out to have no
power over the defect it was written for. Mutation testing is not optional here.

Full mutation battery, every one now producing failures:

| Mutation | Result |
|---|---|
| privacy gate bypassed | 2 failures |
| input echoed again | 1 failure (was 0 before the test fix) |
| terminal event removed | 1 failure |
| refusal not traced | 1 failure |
| trace.directory ignored | 3 failures |

One mutation is deliberately absent: no sink-failure test. A16 stop-on-broken-sink
for the offline loop is implemented but not covered by a test; it is the next
increment rather than a claim made now.

### Finding 3 — the README quickstart could not work (fixed, docs only)

Step 4 said `./bin/rahu chat --config config.local.json`. With the shipped
defaults (`inputClassification: unknown`, strict, block) that refuses **every**
prompt with `privacy blocked (unknown-provenance)` and exit 4 — clean prompts
included. A reader following the quickstart literally would conclude the product
was broken. The step now names the flag and explains why, including that it is an
assessment and not a bypass.

This is a pre-existing documentation defect, present before either of my
changes. It became visible only because the offline loop now gates privacy.

### Gate status

G06 stays **PARTIAL**. Closed in this increment: the offline path now traces and
gates. Still open: `DemoCommand` hand-builds a hardcoded trace instead of using
`RunTracer`; `trace.capture` and `trace.onFailure` are still parsed-but-unread,
so `capture=full` remains silently ignored — the same inert-key shape this audit
exists to catch.


---

## AUDIT-2026-10-03-c — A16 sink failure was implemented but unproven (now covered)

Follows AUDIT-2026-10-03-b. No behaviour change to production code; this
increment closes the one gap I explicitly declined to claim in (b).

### What was already right

The offline loop's A16 handling was implemented when I wrote the driver: a
`TraceFailureException` stops the turn and returns 5, and a failed refusal trace
does not release the input. Reading it, both looked correct.

### What was missing

Nothing tested it. `TraceWriter` defers its open to the first append precisely so
callers learn about a broken sink at the A16 checkpoint, which means the failure
path is reachable in production and had zero coverage. An implemented-but-untested
safety path is the weakest kind of claim: it reads as evidence in a review and is
not.

### How the sink is broken in the test

By making the trace root's parent a regular FILE. `Files.createDirectories` then
fails inside `TraceWriter.writer()` and surfaces as `TraceFailureException`. No
mocking, no root, no special permissions — the same failure a full disk or a
revoked directory produces, reached through the real config loader and the real
driver.

### Mutation testing found a third gap in my own tests

First pass, two of four mutations died and two survived:

| Mutation | Result |
|---|---|
| sink failure swallowed, exit 0 | caught |
| reports failure but still exits 0 | caught |
| refusal failure reported nowhere | **SURVIVED** |
| refusal logged, loop continues (admits) | caught |

The surviving one was the real finding. `recordRefusal` returns `false` on a
sink failure and the driver prints a warning — but no test asserted the warning
exists, so it could be deleted silently. The consequence is subtle and worth
stating: the operator sees a clean privacy refusal on stderr and **no indication
that their trace archive now has a hole in it**. The refusal happened; the
record of it did not; nothing said so. Added the assertion; the mutation now
dies.

That is the third time in this audit a green suite turned out to lack power over
the defect it was written for. Three for three. The pattern is now consistent
enough to state as a rule rather than an anecdote — see the skill.

### One mutation that turned out to be benign, recorded so it is not re-litigated

Replacing `return 4` with `continue` on the refusal path *is* caught (2
failures), which is the assertion doing its job. But an intermediate mutation
that merely deleted the `if (!recordRefusal(...))` warning branch survived in the
first pass and is the same defect as the one above — noted because "it survived"
and "it is harmless" look identical from the outside and only the second reading
was correct.

### End-to-end confirmation on the packaged build

Trace root under a regular file, real CLI, no test harness:

```
$ printf 'summarise the readme\n' | ./bin/rahu chat --config=... \
      --input-classification=approved-nonsensitive
rahu chat (offline) — /status /reset /exit, EOF to end
trace write failed (A16); turn stopped
exit 5

$ printf 'my email is bob@example.com\n' | ./bin/rahu chat --config=...
rahu chat (offline) — /status /reset /exit, EOF to end
trace write failed while recording a refusal (A16); the input was still blocked
privacy blocked (unknown-provenance); nothing was sent. ...
exit 4
```

Note the first case prints NO answer. The turn is stopped before the answer is
emitted, which is the correct order: an answer presented for a run whose trace
never reached disk is the specific lie A16 exists to prevent.

### Gate status

G06 unchanged at PARTIAL. Closed here: A16 on the offline loop is now proven, not
just implemented. Still open, unchanged: `DemoCommand` hand-builds a hardcoded
trace instead of using `RunTracer`; `trace.capture` and `trace.onFailure` are
still parsed-but-unread, so `capture=full` remains silently ignored.


---

## AUDIT-2026-10-03-d — exit codes violated cli.md:42, and I had locked the bug in

Found while scoping the inert `trace.capture` key. No production change was
planned for this increment; the exit-code defect was not on my list and is the
most consequential thing found so far.

### The defect

cli.md:42 fixes the vocabulary:

> 0 complete answer/deterministic demo, 2 invalid input/configuration, **3 no
> feasible route/limit reached/privacy blocked**, **4 provider/decision/tool
> failure**, 5 trace/replay integrity failure, 130 interrupted.

Three privacy-block sites returned **4**:

| Site | Meaning | Was | Spec |
|---|---|---|---|
| `LiveTurnDriver` initial admission | privacy blocked | 4 | 3 |
| `LiveTurnDriver` decision dispatch | privacy blocked | 4 | 3 |
| `LiveTurnDriver` generation dispatch | privacy blocked | 4 | 3 |
| `OfflineTurnDriver` | privacy blocked | 4 | 3 |

Pre-existing, dating from the `LiveTurnDriver` extraction (2dc6fb1). My
AUDIT-2026-10-03-b increment copied the live driver's 4 into the offline driver.

### Why it matters beyond tidiness

3 and 4 are different claims to a caller. 3 means "nothing was sent and retrying
unchanged will not help" — the group cli.md deliberately puts privacy blocks,
routing terminals and limit exhaustion into, because a supervisor's correct
response to all three is *do not retry*. 4 means a provider, decision or tool
failed, whose correct response is often *retry or fail over*. A privacy refusal
reported as a provider failure invites exactly the wrong operational response, and
in a harness that retries on provider errors it invites re-sending input that was
refused precisely because it must not be sent.

### The part I got wrong

My own offline test asserted:

```java
assertEquals(4, result.exit(), "a privacy block uses exit 4, as the live driver does");
```

That message states the error precisely. I pinned the value to its **sibling
rather than to the spec**, so the new test certified the bug instead of catching
it. Two drivers agreeing with each other looks identical to two drivers being
right, and I wrote the test that would make that indistinguishable forever.

### The fix, and why constants rather than a patch

Added `ExitCode`, one class owning the vocabulary, and routed every one of the
14 return sites in both drivers through it. Three sites changed value; the rest
were already correct and are now named. A bare `return 4;` is now asserted
absent, so the next site cannot reintroduce a literal without failing a test.

Constants rather than an enum because these cross the process boundary as ints
and are compared against shell exit codes; the names carry the spec's meaning so
a reader can check the code against cli.md:42 without leaving the file.

### Why the conformance test reads source

`ExitCodeConformanceTest` inspects the drivers' source rather than driving them.
That is normally the weaker technique, and it is here deliberately: proving exit 4
*is* provider failure and exit 3 *is* privacy blocked by execution needs six
distinct failure modes, two of which (provider outage, routing terminal) cannot
be provoked offline at all. That unreachable-by-test shape is precisely how the
original gap survived. The test also fails loudly if the block count drops to
zero, so a refactor cannot make it pass vacuously.

### Proof it has power

Reintroducing the original defect in each driver separately:

| Mutation | Result |
|---|---|
| baseline | 17 tests, 0 failures |
| live driver: privacy → provider code | **2 failures** |
| offline driver: privacy → provider code | **3 failures** |

Packaged binary: `privacy blocked -> exit 3`, `admitted -> exit 0`.

### Not fixed here, recorded so it is not lost

`trace.capture` and `trace.onFailure` are still parsed-but-unread, and this
increment found a THIRD inert-thing in the same area: **`ReplayEngine` is dead
code**. Nothing in `src/main` references it or `ReplayOutcome`; there is no
`replay` command, though cli.md:14 documents `rahu replay RUN_PATH`. So the
replay half of G06 is not wired, not merely mis-wired, and observability.md's
"if payloads are absent, report replay unavailable" has no reachable surface.
`capture: payloads` has no writer, so no run can ever produce a replayable trace.

That is a larger piece of work than an exit-code fix and is the next increment.
Recorded here because I found it while scoping, and it would have been easy to
mention `capture` in a commit message and leave the actual gap unstated.


---

## AUDIT-2026-10-03-e — replay was dead code, and the engine would have lied

Scoped from the AUDIT-2026-10-03-d note. The finding was larger than the note
said.

### The gap

`cli.md` documents nine commands. The packaged CLI had five. Missing entirely:

| Command | cli.md | Status |
|---|---|---|
| `rahu replay RUN_PATH` | :14 | **did not exist** |
| `rahu trace inspect RUN_PATH` | :13 | **did not exist** |
| `rahu run --config FILE` | :11 | **did not exist** |

`ReplayEngine` and `ReplayOutcome` existed and were unit-tested. Nothing in
`src/main` referenced either. `trace.capture` and `trace.onFailure` were parsed
into config and read by nothing, so `payloads` and `metadata` were
indistinguishable. `docs/plans/active/001-dogfood.md:121` carries a **checked**
box claiming "complete run/chat/inspect/replay/eval help and exact commands",
verified by "demo/validate/eval 6/6 exit 0" — the three commands that were
tested, and none of the four claimed. G06 was marked PASSED on that basis.

So `replay` is now implemented and wired. `run` and `trace inspect` are not, and
G06 cannot honestly be marked PASSED until they are.

### The defect behind the dead code

`ReplayEngine` did not take the resolver's confidence parameters. It hardcoded:

```java
new RouteResolver.ResolutionInput(candidates, baselineId, fallbackId,
    "chosen_probability", 0.65);
```

Those are the config **defaults**, not the captured values. Every replay of a run
configured with any other `confidenceField` or `confidenceFloor` would have
resolved against inputs that turn never used, and then reported agreement or
disagreement about a routing question that had not been asked.

This is worse than dead code, and it is the reason dead code can be dangerous:
wiring it up unchanged would have produced confident wrong answers. It is now the
entry point's own regression — a test asserts the defaults-based overload stays
`@Deprecated`, and the frozen-input overload routes past it.

### What replay is, and what it is not

Per observability.md:34-38, policy replay: frozen routing inputs pushed through
the same `RouteResolver` the live turn used. Not live rerun (billable), not
crash recovery (replay cannot do it).

`ReplayCapture` stores the candidate ids, the exclusion list, baseline/fallback,
the confidence field and floor, and the recorded decision — exactly what the
resolver consumes. It stores **no resolution**: persisting the outcome beside the
inputs would let a future caller "compare" against a stored answer, which is a
comparison that cannot fail. A test asserts the capture contains no
`suggested`/`executed`/`degraded`.

It also stores no prompt text, no answer, no tool content. That is why
privacy.md:27's re-scan obligation does not arise: there is no generated content
in the file to re-scan, because the capture is built from the candidate set
rather than the transcript. Two tests assert this, one against the literal
prompt the driver was actually given.

### Guarantees, and how each is enforced

- **No network, no tools (A11).** Proven structurally by scanning the four
  production classes for `HttpClient`, `http.`, `OkHttp`, `URL(`, `Socket`,
  `openStream`, `OpenRouterProvider`, `DecisionEngine`, `Tools.`, and asserting
  none appear. A behavioural test cannot prove the *absence* of a call.
- **UNAVAILABLE is real.** Metadata capture is the default, so replay of an
  ordinary run reports unavailable and exits 3. It never fabricates a
  resolution — a fabricated replay looks like evidence.
- **Integrity first.** A trace with no `RunStarted`, or no `RunTerminated`, is
  refused with exit 5 rather than replayed. A run that stopped mid-turn has no
  final routing outcome, and re-deriving one would report agreement about a turn
  that did not complete.
- **Refuse, never substitute.** A capture missing `confidenceFloor`, carrying an
  unknown `schemaVersion`, or naming a candidate whose reasoning-policy suffix is
  not in `CandidateFactory`'s vocabulary, is rejected. Substituting the default
  would replay against inputs that were never recorded.

The policy suffix check matters more than it looks: decoding with a private
vocabulary would let an id the live run could not produce replay happily, so the
replay would report agreement about a candidate that never existed.

### Proof it has power

Reintroducing each original defect, running only the replay suites:

| Mutation | Result |
|---|---|
| baseline | 21 tests, 0 failures |
| `capture=payloads` still inert (the original bug) | 1 failure, 4 errors |
| driver never calls capture (the original bug) | 1 failure, 4 errors |
| replay re-applies hardcoded defaults (the original bug) | 2 failures |
| exclusions dropped from the capture | 2 failures |
| capture stores a resolution | 1 failure |
| missing floor silently defaulted | 1 failure |
| unknown policy suffix → provider default | 1 failure |
| schema version unchecked | 1 failure |

Packaged binary, four paths: metadata run → UNAVAILABLE exit 3; same in JSON →
`{"status":"UNAVAILABLE",...}`; trace with no terminal record → exit 5; missing
path → exit 3. REPLAYED output agrees with the recorded route at floor 0.65 and
correctly reports `agrees=false` at floor 0.95.

### A test that failed truthfully, twice

Worth recording because both failures were the test being right.

`ReplayEndToEndTest` initially failed: with a silent decision engine the live
turn makes no decision, so the capture legitimately holds none and every replay
is UNAVAILABLE. The temptation was to weaken the assertions to match. Instead a
`RoutingEngine` fixture now answers the routing question for real, and
UNAVAILABLE-when-absent is asserted separately where it is the property under
test.

Second: the policy-change test raised the captured floor to 0.99 and saw no
change. The fixture's uniform 0.5 probabilities are already rejected by the
configured 0.65 floor, so both replays rejected identically — the test would have
passed for the wrong reason on the original edit, had the floor been the only
thing checked. Lowering to 0.1 makes the same decision accepted, so the outcome
must differ. Same lesson as AUDIT-2026-10-03-d, from the other direction: a test
that cannot fail is worse than no test, because it is trusted.

### Fourth inert configuration key: `confidenceField`

Found while verifying the fix. `confidenceField` is parsed
(`ConfigLoader:226`), passed into `ResolutionInput`, written into the capture,
read back — and **never consulted by `RouteResolver`**. The resolver derives the
chosen probability from `probabilities().get(chosenLabel())`, so the field name
has no effect on any decision. Every fixture in the repo sets it to
`chosen_probability`, so no test could have detected this.

Recorded, not fixed. Making it select a probability field changes routing
semantics and is a design decision, not a wiring fix — the honest options are to
implement it or to remove it from the spec and schema. Left visible rather than
silently deleted from the record.

### Still open

- `rahu run` and `rahu trace inspect` do not exist. cli.md:11-13 document them.
- `trace.onFailure` remains parsed-but-unread; A16 hard-codes "stop", which is
  the safe default but leaves the key inert.
- `DemoCommand` still hand-builds its trace instead of using `RunTracer`.
- A live capture cannot be produced through the packaged CLI: `mode: live`
  requires the OpenRouter adapter, so the end-to-end capture proof above runs
  in-process against the real driver with fakes, and the packaged proof uses a
  hand-authored capture. The two together cover the command path and the wiring;
  neither alone would.


---

## AUDIT-2026-10-03-f — the fourth missing command, and a CLI that could not describe itself

Follow-on to AUDIT-2026-10-03-e. `rahu run` is still missing (see "Still
open"); this increment implements the other absent command and fixes two things
the new command's own verification exposed.

### `rahu trace inspect RUN_PATH` (cli.md:13)

Implemented, and it exists because of a distinction nothing else covered. The
other trace surfaces answer questions about the *run*; this one answers questions
about the *evidence*. Both "the run degraded" and "the trace never recorded what
the run did" look like a missing answer at the terminal, and an operator cannot
tell them apart from a missing answer. `trace inspect` reports which events a
trace holds, whether the record is complete, and whether a routing outcome was
left behind.

Guarantees, each enforced rather than asserted in prose:

- **No payload dumps.** cli.md:50 requires that local inspection "must not dump
  protected payloads by default". Payloads are read only to lift an allowlist of
  scalars — route ids, terminal reason, token counts, hashes. Written as an
  allowlist, not a blacklist: a blacklist must be updated whenever a payload grows
  a field, and the failure mode of forgetting is printing someone's prompt to a
  terminal. A new field here is simply not printed until someone decides it
  should be. There is no `--dump-payloads`, deliberately — adding one later is a
  privacy decision, not a convenience.
- **No network, no tools**, proven by scanning the source for `HttpClient`,
  `http://`, `OkHttp`, `URL(`, `Socket`, `openStream`, `OpenRouterProvider`,
  `DecisionEngine`, `Tools.`, `Dispatch`, `Generat`. A behavioural test can only
  prove a call *happened*; this proves the means is absent.
- **Reports, does not judge.** An incomplete trace exits 5. It does not claim the
  run failed — only that the file cannot support a claim about the whole run.

### Two things the new command forced into the open

**1. Two integrity predicates would have existed.** `replay` had its own; the
new command needed one. Two copies would drift, and the copy that drifts looser
silently starts approving incomplete traces. Extracted to `RunLocator` and
`replay` refactored onto it, so the two commands cannot disagree about whether
the same file is sound. A test asserts they agree on both a good and a bad file —
that test is what would notice if the sharing ever broke.

The shared predicate is stricter than `replay` was: it also rejects a **sequence
gap**. A trace missing event 3 while starting at 1 and ending at 4 looks complete
to a start-and-end check, and is not: events were lost, so it cannot support a
claim about the run. One case is deliberately still tolerated — a **truncated
final line after** `RunTerminated` **is** accepted, because the run demonstrably
completed and only its tail was lost. Collapsing "lost the tail of a finished run"
into "never finished" would refuse honest evidence.

**2. `"events.jsonl"` was a literal in five places** — the writer and two
readers. They agreed by luck, and that agreement is load-bearing and invisible: a
writer appending to one name while a reader looks for another reports "no run
trace", the same message an operator gets for pointing at the wrong directory. The
failure would be attributed to the operator rather than to the rename. Now
`TraceFiles`, with the writer and both readers pointing at it.

### The CLI could not describe itself

Found while verifying the new command by reading `--help`. **No command supported
`--help`.** `rahu demo --help` answered `Unknown option: '--help'`; `rahu chat
--help` failed on a missing `--config` *before* printing help, so a user could not
see how to supply it. cli.md:47 requires that help examples use consistent
terminology.

Fixed by adding `mixinStandardHelpOptions` to every command and subcommand. The
mixin is not inherited by subcommands — that was the first wrong fix, caught by
re-running rather than assuming — so `config show` needed its own.

`HelpSurfaceTest` pins the whole documented surface by driving `Main`, not by
instantiating command classes: registering a command and being able to invoke it
are different properties, and only the entry-point path sees both.

### A test that overstated its own coverage

My first version of `HelpSurfaceTest` was named "every command in cli.md exists"
and silently omitted `rahu run`. That is the exact defect the file exists to
prevent: a test whose name claims more than its body checks. Rewritten so
`DOCUMENTED` is named "every command cli.md documents that exists", with
`runIsTheKnownUnimplementedGap` asserting the absence **explicitly**. When `rahu
run` lands, that test fails and the fix is to move `run` into the list — a gap
marker that can be satisfied by weakening itself is worse than no marker.

### Two failures that were mine, not the code's

- Ten tests failed at once because the harness captured picocli's streams while
  the commands print via `System.out` directly. A harness that silently captures
  the wrong stream makes a working command look silent. Fixed at the JVM level.
- The reader crashed on a truncated line before the integrity gate could report
  it — so a damaged trace could never be inspected, which is exactly when an
  operator most needs to look. The reader now parses what it can and lets the
  shared predicate judge. The tolerance cannot make a damaged trace look sound,
  because the exit code comes from that independent check.
- My hand-written `replay.json` fixture was rejected by `replay` with exit 5. The
  honest reading was that the *fixture* was wrong: it only looked like the
  artifact. It now uses `ReplayCapture.write`, the production writer.

### Proof

Mutation battery, trace suites only (baseline 35/0):

| Mutation | Result |
|---|---|
| `trace inspect` not registered on `Main` | 12 failures |
| terminal-event check removed | 2 failures |
| sequence-gap check removed | 1 failure |
| corrupt/truncated-line check removed | 1 failure |
| ambiguous parent picks first instead of refusing | 1 failure |
| payloads reported wholesale | 1 failure |
| integrity verdict ignored (always exit 0) | 4 failures |

Help surface (baseline 5/0): mixin removed from demo → 2 failures; chat → 3;
eval → 3; trace inspect → 3; `replay` unregistered → 1.

Packaged binary: all eleven documented commands answer `--help` and `-h` with
usage and exit 0. `trace inspect` on a real offline turn reports `COMPLETE`, two
events, `capture=false`, and the `RunTerminated` reason; JSON form matches.

### Still open

- **`rahu run` (cli.md:11) is still unimplemented.** The only remaining gap in
  the command table. G06 cannot honestly be marked PASSED until it exists.
- A real offline `chat` turn records `RunStarted` + `RunTerminated` and **no
  `RouteResolved`**. Offline mode routes nothing, so there is no routing outcome
  to inspect or replay. Not yet classified as a defect — it may be correct for
  offline mode — but it means the offline path exercises far less of the trace
  contract than the live path does.
- `trace.onFailure` remains parsed-but-unread.
- `confidenceField` remains an inert config key (AUDIT-e).


---

## AUDIT-2026-10-03-g — `rahu run`, the last missing command

Follow-on to (f). `rahu run` (cli.md:11) was the final gap in the command table, so
**G06 could not honestly be marked PASSED** until it existed. This increment implements
it, and the four defects below were all found by verification rather than by reading.

### Two things deliberately NOT duplicated

Copying either would have reproduced a shape this repo has already paid for twice — two
integrity predicates (AUDIT-f), two capture integrities (AUDIT-e) — where the copies
drift and the drift is invisible because both are exercised by the same suite.

**`LiveAssembly`** holds the 60-line live wiring that lived in `ChatCommand.runLive`:
provider, decision engine, evidence-gated router, session, path boundary, narrowed tool
registry, tool loop. `run` and `chat` now build from it. This is where drift is most
dangerous: a tool left enabled in one command and disabled in the other is a **privacy
difference, not a refactor**. `ChatCommand` delegates `workspaceRegistry` and
`provenance` to it too, so "what `unknown` means" cannot differ between the commands —
verified, not assumed: `rahu chat` and `rahu run` both exit 3 with
`unknown-provenance` on the same unclassified input.

**`TurnOutcome`** replaces `oneTurn`'s bare `int`. cli.md:15 requires `run --format json`
to emit "one final structured result"; building that by scraping the human stderr trail
would make the machine surface a function of phrasing — the reason the trace writer is
not reconstructed from log lines. `LiveTurnDriver.turn()` is the SAME body `chat`
already ran; `oneTurn` is now a thin wrapper. A second implementation of the turn would
be free to disagree about which candidate ran.

`RepoFile` was extracted for the same reason in test code: it existed as a private copy
in two test classes already, and a copy resolving the wrong root fails as "fixture
missing" — a misleading error for whoever reads the failure next.

### The four defects

**1. `--prompt /status` exited 0 silently, answering nothing.** `run` passed
`line -> null` to mean "no commands", but a *non-null* handler still entered the
driver's slash branch, and the null-means-continue path then `continue`d **past the
turn**. Result: exit 0, empty stdout, no trace — indistinguishable from a run that never
happened, and invisible because exit 0 reads as success. Fixed with an explicit
`slashCommandsEnabled` flag rather than a null check: a null handler is a value a caller
can supply by accident, and the accident is silent. Same guard applied to
`LiveTurnDriver`, where the identical latent bug was waiting.

**2. The offline path wrote no trace at all.** The first implementation inlined the
offline answer; `chat` offline wrote RunStarted + RunTerminated while `run` wrote
**nothing**. That is the AUDIT-a shape exactly — a path that answers without leaving
evidence. Fixed by driving `OfflineTurnDriver` with a single in-memory line, which makes
the privacy gate, the trace and the fixed answer impossible to omit because they live in
the one place both commands share.

**3. JSON mode emitted the answer twice.** Offline mode printed the fixed answer to
stdout *and* then the JSON document. A filter cannot un-print a stream, so suppression
happens where the print happens (`printAnswer`), in both drivers.

**4. `runId` was invented rather than reported.** The offline JSON reported
`"runId": null` while a trace *was* on disk. The id now comes from the driver that
wrote it, and the packaged run is verified to resolve to a real directory:
`trace inspect` on the exact reported id returns `COMPLETE`.

### A test that overstated its own coverage

The first `RunCommandTest` asserted `--routing` and `--capture-payloads` were
*accepted*, which passes even when a flag is parsed and discarded — the same defect shape
as inert `confidenceField`. The mutation battery proved it: both mutations produced **0
failures**. Fixed by making `applyOverrides` static and asserting its *effect* on the
effective config, plus that the override reaches the router. Both now caught.

Similarly, `TurnOutcomeTest` was added only after the battery showed the driver printing
the answer in JSON mode was **uncaught** — because no test drove a real turn.

### Offline and live JSON expose the same KEYS

A schema that changes shape with the mode is a schema no consumer can rely on:
automation would need two parsers and would discover the difference as a KeyError in
someone else's pipeline. Values differ honestly (offline has no route, no cost, no
model call); the keys do not. `jsonSchemaIsStableAcrossModes` pins it.

### Proof

Mutation battery (54 tests baseline), all caught:

| Mutation | Failures |
|---|---|
| `run` unregistered on `Main` | 16 + 1 error |
| provenance always unknown (privacy) | 11 |
| JSON answer not suppressed (offline) | 3 |
| offline `run` writes no trace | 2 |
| routing override ignored | 2 |
| `tools.enabled` ignored (F-4 regression) | 2 |
| unobserved cost shown as observed (A10) | 2 |
| slash-as-command (silent exit 0) | 1 |
| `runId` never reported | 1 |
| answer printed in live JSON mode | 1 |
| capture override ignored | 1 |

Packaged binary: all **twelve** documented commands answer `--help`; `--prompt /status`
is answered as task text; stdout is exactly one line; JSON is one parseable document
whose `runId` resolves to a real trace directory; `trace inspect` reads it and reports
`COMPLETE`; `replay` reports UNAVAILABLE for a metadata-capture run rather than
reconstructing anything. Privacy block exits 3 with zero content leakage on either
stream. 427 tests green.

### Still open (unchanged, and now smaller)

- **The offline path records no `RouteResolved`** — confirmed here, and it is *correct*:
  offline mode routes nothing, so there is no routing outcome to record. A `run`/`chat`
  offline trace is a truthful two-event record, not a degraded one.
- `trace.onFailure` remains parsed-but-unread.
- `confidenceField` remains an inert config key (AUDIT-e).
- `DemoCommand` still hand-builds its trace.


---

## AUDIT-2026-10-03-h — `trace.onFailure`, and a refusal I had to talk myself out of

G06's last two "parsed-but-unread" keys. The register recorded these as one item; they
are three defects with three different shapes, and one of them I nearly fixed in the
wrong direction.

### `capture="payload"` loaded clean, and that is a silent loss of guarantee

`bindTrace` accepted any string. `TurnTrace.payloadsEnabled` compares against
`"payloads"`, so a one-character typo went false — no replay inputs written — while the
operator's config said payloads. The failure mode is unusually well-camouflaged:
`replay` then reported UNAVAILABLE, which is *honest*, and nothing anywhere said the
config was wrong. An honest tool downstream cannot compensate for a wrong input
upstream. Now refused at load, naming both accepted values and what the typo would have
caused.

### `onFailure: warn` — and the refusal I argued myself out of

The schema advertised `stop|warn`; nothing read either. The obvious "fix" is to
implement `warn`. That would have been wrong.

`observability.md:32` requires that a persistence failure "stops new operations with
TRACE_FAILURE", and A16 requires stopping and never inventing completion. A
continue-on-failure mode lets a run present an answer whose trace never reached disk —
precisely the failure this subsystem exists to prevent. There is no spec basis for
`warn`; the enum value was an aspiration.

So it is **refused with the reasoning attached**, and removed from the schema
(`enum` → `const`). A key the spec forbids must not be advertised by our own artifact:
while the schema offered it, every editor and validator suggested a config that cannot
load. This is the one place in this audit where the correct fix was to *remove*
capability, and it is worth being explicit that removing it is the safer choice.

### The error message asserted a cause it had not checked

`ReplayCapture.read` said "trace.capture=payloads is required" — unconditionally, for
any missing capture. For an operator who had *already set payloads* that is an
instruction to edit a correct config. The true cause can be that the mode never routed
(offline records no `RouteResolved`), so the message now reads the trace and
distinguishes: routed-but-uncaptured, versus unrouted. And `ReplayCommand` printed a
*second* line repeating the unconditional blame, so the output contradicted itself —
"offline routed nothing" immediately followed by "you need capture=payloads".

### How this survived a green suite: three separate reasons

Worth recording, because each would independently hide it.

**Nothing asserted the *effect*.** `ConfigLoaderTest` had one trace line in a fixture
and asserted nothing about it. Asserting "a bad value does not throw" passes whether the
loader refuses the value or silently accepts it — the inert-config defect again.

**Nothing drove `replay` at all.** No test executed the command. Its entire output
surface was unasserted.

**`ReplayCommand` prints to `System.out`, not an injected writer.** Unlike
`RunCommand`, it never had a `PrintWriter`, so `run(...)` captured *nothing* — every
assertion on its output would have passed on an empty string. I only found this because
a mutation survived that should not have. Converting both trace commands to injected
writers is AUDIT-2026-10-03-i; until then the test captures the real stream, with the
reason recorded in the helper.

### A banned phrase is not a property

My first fix asserted the output did not contain `"capture=payloads is required"`.
Restoring the original defect — which read *"Replay requires trace.capture=payloads; the
default metadata capture…"* — **survived**, because the wording differs. The assertion
was pinned to a string rather than to the property.

Replaced with the property: any line mentioning `capture=payloads` must carry the
qualifier "necessary but not sufficient", and at most one line may mention the flag at
all. That kills both the original wording and any future variant. The general rule:
*ban a behaviour, not a spelling* — a phrase ban only catches the exact phrase you
remembered.

### Proof

Mutation battery (74 tests), every defect restored verbatim, all caught:

| Restored defect | Failures |
|---|---|
| cause branches swapped | 5 |
| capture dropped on the way through | 3 + 4 errors |
| replay never inspects the trace | 3 |
| capture typo accepted | 2 |
| `onFailure=warn` accepted | 2 |
| explanation stripped from the message | 1 |
| schema re-advertises `warn` | 1 |
| contradictory second line restored | 1 |

One existing assertion had to be corrected: `ReplayEndToEndTest` asserted the message
contains `"trace.capture=payloads"`, which was true only because the error named that
cause unconditionally. It now asserts the *specific* cause, and separately verifies the
trace really recorded `RouteResolved`, so the message's claim rests on evidence the test
checks.

Packaged binary: a capture typo and `onFailure=warn` both exit 2 with actionable
reasons; the correctly spelled `payloads` still exits 0; offline replay's text and JSON
output agree and neither tells the operator to change a correct config. 438 tests green.

### Still open

- **AUDIT-2026-10-03-i:** `ReplayCommand` and `TraceInspectCommand` print to
  `System.out`/`System.err` instead of injected writers, so their output is only
  assertable by capturing the real stream.
- `DemoCommand` still hand-builds its trace rather than using `RunTracer`.
- `confidenceField` remains an inert config key (AUDIT-e).


---

## AUDIT-2026-10-03-i — three commands whose output no test could see

Registered as a one-line leftover. It was the worst defect shape found in this audit,
and it was only found because a mutation *survived*.

### The defect

`ReplayCommand`, `TraceInspectCommand` and `TraceCommand` printed to `System.out` /
`System.err` while every other command used picocli's injected writers. The test
harness sets those writers, so it captured **nothing** for these three — and every
assertion on their output passed on an **empty string**.

That is the most dangerous kind of test defect, because it is invisible. The
assertions look real, the suite is green, and the code is wrong. It is precisely how
`replay` survived an increment telling operators to change an already-correct config
while its own test "verified" the message: the assertions were not weak, they were
reading nothing.

Worse, `RunCommandTest` had already grown a `capturingSystemOut` workaround with a
comment explaining it. The workaround was *keeping the defect alive and documented*
rather than fixing it — the previous increment noticed the problem and routed around
it, which is what made it feel resolved.

`RunLocator` — the shared locator both trace commands call, and the one place the
codebase decides *which* run was asked for — printed to `System.err` from a `static`
method with no access to a writer. So the diagnostic that names the candidates when
several runs match was unreachable from any test.

### The fix

All three commands take `@Spec CommandSpec` and route through
`spec.commandLine().getOut()/getErr()`. `RunLocator.locate` now takes an injected
`PrintWriter` instead of reaching for the global stream. The `capturingSystemOut`
workaround and its `Captured` record are **deleted** — the test now uses the ordinary
harness, so a future regression that returns to the real stream fails on an empty
string rather than being papered over.

### Two tests, because catching the instance is not enough

`InjectedOutputTest` closes the *class*, not just the three files:

1. **A source scan** over `src/main/java` — a new command that reaches for
   `System.out` fails the build rather than quietly becoming untestable.
2. **A subcommand guard** — the scan exempts prints inside `public static void main`,
   because a `main` body invoked by `mvn exec:java` is a program entry point with no
   picocli writers to route through. That exemption is only honest while those files
   are *not* shipped commands, so the guard asserts it: if `SchemaGenerator` or
   `ShadowCorpusProbe` were ever registered in `Main`'s subcommands, their `main`
   bodies would become a library path and the guard fails. Verified rather than
   assumed — neither is currently in `Main`.

The exemption is scoped **structurally** (position after a `main` declaration), not by
a hand-maintained list of filenames, so a renamed file cannot silently re-enter scope.

### A vacuous assertion is indistinguishable from a passing one

The property assertions on replay's output all pass on an empty string — the exact trap
this increment removes. So `RunCommandTest` now asserts `replay.out().length() > 40` as
a **positive control**: if output ever goes missing again, that fails loudly instead of
the checks above silently becoming true of nothing.

### What I got wrong while verifying the guard

I mutated the *guard* four ways to check it was tamper-resistant. Three of those
mutations were **self-disabling** — disabling a check cannot make the check fail, so
they proved nothing and I discarded them. A green result from "remove the guard" is not
evidence the guard works. Replaced with mutations of the **production** code in each
API shape the scan claims to cover, which is the only honest test:

| Real bypass restored | Result |
|---|---|
| `System.out.print` (no `println`) | 1 failure |
| `System.out.printf` | 1 failure |
| `System.out.format` | 1 failure |
| `System.out.println` in `ReplayCommand` | 1 failure |
| `System.out.println` in `TraceInspectCommand` | 2 failures |
| `System.err.println` in `TraceCommand` | 2 failures |
| `System.err.println` in `RunLocator` | 1 failure |

One incidental correction: the scan first hard-coded `src/main/java`, which surefire
only happens to satisfy. It now resolves the tree from the test class's own code source,
so it does not depend on how the tests are launched.

### Packaged binary

Behaviour unchanged, streams still split correctly under redirection: replay exits 3
with the cause-specific message, `trace inspect` reports `COMPLETE`, bare `rahu trace`
puts usage on stderr and stdout stays empty (0 bytes), JSON stays machine-parseable,
and the multi-run parent still names its candidates. 442 tests green.

### Still open

- `DemoCommand` still hand-builds its trace rather than using `RunTracer`.
- `confidenceField` remains an inert config key (AUDIT-e).


---

## AUDIT-2026-10-03-j — a second trace writer, and "unobserved" reported as zero

Two defects found by asking a narrow question: does `demo` still hand-build its trace?
Yes. Following that turned up a reader bug affecting every run, not just the demo.

### Defect 1: `DemoCommand` was a second, independently-maintained trace writer

It assembled the JSONL envelope with a private `event()` helper instead of using
`RunTracer`. It had **already drifted**:

| Event | Fields the hand-built writer omitted |
|---|---|
| `RunStarted` | `turnIndex` |
| `RouteResolved` | `degraded`, `excludedCandidates` |
| `ModelCompleted` | `usage` was the STRING `"unavailable"`, not a counters object |

Nothing caught it, because nothing compared the two writers. `TraceEvent.payloadJson`
is an untyped `String` — **no code validates trace payload shape at all**, so a
divergent dialect parses fine.

That last point is what makes the omission dangerous rather than cosmetic: `inspect`
reads `degraded` with `asBoolean(false)` and the string-`usage` payload with
`asInt(0)`. So a demo trace reported `degraded: false` and `promptTokens: 0` — claims
the trace never actually recorded, indistinguishable from real measurements.

Demo now emits through `RunTracer`. The demo's job is to show what a real run looks
like, and it cannot do that while writing a different dialect.

### Defect 2: "unobserved" encoded as `0`, in two shipped outputs

Not demo-specific — the readers do it:

- `TraceInspectCommand`: `usage.path("promptTokens").asInt(0)`
- `RunCommand.renderJson`: `.promptTokensOpt().orElse(0)`

`LiveTurnDriver` already printed `"unknown"` for the same absence, so the codebase
knew the distinction existed and two output paths contradicted it. A provider that
returns no counts becomes a call reported as free. For a tool whose job is reporting
what happened, a self-flattering default is a defect.

Both now emit JSON `null`. **451 tests green.**

### What the tests assert, and how I know they bite

`DemoCommandTest` asserts the demo's trace is dialect-compatible with the real tracer
(every tracer field present, `usage` an object, values honest), plus a round trip:
`inspect` must read the demo trace and preserve the null. `TraceInspectCommandTest`
and `RunCommandTest` each assert both directions — unobserved is null, **and** a
recorded value still comes through as a number, so "always null" can't pass while
destroying the feature.

Mutation results, all restored verbatim:

| Defect restored | Result |
|---|---|
| demo: usage fabricated as zeros | 2 failures |
| tracer: `turnIndex` dropped | 1 failure |
| tracer: `degraded` dropped | 4 failures |
| inspect: `asInt(0)` | 2 failures |
| summary.json: `promptTokens` `orElse(0)` | 1 failure |
| summary.json: `completionTokens` `orElse(0)` | 1 failure |

### Three things I got wrong verifying this

1. **I invented an API.** Wrote `RunCommand.renderSummaryJsonForTest` and guessed
   `Usage`'s arity (it's 4 fields, not 3). Compiler caught both. The real need was to
   reach the observed-cost encoding at all, so `renderJson` was made package-private —
   a real seam serving a real test, not a fabricated one.

2. **My new `summary.json` tests passed vacuously.** Mutating `orElse(0)` back
   *survived*. Cause: `costUnobserved()` is true when usage is absent **or cost is
   absent**, so the `else` branch only runs when a cost *was* observed — and the offline
   engine records no usage at all, so the branch was never entered. The reachable case
   is a provider that **bills a call but returns no token counts**. Added that
   specifically; it is the one input that distinguishes the two encodings.

3. **I nearly filed a false "uncovered" finding.** `micros`' `orNull` still survived
   mutation, and `costUnobserved()` itself could be deleted with every test green.
   Before reporting that as a hole I re-ran against `TurnOutcomeTest`, which owns the
   gate — both mutations produce 2 failures there. **No hole: my mutation battery was
   scoped too narrowly.** Lesson: a surviving mutation is evidence about the *battery*,
   not automatically about the code.

### A test bug worth recording

My first `DemoCommandTest` used `@TempDir` and read `$TMPDIR/.rahu/runs` — while the
command wrote to the **repository**, because the JVM caches its working directory and
`user.dir` cannot redirect it. The "isolated" tests were creating real run directories
in the repo. Fixed with a package-private `DemoCommand.traceRoot` seam, seeded and
restored per test. `@TempDir` is not process isolation; check what the code under test
actually resolves against.

`.rahu/` is gitignored, so nothing was committed — but the test would have polluted a
developer's working tree on every run.

### Packaged binary

Demo output unchanged; its trace now carries `turnIndex`, `degraded`,
`excludedCandidates` and a proper `usage` object; `inspect` reads it as `COMPLETE` and
preserves the nulls; `run --format json` reports `cost: null` for an unobserved cost.

### NEW FINDING (not fixed here) — the offline run's footer is missing

`rahu run` against `examples/offline.json` writes an answer to stdout and **nothing to
stderr** — no routing line, no cost line — even though `renderJson`/`footer` both write
to `err()`. Its trace contains only `RunStarted` + `RunTerminated`, so `inspect` reports
no `route` and no `usage`.

Verified pre-existing: built HEAD in a clean worktree and got byte-identical stdout/stderr
to the working tree, so this increment did not cause it. It means the offline path never
reaches the footer, which also explains why no test noticed the cost conflation through
the CLI. Worth its own increment.

### Still open

- `confidenceField` remains an inert config key (AUDIT-e).


---

## AUDIT-2026-10-03-k — FIXED: the offline path reported constants, not observations

One root cause, three shipped defects. `runOffline` ended with `return code;` — a
bare `int` — while the live path builds a `TurnOutcome` and hands it to
`footer()`/`renderJson()`. A path that returns an int cannot report anything, so
every offline field was hand-written.

### Defect 1: the footer was never printed

`footer(outcome)` was called on the live path only. Offline printed the answer and
nothing about routing, cost, or trace location. Now `runOffline` calls the same
`footer()`, so both modes report from one place.

### Defect 2: `status` carried a DIFFERENT VOCABULARY per mode

This is the one that mattered most, and it is not a rendering bug.

| | success | privacy block |
|---|---|---|
| live | `ANSWER_COMPLETE` | `PRIVACY_BLOCKED` |
| offline (before) | `COMPLETE` | `BLOCKED` |

cli.md:50 fixes the vocabulary: a block "returns terminal reason `PRIVACY_BLOCKED`,
exit 3 … JSON includes the same typed status". Offline said `BLOCKED` — a word
`PRIVACY_BLOCKED` is a superstring of. A consumer keying on the spec value silently
matches **every** offline block, and one keying on `COMPLETE` misreads live
success. The exit codes matched; the machine contract did not.

### Defect 3: observed fields were hardcoded

`generationSteps` was a literal `0` for a run that generated a step, and `answer`
was a literal `null` for an answer that had just been printed. Only `status`,
`exitCode` and `runId` were interpolated.

### The fix

`OfflineTurnDriver` now exposes `lastOutcome()` and every one of its exit sites goes
through one `outcome(...)` builder, so no return site can forget to record itself.
`runOffline` renders through the **same** `renderJson`/`footer` as live. Timings are
measured (`System.nanoTime`), not invented. `renderOfflineJson` — 886 characters of
hand-written JSON that existed only because the offline path had nothing to render
from — is **deleted**.

### Why the existing test missed all three

`jsonSchemaIsStableAcrossModes` compared the **key set** across modes. All three
defects live in the **values**. Keys were always identical, because the offline
document was written to mirror the live one. The test asserted the schema and
nothing about the content, and the divergence was exactly where it did not look.

Lesson recorded: a schema-equality test proves the schema, nothing more. Any field
whose *value* is produced by a different code path needs its own comparison.

### A test that pinned a defect

`jsonCarriesAnswerAndStatus` asserted `status == "COMPLETE"` — it had frozen the
divergence in place. Updated to `ANSWER_COMPLETE`, with the reasoning inline and a
new assertion that `answer` is not null. Worth noting: when fixing a defect, check
whether an existing green test is *protecting* the old behaviour.

### Mutations (all restored verbatim)

| Mutation | Result |
|---|---|
| driver reports `BLOCKED` not `PRIVACY_BLOCKED` | 1 failure |
| driver: `generationSteps` always 0 | 1 failure |
| driver: answer suppressed | 1 failure |
| `runOffline`: footer skipped again | 1 failure |
| `runOffline`: outcome discarded, fallback used | 6 failures |

The last one mattered: the `orElseGet` fallback was reachable by every offline
assertion, and `offlineTextModePrintsAFooter` caught the resulting
`trace unwritten` — so the fallback is not silently load-bearing.

### 454 tests green. Packaged binary

- text mode: `Cost unavailable · 1 generation step · 0.031 s · trace run-…`
- json mode: `status: ANSWER_COMPLETE`, real `answer`, `generationSteps: 1`, real `runId`
- privacy block: exit 3, `status: PRIVACY_BLOCKED`, no content
- the `runId` in the document resolves to a trace on disk that `inspect` reports
  `COMPLETE`, containing exactly `RunStarted` + `RunTerminated`

That last point is the one I would have broken by "fixing" the missing events: the
offline trace records no `RouteResolved`/`ModelCompleted` **because none happened**,
and manufacturing them would be the AUDIT-f mistake in a new place.

### Notes on my own process

- I invented `offlineConfig()`/`configPath()`/`runCli()` in the first test draft and
  hit a `ValueError` because none exist. Last increment's mistake, repeated once.
  Read the real helpers first.
- My first binary probe omitted `--format json` and I reported "empty stdout" as a
  symptom when it was my own omission. Re-ran before concluding.
- A `grep` with no path argument read stdin and hung for 300s, killing the kernel.
  Use the search tool.
- `turnStartedNanos`/`lastTurnMillis` became dead after wiring; LSP flagged the
  leftover assignment immediately. Both removed.

### Still open

- `confidenceField` remains an inert config key (AUDIT-e).
 — the offline path never builds a result, so it reports nothing

Found by following AUDIT-j's footer finding rather than filing it. The cause is
structural, and it is why the cost conflation in AUDIT-j was invisible through the CLI.

### The defect

`runOffline` ends with `return code;` — an `int`. The live path instead builds a
`TurnOutcome` and calls `footer(outcome)`, which is what prints the routing line, the
token line and the cost line. So **offline `run` prints an answer and nothing else.**

Verified pre-existing (HEAD in a clean worktree produced byte-identical stdout/stderr),
so this did not originate in AUDIT-j.

Two consequences, and the second is worse than the missing footer:

1. The human footer is absent, so an operator running offline gets no routing, no cost
   and no token information at all.

2. `renderOfflineJson` cannot report anything it did not hardcode. It emits
   `"routing":null,"cost":null` as **literal constants**, so the offline JSON document
   is a fixed string with `status`/`exitCode`/`runId` interpolated — it is not a
   rendering of what happened. It happens to be *honest* today (the offline engine
   genuinely resolves no route and calls no model), but it is honest by hardcoding, not
   by observation. The moment the offline driver resolves a route or the trace records
   one, the document will still claim `null`.

### The trace records less than the live path, and that is correct

The offline trace holds only `RunStarted` + `RunTerminated`. That is *right*: no route
is resolved and no model is called, so there is no `RouteResolved` or `ModelCompleted`
to record. Manufacturing those events to make the trace look uniform would be the
AUDIT-f mistake in a new place — recording a claim the run never made. Left alone
deliberately.

### Superseded

This entry was written as a diagnosis before the fix. The fix, the mutation battery and the
packaged-binary evidence are in the FIXED entry above; the observation that the offline trace
records only `RunStarted` + `RunTerminated` remains correct and was deliberately preserved.

---

## AUDIT-2026-10-03-e — FIXED: `confidenceField`, deferred five times as "needs a wire-contract change"

The fifth "still open" line in this register. Recorded as HIGH by audit 016 (F-11),
flagged in audit 017, and carried forward through five increments. The stated reason
each time was that it needs a wire-contract change. It did — and it was small.

### What was actually wrong

`confidenceField` was parsed (`ConfigLoader:226`), schema-exposed, traced, captured,
replayed, and **never read**. `RouteResolver.resolve` compared
`chosenProb >= confidenceFloor` unconditionally, so every value of the setting
behaved identically to the default.

Worse than inert: the name is *descriptive*. An operator reading
`confidenceField: "raw_confidence"` reasonably concludes the gate runs on that field.
It ran on `chosen_probability` and said nothing.

### The spec decided it

routing.md:26: *"Keep probability of the chosen action, provider confidence and its
formula separate. Confidence thresholds operate on a named field; default
`chosen_probability` is 0.65."*

So `DecisionResult.ValidChoice` already carries both — `probabilities` and
`rawConfidence` — and the spec explicitly requires them kept apart. Two fields are
therefore supported: `chosen_probability` (default) and `raw_confidence`.

- `ResolutionInput` now **rejects an unknown field name**. Accepting one silently
  would restore exactly the lie being removed.
- The floor evaluates the **named** field.
- A named field the decision does not carry **fails the gate**. Substituting the
  default, `0.0` or `1.0` would manufacture evidence the decision never produced.
- The max-probability check stays on the probability in both cases: it is a property
  of the decision's distribution, not of the confidence field.

### Mutations

| Mutation | Result |
|---|---|
| resolver: always `chosen_probability` (the original defect) | 2 failures |
| resolver: absent named field falls back to the probability | 1 failure |
| resolver: unknown-field validation removed | 1 failure |
| resolver: `isFinite` filter removed | **0 failures — see below** |

### The surviving mutation was NOT an equivalent mutant

I had this rule recorded from AUDIT-j: a surviving mutation is evidence about the
*battery* first. So I checked before filing it as an equivalent mutant — and my
first reasoning was wrong.

I argued: "`NaN >= 0.65` is false, so with or without the filter a non-finite value
is rejected." Printing the actual comparisons:

```
NaN  >= 0.65  -> False
+inf >= 0.65  -> TRUE      <-- 
-inf >= 0.65  -> False
```

`Double.POSITIVE_INFINITY` satisfies any threshold. Without the filter, a provider
returning an infinite `raw_confidence` sails straight through the concentration gate
— precisely the failure the gate exists to prevent. The filter was load-bearing and
**no test covered it**.

Added `nonFiniteConfidenceNeverSatisfiesTheFloor` over both `+inf` and `NaN`. The
mutation now produces 1 failure, with the message showing the accepted route.

This is the mirror of the AUDIT-j lesson: there, a surviving mutation was a too-narrow
battery. Here it was a real defect in my own new code, hidden because the obvious
reasoning about it was *nearly* right — `NaN` behaves as I assumed, `+inf` does not.
"Equivalent mutant" is a claim to verify, never a conclusion to reach from one
example.

### A test-breaking change I had to make

`ReplayCaptureTest` passed `input("f", …)` — an arbitrary one-character field name in
eleven places, legal only because the field was never read. Replaced with
`chosen_probability`. Worth noting: those tests *passed on an invalid configuration*
for as long as the setting was inert. Once the setting is validated, an
accepted-and-ignored field stops being a free pass for arbitrary test data.

### 458 tests green (158 core / 14 openrouter / 30 systemone / 256 cli)

### Remaining inert configuration keys

`decision.confidenceSemantics`, `tools.resultBytes`,
`OperationRequirements.isTextAnswer` and `.structuredOutputRequired` are still
accepted and never read. Same shape, same treatment owed — but each needs its spec
read first, as this one did. Registered as the next sweep rather than bundled here.

---

## AUDIT-2026-10-03-l — the four remaining inert keys are NOT the same shape as `confidenceField`

Assessed individually rather than swept as one batch. AUDIT-e took one increment each,
so grouping four unrelated wirings behind one commit would repeat the mistake that
deferred AUDIT-e five times.

### 1. `decision.confidenceSemantics` — **not inert, it is carried**

`DecisionResult.ValidChoice.confidenceSemantics` is a *result* field, not a config
key, and it is populated (`"none"` in the fixture helper, `"provider_score"` in the
new tests). routing.md:26 requires "probability ... and its formula" be kept
separate — `confidenceSemantics` is where the formula's identity belongs.

It is **read nowhere**, so the separation the spec demands is currently unobservable:
a consumer cannot tell whether a 0.9 was a probability or a provider score. Now that
`confidenceField` can select `raw_confidence`, this matters more, not less: routing
on a provider score without knowing its semantics is the conflation routing.md:26
warns against, one layer up.

**Fix scope:** record the semantics in `RouteResolution`/`TraceWriter` so the chosen
confidence field and its semantics travel together into the trace. Small, and it makes
AUDIT-e auditable after the fact.

### 2. `tools.resultBytes` — **a real cap with no site to apply it**

Parsed and schema-exposed; `ToolLoop` has no result-truncation call at all, so there
is currently **no** place a byte cap would apply. This is not a wiring bug — it is an
unimplemented feature whose config key shipped first.

Wiring it means choosing semantics that do not exist yet: truncate silently (context.md
"Never silently truncate"), return a typed error, or drop the call. Each is a spec
decision, and none is written down.

**Fix scope:** BLOCKED on a spec decision, not on code. Needs a line in context.md
or artifacts.md saying what happens to an oversized tool result. Recorded as a
question for the spec, not a defect I should invent a behaviour for.

### 3 & 4. `OperationRequirements.isTextAnswer` / `.structuredOutputRequired` — **structurally inert, differently**

These are not config keys at all: they are record components of a *trusted* input,
constructed at exactly one site (`ActiveRouter:204-205`) with constant values `true`
and `false`. `CandidateFactory` reads `toolsExposed()` and `contextAllowanceTokens()`;
the other two are never consulted.

routing.md's candidate-generation step 3 describes "text modality flags" as part of
the requirements — so the record shape is per spec. The values are constant because
alpha has one modality and no structured-output path.

**Fix scope:** these are **future scaffolding**, not defects. No test can distinguish
them because nothing varies them. Correct action is to leave them and note that when
the first non-text modality ships they must be read — or delete them then. Removing
them now would break the spec's stated shape for no gain.

### Why this is recorded rather than fixed

Three of the four need a decision that belongs to the spec, not to me:
- what happens to an oversized tool result (none of silent-truncate/typed-error exists)
- how confidence semantics reach a consumer
- when a non-text modality actually arrives

The AUDIT-e failure mode was inventing behaviour ("wire-contract change") and then
deferring it five times. Naming each one's blocker is the alternative: each is now a
one-line question instead of a standing "still open".

### Nothing here is a privacy, cost or rollback gate

Checked deliberately. `resultBytes` is the only one with a safety flavour, and an
unbounded tool result is a **context** risk (context.md's bounded-feature rule), not a
privacy or spend risk — no data leaves the machine by being long. So this is correctly
a design question, not something to force through a gate.

---

## AUDIT-2026-10-03-m — cross-cutting re-audit: hunting SHAPES, not instances

AUDIT-j, -k and -e were each found by asking a narrow question and following it. That
works but it is opportunistic. This pass searches for the *shapes* those three fixes
revealed, to see whether more instances exist.

### Shape A: a path returning a bare `int` where its sibling returns a result object

The AUDIT-k root cause. `LiveTurnDriver` has the identical pair:

```java
private int oneTurn(...) {
    return turn(...).exitCode();      // the outcome is built, then thrown away
}
```

and `run()` consumes only that int. So the multi-turn chat loop discards every turn's
outcome, exactly as `runOffline` did.

**Assessed as NOT a defect**, on evidence:

- `/status` (cli.md:23 requires "current route, turn count and aggregate
  settled/reserved/uncertain spend") reads `session.turnCount()` and
  `session.ledger()` **directly** — not from a `TurnOutcome`. Verified live:
  `/status` prints `turns=0, settled=0 USD, uncertain=0, remaining=3.00,
  maxTurns not reached: true`, and `/reset` prints `reset: conversation cleared;
  ledger and counts retained`.
- `/bogus` and `/help` correctly exit 2 with `unsupported command /bogus;
  supported: /status /reset /exit` — cli.md:23's "reject unsupported slash commands
  with help before a paid call" is met, with **no** paid call made.

So the chat loop has no consumer starved by the bare int. It is a **latent** shape,
not a live bug: it becomes one the moment something needs a turn's outcome from
inside the loop. Recorded so the next reader knows it was checked and why it stands.

### Shape B: a second implementation of a writer/serializer

The AUDIT-j root cause. No further instance found: after AUDIT-j, both live and
offline paths go through `RunTracer`, and both `run` modes go through `renderJson`.

### Shape C: config validated but never read

The AUDIT-e root cause. Now exhaustively enumerated rather than sampled, because
AUDIT-e proved this class hides in plain sight (a field can be parsed, schema-exposed,
traced, replayed and still inert). Covered in AUDIT-l: one actionable
(`confidenceSemantics`), one blocked on a spec decision (`resultBytes`), two that are
future scaffolding (`isTextAnswer`, `structuredOutputRequired`).

### The thing I did not do

I got two turns into checking whether `/reset` preserves the turn count as cli.md:23
requires, hit a privacy block (my probe omitted `--input-classification`, which
`chat` does not take the way `run` does), and realised I was **inventing a probe**
rather than testing a hypothesis I had evidence for.

Stopping there is the point. Two increments ago I invented an API and a helper that
did not exist; the register records that. The discipline that is actually working is:
**do not manufacture a test for a hypothesis I have not first confirmed with a
source-level fact.** `/reset` turn-count retention is a real open question and is
recorded as one, unverified, rather than half-tested.

### Status

No new live defects from this pass. One latent shape recorded with its reason for
standing down, one unverified question recorded as a question. That is the honest
outcome of a sweep that found nothing — and worth recording precisely so a later
reader does not assume the pass was skipped.

---

## AUDIT-2026-10-03-n — the fix I shipped last increment made captures lossy

AUDIT-l item 1, and the most instructive entry in this register: **a correct fix
created a new defect in the same file, and an existing comment blessed it.**

### The defect

AUDIT-e made `confidenceField` select the field the floor reads. `ReplayCapture`
unaffected by that change, so it went on capturing a `ValidChoice` as:

```java
node.put("chosenLabel", choice.chosenLabel());
node.put("probabilities", ...);
// ... and that was all
```

It wrote neither `rawConfidence` nor `confidenceSemantics`, and `read()` rebuilt
every decision with `Optional.empty()` and a hardcoded `"captured"`.

So a run configured for `raw_confidence` produced a capture that **could not replay
itself**: the replay rebuilt a decision with no provider score, the floor failed for
a *different reason* than the live turn, and `ReplayOutcome` reported a degraded
replay that looked like a policy difference. `agrees()` compares suggestions, so it
would have reported disagreement — but for the wrong reason, on a capture that was
supposed to be faithful.

### The comment that blessed it

`encodeDecision` carried this javadoc:

> *"The `questionId` and confidence semantics are omitted because routing never
> consults them, and omitting them keeps the capture minimal — every stored field
> has a reader."*

That was **true when written and false as of AUDIT-e**. Routing consults the semantics
now, in the sense that matters: without it, a score has no formula and the capture
cannot say which confidence it recorded. This is the AUDIT-f failure mode in a
sentence — *every stored field has a reader* became *every stored field has a
reader, so nothing else is needed*, and the AUDIT-e commit invalidated the premise
without invalidating the comment.

**Rule adopted: a fix that changes what a consumer reads must re-check every comment
that justified the consumer's omissions.** A stale justification is worse than none,
because it actively discourages the next reader from looking.

### Spec basis

systemone.md:36: *"optional raw provider confidence plus **semantics identifier**"* —
the identifier is required, not optional metadata. routing.md:26 requires probability
and provider confidence be kept separate; recording the formula is what makes that
separation observable rather than asserted.

### Mutations (all restored verbatim)

| Mutation | Before | After |
|---|---|---|
| capture: `rawConfidence` dropped | 2 failures | 2 failures |
| capture: `confidenceSemantics` dropped | 1 failure | 1 failure |
| read: `rawConfidence` defaulted to 0 | **0 — survived** | 1 failure |
| read: semantics hardcoded to `"captured"` | **0 — survived** | 1 failure |
| capture: absent score written as 0 | **0 — survived** | 1 failure |

### The three survivors shared one cause, and finding it was the actual work

I nearly filed all three as equivalent mutants — the reflex after AUDIT-e, where one
survivor turned out to be real. Three is also too many for coincidence.

All three were the **same** gap: nothing asserted that **absence survives the round
trip**. AUDIT-e's `missingNamedFieldIsRejected` proved the *live* resolver refuses an
absent score; nothing proved the *capture* preserved the absence. Dropping the
null-check, defaulting to 0, and writing 0 for an absent score all yield a capture
that looks well-formed and replays a confidence the provider never gave — the
AUDIT-j conflation, one layer into the capture.

`absentRawConfidenceStaysAbsent` closes two of them by round-tripping a decision with
no provider score and asserting: still absent on the decoded object, JSON `null` in
the file, and the floor failing because there is no number to compare.

The third survivor needed a different assertion. `read: semantics hardcoded to
"captured"` still survived because my test only checked the capture **text** — the
file was right and the decoded **object** was wrong, which is precisely what a round
trip exists to detect. Added an assertion on the *reconstructed* `ValidChoice`. This
is the AUDIT-k lesson restated: `jsonSchemaIsStableAcrossModes` compared keys and
missed values; asserting the file missed the object.

### 461 tests green (158 core / 14 openrouter / 30 systemone / 259 cli)

### What I got wrong getting here

- Passed `ReplayCapture.write(...)`'s return straight to `read()` → FileNotFound.
  "Corrected" it by appending the filename again → "Not a directory". **Both were
  wrong**: `write` returns the file, `read` takes the directory. I fixed it by
  reading the actual signatures instead of iterating on error messages.
- Then relaxed a JSON assertion to match on the key rather than the value, which
  would have passed with a null value — the very defect. Replaced with a parsed
  `readTree` comparison.
- Left two orphaned lines from a string replacement, producing a compile error.

Three failures in a row from guessing at an API I could have read. The rule is
already in this register from AUDIT-k; I broke it three times in one increment.

### Still open

- `tools.resultBytes`: no site to apply it (AUDIT-l item 2), blocked on a spec decision.
- `isTextAnswer` / `structuredOutputRequired`: future scaffolding (AUDIT-l items 3-4).
- `/reset` turn-count retention: recorded as an unverified question in AUDIT-m.

---

## AUDIT-2026-10-03-o — a refusal that applied the mutation anyway

Closed the AUDIT-m open question first, then found a defect by reading the code
instead of probing it.

### The AUDIT-m question: `/reset` turn-count retention — NO DEFECT

cli.md:23 requires `/reset` to "clear content/continuation, retain ledger/count", and
context.md:52 repeats it. I had recorded this as an *unverified question* last
increment because I was inventing a probe rather than reading the source. Read first
this time, which is what I should have done then:

```java
public synchronized void resetConversation() {
    history.clear();
    lastFailure = null;
    if (active != null && !active.completed) { throw ... }
}
```

`turnCount` and `ledger` are not touched. Verified live on the packaged binary, not
just in a unit test:

```
turns=0 ... /reset ... turns=1, settled=0 USD, uncertain=0, remaining=3.00
```

**The count survives reset.** Question closed, no change needed.

### The defect: the guard ran AFTER the mutation

The method cleared `history` and `lastFailure`, and *then* refused if a turn was
active. `ChatCommand` catches the throw and prints:

```
reset refused: cannot reset during an active turn
```

So a **refused** reset had already wiped the conversation and the failure record.
The operator is told the reset did not happen, after it had. This is the
reported-vs-actual shape from AUDIT-j, and it is the variant that loses data: not a
misreported counter, a destroyed transcript with a reassurance attached.

### Why it was never observed

`orchestration.md:11` — "One driver mutates run state; one active turn owns a session.
All paid requests are sequential." The CLI reads a line, runs the turn to completion,
*then* reads the next line, so `active` is always null when `/reset` arrives. The
refusal path is **unreachable through the CLI**. The defect is only in the class's
contract, which is still a contract: the method's name and its javadoc promise one
thing and the implementation did another, and the next caller need not be the CLI.

I am recording the reachability honestly rather than inflating it. It is a real
defect with no current trigger — not a live data-loss bug.

### The fix

Guard first, mutate after. Validate before mutating, so a refusal is total.

### Mutations (all restored verbatim)

| Mutation | Failures |
|---|---|
| guard moved back after the mutation (the original defect) | 1 |
| guard deleted entirely (reset always allowed) | 1 |
| guard also refuses a *completed* turn (over-refuses) | **0 — equivalent** |

The surviving mutant is genuinely equivalent, and I checked rather than assumed.
`active` is assigned in exactly three places: set in `beginTurn`, and nulled at lines
107 and 114 inside `onTurnComplete`/`onTurnFailed` — the same `complete()`/`fail()`
call that sets `completed = true`. So `active != null` implies `!active.completed`,
and dropping the second conjunct cannot change behaviour. Equivalence established
by reading the assignments, which is the standard I set after the `+Infinity`
episode.

### Two adjacent suspicions, both checked and both NOT defects

1. **`onTurnComplete`/`onTurnFailed` are not `synchronized`**, though every public
   accessor is, and they mutate `turnCount`, `history` and `active`. That is a real
   inconsistency — but `grep` for `new Thread|Executors|parallelStream|CompletableFuture`
   across both `src/main` trees returns **zero** thread spawns, and
   `orchestration.md:11` mandates serial paid requests. Latent, not live. Recorded,
   not fixed: there is no concurrent caller to fix it for, and adding `synchronized`
   to code that is contractually single-threaded would imply a guarantee the spec does
   not ask for.
2. **`fail()` before `recordUser` yields `FailedTurn` with a null `userRequest`**,
   since `onTurnFailed` stores whatever `user` currently holds. `grep` for
   `lastFailedTurn` across `src/main` returns exactly **one** hit — the declaration
   itself. No consumer, so nothing dereferences it. Latent, recorded, not fixed.

### 462 tests green (159 core / 14 openrouter / 30 systemone / 259 cli)

### The lesson, which is the same one I broke last increment

Last increment I recorded: *"I stopped myself mid-way through checking `/reset` — I
was inventing a probe rather than testing a hypothesis I had evidence for."* The
correction was not to stop; it was to **read the source first**. That is what I did
this time, and it took ninety seconds instead of a speculative harness — and it is
what turned "an open question" into "an answer plus a real defect three lines away."

Also: I again passed a shell script where `subprocess.run` wanted an argument list,
and got a `FileNotFoundError` on the script's own text. Same class as the
`write()`/`read()` signature guess from last increment — assuming an API's shape
instead of checking it.

---

## AUDIT-2026-10-03-p — a config key I called "blocked on the spec" for five increments

Started by verifying the two "still open" items AUDIT-m named. **Both were already
fixed.** `DemoCommand` goes through `RunTracer` (AUDIT-j fixed it; my register had
carried the stale line through five entries). `trace.onFailure` is validated at load
(AUDIT-g fixed it). So the register's "still open" list had become unreliable, and the
next item on it — `tools.resultBytes` — was recorded five times as *"blocked on a spec
decision"*.

### It was never blocked. The spec had decided it twice.

- `configuration.md:24` lists `resultBytes` as a real `tools` key.
- `tools.md:5`: *"A descriptor has a stable name/version, description, JSON input
  schema, effect class, timeout, **result byte limit** and deterministic authorisation
  policy."*

And the cap was not missing either — `WorkspaceTools` enforced a hardcoded
`MAX_READ_BYTES = 64 * 1024`. The defect was narrower and duller than I had recorded:
**the configured value never reached the constant.** An operator setting
`tools.resultBytes: 2048` got 64 KiB and no warning.

I only found this because I finally read the specs instead of trusting my own note.

### A wrong conclusion I have to record

Mid-investigation I grepped for `MAX_READ_BYTES|65536|truncat` in
`rahu-cli/.../tools/WorkspaceTools.java` — **a path I had assumed**. It does not
exist; the class is in `rahu-core`. The grep found nothing, and I wrote that
`workspace.read` had *"no byte cap at all — not even the spec-mandated 64 KiB"*.

**That was false.** The cap is enforced. I concluded from an absence produced by
searching the wrong file, which is the worst way to be wrong: absence of evidence
treated as evidence of absence, the exact shape this register keeps documenting in
other code. Same class as the `write()`/`read()` signature guess and the
`subprocess.run` argument-list guess — assume an API's location or shape, then reason
confidently from the resulting error.

### The fix

- `DEFAULT_RESULT_BYTES = 64 KiB` becomes the *default*, not the cap.
- The configured value bounds **every** result: `read`, `list` and `search`.
- Truncation is always disclosed, and the marker names the limit that fired.
- Non-positive caps are rejected at construction: a zero cap would truncate every
  result to nothing while still reporting success.
- The per-file **scan** bound in `search` stays deliberately separate from the
  **result** bound, as `MAX_SEARCH_BYTES_PER_FILE`. Conflating them would make a small
  result cap change which matches a search can *find* — a search would stop matching
  text it was entitled to look at and report a clean "no matches".

### A second defect, found by running the binary rather than reading tests

`rahu run --config <resultBytes: 0>` exited **0**. `ConfigLoader` binds with
`n.path("resultBytes").asInt(65536)`, and `asInt` validates nothing, so the schema's
own `"minimum": 1` was never enforced. A schema that cannot stop an operator is
documentation. The same unvalidated `asInt` applied to `maxCallsPerStep`, where zero
would make the loop refuse every call while reporting a completed answer. Both are now
refused at load, naming the offending value.

Live proof after the fix:

```
resultBytes=    0 -> rc=2  config invalid: tools.resultBytes must be at least 1, got 0; ...
resultBytes=   -5 -> rc=2  config invalid: tools.resultBytes must be at least 1, got -5; ...
resultBytes=    1 -> rc=0  Cost unavailable · 1 generation step ...
resultBytes= 2000 -> rc=0  Cost unavailable · 1 generation step ...
```

### Mutations — and the two that mattered most

| Mutation | Failures |
|---|---|
| **assembly call site passes the default, ignoring the helper** | **0 → 1** |
| **loop ignores the cap handed to it** | **1** |
| **loop accessor lies (reports the default)** | **1** |
| read cap back to hardcoded 64 KiB | 1 |
| search drops `truncated` from the returned flag | **0 → 1** |
| list drops `truncated` from the returned flag | 1 |
| truncation marker suppressed | 1 |
| non-positive silently clamped to 1 | 1 |
| scan bound conflated with result cap | 1 |
| default changed to 1 byte | 4 |
| loader: `resultBytes` validation removed | 1 |
| loader: `maxCallsPerStep` validation removed | 1 |
| loader: rejects the legal value `1` | 1 |

**The call-site mutation is the one worth remembering.** My first wiring test asserted
`LiveAssembly.resultBytes(cfg)` — the *helper*. A mutation replacing the helper's result
at the original call site passed everything, because the helper was still correct and
nothing could see what assembly handed the loop. No amount of testing a pure function
proves a call site uses it. Fixed by extracting `LiveAssembly.toolLoop(...)` — the one
place a live loop is constructed — and reading `loop.resultBytes()` off the built
object.

**The search flag mutation is the second.** Dropping `byteTruncated` from
`search`'s return leaves the body already capped, so every byte-length assertion still
passed while `truncated()` reported **false for a visibly cut result**. My first test
also could not see it, because it only used inputs that tripped the 100-*match* cap, so
`truncated()` was already true for unrelated reasons. The distinguishing input is a
result **under** the match cap but **over** the byte cap — where the byte cap is the
sole reason for truncation. Asserting the FLAG, not just the bytes and the marker, is
what caught it.

### One survivor I chose not to force

"helper returns 0 when the key is absent" survived, and I traced why rather than
adding a test: `ConfigLoader` materialises `resultBytes` with `asInt(65536)`, so a
loader-built config **never** yields null. The branch is reachable only from a
hand-built `ToolsConfig`. Writing a test for it would require constructing an
impossible configuration to prove a guard on a path nothing takes; I documented the
reason in the code instead. Recorded here so the survivor is visible rather than
quietly dropped.

### 470 tests green (163 core / 14 openrouter / 30 systemone / 263 cli)

### The stale-register finding, which is the real lesson

Five "still open" lines were carried across five increments without being checked, and
two were already fixed. Register hygiene is now a standing check: **before working any
item a register lists, verify it is still open.** A status list that nobody re-verifies
is a list of beliefs, not of facts — and I had been reasoning from it as if it were
evidence, which is the failure this whole audit exists to catch.



---

## AUDIT-2026-10-03-q — the register's own "Open" table, audited against source

Last increment's lesson was to verify every item a status list names before working it.
Two of five were stale. This increment applies that to the register's **top table** —
"Open — verified real, deliberately not fixed" — row by row, against source rather than
against my own note. The table heading claims its rows were "confirmed". Two were not
open at all.

### Two rows were already fixed and still listed as open

| Row | Claimed | Actual |
|---|---|---|
| **F-9** | `tools.resultBytes` is inert; `WorkspaceTools` uses its own `MAX_READ_BYTES` | FIXED in AUDIT-p (`0e130e9`) — same increment that made me notice |
| **F-11** | `confidenceField` documented, plumbed, never read | FIXED in AUDIT-e; `RouteResolver.java:99` reads it via `confidenceOf(c, in.confidenceField())` |

Corrected to FIXED with their commits. A table headed "verified real" that had not been
re-verified for two increments was exactly the belief-not-evidence failure.

### C-2 was worse than recorded — and a test was enforcing it

C-2 said `CompactionPlanner` "fails **open** on a typo'd label" and deferred it as "copy
`TaskClass.fromLabel`, with a test". The spec makes it mandatory, not optional:

> **systemone.md:40** — "Compaction-policy failure defaults to concise if
> compaction is feasible/required; otherwise defer or stop by deterministic fit."
> **runtime.md:29** — "At 80% of usable context, ask System One for defer/concise/detailed."

The code failed open **twice**, and the javadoc claimed the opposite of its own behaviour:

- `"concise"` and `"detailed"` matched a bare `switch` on the raw label; **everything
  else fell into the same branch as a real `defer`**. So `"CONCISE"`, `"verbos"` and a
  genuine `defer` were indistinguishable.
- `decision.isEmpty()` — the shape an **unreachable engine** produces, because
  `CompactionPolicyDecider` catches the `RuntimeException` and passes `Optional.empty()`
  — left `requested = Policy.DEFER`.
- The javadoc said "a failed or **absent** answer defaults to CONCISE (fail closed)". It
  did not. My own comment described the fix that was missing.

**Why the absent case is the damaging one.** This consult runs *only* at
`LiveTurnDriver.java:376`, `if (pressure >= 0.80)`. So an unreachable decision engine
printed `compaction: policy=defer pressure=0.85 — defer: context fits; nothing compacted`
— at exactly the moment the caller had measured the context as over the trigger. And the
deterministic fit check could not rescue it: that check promotes DEFER to CONCISE only
when the next request **cannot** fit, so any comfortable allowance left it deferring.

### The fix

`Policy.fromLabel(String)` — case-insensitive, trimmed, closed vocabulary, **CONCISE** for
anything unrecognised, mirroring `TaskClass.fromLabel` on the same wire. And
`requested` now starts at `CONCISE`, so every non-answer fails closed: absent, a failure,
an unusable label, and a null label. DEFER is now reachable **only** from an explicit
label that passed the fit check.

The comment on the enum records why an unrecognised label is a *failure* and not a
defer: the labels arrive as free text from a model, so refusing `"CONCISE"` would be the
same defect at a new scale.

### A test I had to correct — it was enforcing the defect

`CompactionPolicyDeciderTest.transportFailureDelegatesToDeterministic` asserted **DEFER**,
with the message *"without a consultation nothing is compacted"*. Its sibling 20 lines
up asserted CONCISE for a failed *answer*. **Two tests in one class disagreed about one
rule**, which is only possible when one was pinned to the implementation instead of the
spec.

I corrected it against `systemone.md:40`, not against its sibling, and kept the
reasoning in the test. Worth recording as a pattern: a test can be wrong in the same
direction as the code it was written beside, and a green suite will not tell you.

### Proving it at the call site, not just in the helper

`CompactionPolicyDecisionTest` and `CompactionPolicyDeciderTest` both passed while the
bug lived. They cannot prove the **driver** reaches the planner with a pressured context
— the exact gap AUDIT-p taught me to look for: a correct helper the production call site
never uses.

`CompactionTriggerWiringTest` drives the real `LiveTurnDriver` with
`"maxPromptTokens": 40` (the estimator is `bytes/3 + messages`, so the prompt alone
exceeds 32 tokens and the trigger fires on the first turn, with no history to build up)
and an engine whose `askAll` throws. It asserts on what the **operator was told**.

Six driver mutations, all caught:

| Mutation | Result |
|---|---|
| planner: absent → DEFER (the original defect) | CAUGHT |
| decider fabricates a `defer` answer instead of consulting | CAUGHT |
| driver never reaches the consult (`if (false)`) | CAUGHT |
| trigger threshold raised to 1.01 | CAUGHT |
| compaction line suppressed | CAUGHT |
| **pressure computed as 0.0** | CAUGHT |

The pressure mutation matters most: without it the test could pass because pressure
happened to be high for an unrelated reason — the AUDIT-p lesson about inputs tripping a
*different* limit, applied to reachability instead of truncation.

Two of my first six anchors SKIPped. That was my anchor strings, not test weakness —
`LiveTurnDriver` indents that block 16 spaces, not 18. Re-run with the real text.

### Two survivors I chased down rather than wrote off

1. **`fromLabel(null) → DEFER` survived.** Every production constructor avoids null (the
   adapter reads `asText("")`; so does `ReplayCapture`), so the branch is not exercised
   today — but `ValidChoice` is a **public record on the decision port**, and
   `new ValidChoice("q", null, ...)` compiles for any future implementor. Pinned with a
   test, since a null that silently means "do not compact" is the worse default.

2. **Deleting the DEFER note survived.** Investigated rather than accepted, and the
   reason was instructive: deleting the arm does not compile — the switch is exhaustive
   over an enum — so the "survivor" was an **invalid mutation**. The compile-clean version
   (`case DEFER -> null`) fails two tests. Recording this because my mutation runner
   counted a compile error as zero failures, which is exactly how a battery can look
   complete while proving nothing. I now check for `COMPILATION ERROR` before reading a
   mutation result.

Also caught: the wrong CONCISE cap in the note (`512` is in `context.md:36`, so the note
must carry it), notes collapsed to one string, and `answered` lying in either direction.

### 478 tests green (168 core / 14 openrouter / 30 systemone / 266 cli)

### Still open after this sweep

- **F-6** `confidenceFloor` has two defaults (`ConfigLoader` 0.65, `ActiveRouter` 0.0).
  Confirmed still open, and still unreachable today. Genuinely an operator decision.
- **F-7** `Question` is now `sealed` (`DecisionEngine.java:116`), but the row says it is
  not. My row was written before that change and never re-read.
- **F-8** all transport failures collapse to `TIMEOUT` (`SystemOneHttpAdapter:123`).
  Still open; needs a new `FailureKind`.
- **C-1** cost rounding to a settled zero, **C-3** `estimateTokens` clamping (which also
  caps `pressure` at 1.0 — noted here because this increment touches that value).
- **C-2** is now FIXED. F-9 and F-11 are now FIXED.


---

## AUDIT-2026-10-03-r — C-3: the token estimate was a clamped measurement

Raised in increment q's notes as a curiosity ("`estimateTokens` clamps, which also caps
`pressure` at 1.0"). It was not a curiosity. It was the same shape as q: **a real signal
reported as a comfortable one, at a point where the system is deciding whether to compact.**

### The clamp

`PromptAssembler.estimateTokens` returned `Math.min(tokens, allowance)`. Every input over
budget therefore reported *exactly* the allowance. Consequences, none of them visible in
the code that used the value:

- **`LiveTurnDriver`'s `Math.min(1.0, estimated / allowance)` was unreachable, not
  defensive.** The numerator could never exceed the denominator, so pressure could not
  exceed 1.0 *in principle*. A clamp written to look like a safety bound was doing nothing.
- **A context 40x over budget and a context exactly at budget produced the same
  pressure**, so the same trace, the same operator line, and the same trigger input.
- **`CompactionPlanner` passed `Integer.MAX_VALUE`** purely to escape the clamp for its
  own fit check. The real number was reachable only by asking for a nonsense allowance —
  the clearest admission that the API was wrong.

### The fix, and why the bound moved rather than disappeared

`estimateTokens` no longer takes an allowance: a measurement must not change because an
unrelated config value changed. The [0,1] bound did not vanish — it **moved to the port
that states it**. `DecisionEngine.State` rejects `contextPressure` outside [0,1], so:

- `ContextPlan.pressure()` — the honest ratio, may exceed 1.0.
- `ContextPlan.boundedPressure()` — saturates at 1.0, used only for the port.

`ContextPlan` also gained a non-positive-allowance guard, because `pressure()` divides by
that field and `assemble()`'s own guard does not protect a direct construction of a
**public record**.

### A second defect the mutation battery found, which reading had not

**`CompactionPolicyDecider` hardcoded `0.0` into the compaction dispatch.** This consult
runs *only* when the caller measured `pressure >= 0.80`, and it told the decision port
`contextPressure = 0.0` while its own request view said *"estimated context pressure is
above the 80% trigger"*. The engine was informed the context was empty at the moment it
was asked to compact it — and the typed field is what a model weights most, so any
reasoning over it argued for `defer`.

Its javadoc also still claimed a transport failure compacts nothing, which stopped being
true in increment q. Corrected.

Neither was found by reading the code. Both were found by mutating the value and asking
what the port received.

### 14 mutations, all caught

| Mutation | Result |
|---|---|
| estimator re-clamped to the allowance (the original defect) | CAUGHT |
| estimator returns a constant | CAUGHT |
| estimator under-reports 3x | CAUGHT |
| framing overhead dropped | CAUGHT |
| `pressure()` clamps to 1.0 (symptom, not cause) | CAUGHT |
| `boundedPressure()` stops saturating | CAUGHT |
| `ContextPlan` allowance guard removed | CAUGHT |
| driver clamps pressure before the trigger | CAUGHT |
| driver hands UNBOUNDED pressure to the port | CAUGHT |
| compaction consult not given the real pressure | CAUGHT |
| compaction consult hardcodes 0.0 (**the new find**) | CAUGHT |
| compaction consult drops the clamp | CAUGHT |
| planner fit check inverted | CAUGHT |
| planner estimate scaled | CAUGHT |

Then two more that only the wiring test could see:

| Mutation | Result |
|---|---|
| compaction consult given `0.5` instead of the real pressure | CAUGHT |
| compaction consult given `0.0` at the call site | CAUGHT |

### Three survivors I chased instead of writing off

1. **"Allowance guard removed" survived.** Not a weak test — `assemble()` has its own
   guard, so the test proved nothing about the record. Added
   `contextPlanRefusesNonPositiveAllowanceDirectly`, which constructs the record. Now caught.

2. **"Driver hands UNBOUNDED pressure to the port" survived twice.** My first fix asserted
   `ProfileDecider` receives a legal value — but it passed a literal `1.0`, which is in
   range *by construction*, so it could never fail. The defect was in the **driver's call**,
   and the driver's `catch (RuntimeException)` turned the port's rejection into
   `profile: unavailable`, silently disabling the profile decision on exactly the
   over-budget turns where it matters. Rewritten to drive the real driver with a recording
   engine.

3. **"Compaction consult given 0.0 at the call site" survived.** My compaction test proved
   the dispatch was *in range*; it did not prove it was *the right number*. In-range-but-
   wrong is precisely what a bounds check cannot see. The follow-up mutation — `0.5`
   instead of the real value — is what proves the new test checks the value and not the
   bound.

Worth generalising: **a bounds assertion and a value assertion are different tests.** The
first mutation battery here was 13/14 and would have shipped a driver that reported a
flat `0.00` to the compaction engine.

### A limit on the packaged-binary proof, stated rather than glossed

I tried to demonstrate the trigger through `rahu-cli.jar`. It cannot be done. Offline mode
never reaches the trigger, and live mode requires a real generation adapter plus a key
(`live wiring failed: unknown generation adapter "fake"; expected openrouter`). So the
`pressure` behaviour is proven at the **driver level with the real `LiveTurnDriver`** and
not through the shipped binary. Recorded so a later reader does not assume binary coverage
that does not exist.

### 488 tests green (172 core / 14 openrouter / 30 systemone / 272 cli)

### Also closed this increment

- `examples/offline.json` verified loadable; the packaged binary still runs offline turns
  cleanly end to end.
- **F-7 is corrected in the register**: the row claimed `Question` is unsealed; it has been
  `sealed` since `DecisionEngine.java:116`. Stale for as long as F-9 and F-11 were, and
  found only because the whole table was re-read against source.


---

## AUDIT-2026-10-03-s — a typo one level down loaded clean

I went to re-audit the Open table against source, as increment `r` did for F-7. The
row I expected to check — "five accepted-and-ignored fields remain" — turned out to be
**wrong about its own contents**: it said five, named four, and one of the four is the
key the same table reports as fixed. So before fixing the register I checked what the
loader actually accepts.

### The defect

`ConfigLoader.bind` validated the **root object only**, against `TOP_KEYS`:

```java
if (!TOP_KEYS.contains(name)) { unknown.add(name); }
```

and then handed each section to a `bind*` method that read the keys it knew and
**ignored everything else**. There was no second check at any depth. Verified through
the packaged binary — all of these loaded with exit 0 and ran a turn:

```json
"privacy": {"mode": "strict", "onUnkown": "block"}
"trace":   {"directory": ".rahu/runs", "onFailuree": "stop"}
"pools":   {"demo": {"modelz": [], "models": [...]}}
```

**All 14 sections accepted a misspelled key.** Not one invented name needed to be real —
any garbage works.

### Why it matters more than an ordinary typo

`privacy.onUnkown` leaves `privacy.onUnknown` at its built-in default. That default
happens to be `block`, so the run is **safe by luck rather than by configuration** — and
the operator's file describes a system that was never configured. configuration.md
requires this explicitly: *"reject other values and unknown privacy keys."* Nothing
rejected them.

This is the register's central pattern — *a capability that was configured but which the
code did not implement* — in its worst form, because it does not require a real key to
go wrong.

### And the reason it survived

**A15 tests exactly this, one level too high.** `ConfigLoaderTest.unknownKeyFailsWithFieldPath`
proves `unknownTop` is refused, and a reader reasonably concludes unknown keys are
refused. The check exists, is tested, and is green — at the only depth anyone looked.

### The fix

A `SECTION_KEYS` map (14 sections) plus `POOL_KEYS` and `POOL_MODEL_KEYS`, enforced by
`rejectUnknownSectionKeys` before any binder runs. Absent optional sections are skipped
rather than demanded, since `injection` and `search` are documented as optional and an
absent block must stay absent.

Each allowlist is the union of what its binder reads, so **adding a key to a `bind*`
method without adding it here fails `ConfigKeyReachabilityTest`** — the two cannot drift.

Verified through the shipped binary, with full paths:

```
privacy.onUnkown  → config invalid: unknown config key "privacy.onUnkown"; remove it
                    or fix the spelling (configuration.md field table)
pools.demo.modelz → ... "pools.demo.modelz" ...
```

### A fourth source of truth, found while checking the fourth

Three places name the key set: `configuration.md`'s table, `SchemaGenerator`'s hardcoded
schema string, and the loader. Nothing compared them. The new
`loaderAndSchemaAgreeOnEveryKey` walks the generated schema and requires the loader to
recognise every key it documents — so the schema can no longer offer a key the loader
refuses. It passes, which means my allowlist and the documented schema agree.

Two schema keys remain unread by any binder — `agent.maxCostUsd` and
`session.maxCostUsd` looked like inert money guards, and I flagged them — **but both ARE
read, via a `money(n, "maxCostUsd", ...)` helper my first extraction regex missed.** I
checked before writing it up. Recording that because I nearly filed a false finding, and
a grep-shaped mistake here would have been embarrassing and wrong.

### 5 mutations, all caught

| Mutation | Result |
|---|---|
| section check removed entirely | CAUGHT |
| only the root check kept (**the original defect**) | CAUGHT |
| `privacy` loses `onUnknown` from its allowlist | CAUGHT |
| error message drops the section path | CAUGHT |
| pools check removed | CAUGHT |

### 493 tests green (172 core / 14 openrouter / 30 systemone / 277 cli)

### Mistakes of my own, since three of them cost real time

- **A brace-balance checker that counted braces inside string literals** told me
  `withKey` never closed and that a method was missing. Both were false; my checker was
  wrong, not the file. It cost me several cycles chasing a phantom.
- **A `String.replace`-based test that could not fail.** The pools test "passed" a
  pool-level typo while its model-level replacement silently did not match — an
  unmodified config correctly loads, so the assertion was vacuous. Rewritten to mutate a
  parsed tree, which also removed a fragile text-block-indentation dependency.
- **A regex backtrack that hung the kernel for 300s** (catastrophic nesting on
  `Set.of(...)` inside a method-body scan), and a **blanket string replace that mangled an
  import** into `import JsonNode;` and briefly the package line. Four compile cycles I
  should not have spent. I am now editing scoped to a line range or a unique anchor
  rather than whole-file substitution.

### The lesson, which generalises past this repo

**A check at one level invites the assumption it applies at all levels.** A15 made the
config loader look validated. The next question is never "is there a check?" but "at
which depths, and is each one tested?"


---

## AUDIT-2026-10-03-t — F-8: every transport fault claimed to be a timeout

Recorded-not-fixed in review 016 as needing "an enum addition and a spec change". This
increment is that addition, and the review's stated reason for it was correct.

### The defect

`SystemOneHttpAdapter` caught `IOException | IllegalArgumentException` and returned
`FailureKind.TIMEOUT` for all of them. That merged two different facts:

| Fault | The service... | Retry safe? |
|---|---|---|
| connection refused / DNS unresolvable / **connect** timeout | provably never saw the request | yes |
| read timeout | **may have processed it, and billed for it** | no |

`HttpTimeoutException` and `HttpConnectTimeoutException` are the *same hierarchy* — the
connect variant extends the timeout variant. Reporting both as `TIMEOUT` destroyed the
only distinction a retry needs.

### This was a spec violation, not a style question

`systemone.md:48` already mandated it: *"Uncertain transport outcomes remain traceable
even if the decision service is nominally side-effect-free because billing may have
occurred."* The adapter merged the uncertain case with the certain one. The spec was
right and the code was wrong.

**And my earlier register claim about this finding was itself wrong.** I had written that
"the ledger handles ambiguity via `markUncertain` so cost stays sound — only the
diagnostic is coarse". I checked before relying on it: **the decision port has no ledger
at all.** `grep` for `ledger.(reserve|settle|markUncertain)` in `rahu-cli/src/main`
returns nothing, and the reservation in `LiveTurnDriver.account` covers *generation*
usage only. So nothing downstream re-derives this distinction for decisions; the
boundary is the only place it can exist. The claim was carried over from the generation
path and did not survive checking.

### The fix

`UNREACHABLE` (provably undelivered) and `AMBIGUOUS` (may have been delivered),
classified by exception type. The unrecognised-fault catch-all returns `AMBIGUOUS`
deliberately: an unproven delivery is not a proven non-delivery. `systemone.md` updated
to state the rule, since it is now contract rather than accident.

`safeReason` names the exception **type**, never its text — verified in `jshell` that
`UnknownHostException.getMessage()` embeds the hostname
(`"no-such-host.invalid: nodename nor servename..."`), so echoing `getMessage()` would
leak the endpoint into model-visible output.

### Catch order is load-bearing, and it is pinned

`HttpConnectTimeoutException` extends `HttpTimeoutException`. Testing the supertype
first silently reclassifies every connect timeout as ambiguous — the exact confusion the
enum was added to remove. A mutation test pins the order.

### Also fixed: a `Failure` decision was never covered by any test

`ReplayCapture.decodeDecision` read `FailureKind.valueOf(...)` bare. **No test in the
repo ever wrote a `Failure` decision to a capture**, so the branch was untested. A
capture from a newer build (or hand-edited) threw `IllegalArgumentException` out of a
decode path whose every other failure is reported as a replay error — an operator gets a
stack trace instead of a diagnosis. Now degrades to `UNKNOWN`, with three tests including
a mutation check that restores the bare `valueOf` and confirms the crash returns.

### 6 mutations, all caught

| Mutation | Result |
|---|---|
| collapse to one `TIMEOUT` (**the original defect**) | CAUGHT (F=3) |
| supertype tested before `HttpConnectTimeoutException` | CAUGHT (F=3) |
| catch-all optimistically claims never-sent | CAUGHT (F=1) |
| definite branch lost | CAUGHT (F=4) |
| `safeReason` echoes raw message (leaks host) | CAUGHT (F=1) |
| `safeReason` blank | CAUGHT (F=1) |

### The failure that mattered: my mutation harness reported 6/6 SURVIVED

The tests were green and the first mutation run said **every mutation survived** —
including the original defect. I nearly concluded the code was untestable.

The tests were fine. **My harness was broken**: it tested
`"Failures: 0, Errors: 0" in output`, and surefire prints its summary line twice —
once as `[INFO] Tests run: 4, Failures: 3, Errors: 0` — so the substring
`Errors: 0` matched a **failing** run. Six mutations, six false "survived".

Parsing the totals line explicitly instead of substring-matching turned 6/6 survived
into 6/6 caught. I then found two genuine survivors hidden underneath (an untested
catch-all branch and a vacuous leak assertion) and fixed both.

This is the second increment in a row where a green test suite was not evidence.
**A test result you have not seen fail is not a passing test** — and neither is a
mutation result produced by a harness you have not seen catch something.


---

## AUDIT-2026-10-03-u — C-1: a billed cost the ledger could not hold became a confident zero

The register's headline for this row was right. Its stated *reason* was wrong, and
finding that out is most of the work.

### The real defect

`OpenRouterProvider.parseCostMicros` did `Math.round(cost.asDouble() * 1_000_000.0)`.
A **positive** reported cost below half a micro becomes `0`, and it returned
`Optional.of(0L)` — *present*.

`LiveTurnDriver.account` branches on presence, not value:

```java
var micros = usage.totalCostMicrosOpt();
if (micros.isPresent()) { ...settle(..., true); } else { ...markUncertain(...); }
```

So a sub-microdollar bill took the **settle** branch as `$0.000000`. That is the
precise outcome `TurnOutcome.costUnobserved` exists to prevent — its own comment says
*"a caller that renders this as '$0.00' is asserting the provider billed nothing."*
The provider billed something. We simply cannot hold it at micro resolution, so the
honest state is the ledger's existing uncertain liability.

### The rationale in my own register was wrong, and I checked before relying on it

The row (and review 017) said the `double` *"destroys the exact-decimal guarantee at
the boundary where money enters."* I searched for a disagreement before accepting that:

| probe | result |
|---|---|
| 400k random dollar values, 6–12 decimal places | **0 disagreements** |
| half-micro boundary, 0.4999999999999999999, 0.5 | agree |
| 0.123456789012345678, 123456789.123456789 | agree |

`asDouble()` then `Math.round` agrees with exact decimal over this range. The claim
was reasoning by vibe, and it was retracted rather than quietly fixed. **The defect was
never the arithmetic. It was the semantics of zero.**

### The genuine precision bug was one layer down, and my first fix missed it

I converted via `cost.decimalValue()` and wrote a test asserting exact conversion at
`9223372036854.775807`. It **failed**, returning `9223372036854775000`.

`decimalValue()` on a Jackson `DoubleNode` returns the decimal of the *already-rounded
double*, so "convert through BigDecimal" was cosmetic. Jackson binds an untyped JSON
float to `double` by default, and this repo never enabled
`USE_BIG_DECIMAL_FOR_FLOATS` anywhere. Fixed with a second mapper used **only for
reading provider responses** (the request mapper is untouched — nothing we send needs
decimal fidelity). A mutation that disables the feature is now caught.

### The wiring had no test at all

`LiveTurnDriver.account` is the only place a reported cost becomes a ledger outcome,
and both pre-existing `CostGateTest` cases used a funded allowance with a 500-micro
cost. **The uncertain branch was never taken by any test.** That is precisely how a
sub-microdollar cost could settle a confident zero with a fully green suite. Three
tests now drive a real turn and assert `settled` / `uncertain` / `reserved` — including
that an uncertain reservation is no longer `reserved`, which would otherwise
double-count against the allowance.

### 11 mutations, all caught

| Mutation | Result |
|---|---|
| original: `asDouble` + `Math.round`, no guards | CAUGHT (F=2) |
| sub-micro settles a confident zero | CAUGHT (F=1) |
| exact `0.0` also becomes unknown | CAUGHT (E=1) |
| truncate instead of HALF_UP | CAUGHT (F=1 E=1) |
| negative cost accepted | CAUGHT (F=1) |
| no overflow guard | CAUGHT (E=1) |
| off-by-one at the Long.MAX boundary | CAUGHT (E=1) |
| mapper reads floats as double | CAUGHT (F=1) |
| **`account`**: absent cost settles a confident zero | CAUGHT (F=1) |
| **`account`**: uncertain branch removed | CAUGHT (F=1) |
| **`account`**: always uncertain, even when reported | CAUGHT (F=2) |

Two of these (negative cost, overflow) were **genuine survivors** on the first pass —
the overflow case wraps to a *negative* long, which would read as a credit and could
pass a cost gate. The `Long.MAX_VALUE` boundary test was also my own error: I asserted
the wrong expectation until I traced the failure to Jackson's double binding.

### The failure that mattered: a mutation that would not compile

My first attempt at the "revert to the original" mutation **did not compile**, and my
harness reported it as `COMPILE-ERROR` — a label I had added, so it did not silently
pass. But I nearly read a compile error as a caught defect. A mutation that fails to
build is **not evidence**; it is an invalid experiment. I rewrote it as the verbatim
original method body, which compiles and is caught (F=2).

**And two `str.replace` edits in the test file silently did not match** — whitespace
mismatch — leaving the file half-patched. The compile errors looked like a missing
import; the real cause was that two of my five edits had never applied. Asserting that
each edit landed, before compiling, is what exposed it.

512 tests green (172 core / 21 openrouter / 36 systemone / 283 cli)


---

## AUDIT-2026-10-03-v — C-3 re-verified, and my own task state was wrong about it

I picked C-3 as the next increment from my task-progress record, which listed it as
**open**. It was fixed six commits ago in `9bb0078` (AUDIT-2026-10-03-r). The register
row was still unstruck, and I had carried the stale "open" status forward instead of
re-reading the code. **The lesson is about my bookkeeping, not the code**: the fix was
there, and I nearly spent the increment assuming otherwise.

Rather than declare victory off the commit message and the doc comment — both of which
assert the fix works — I re-verified the whole clamp chain:

| site | clamp | mutation | result |
|---|---|---|---|
| `PromptAssembler.estimateTokens` | none, by design | re-clamp to allowance, plus `CompactionPlanner` re-passing `Integer.MAX_VALUE` (the ORIGINAL defect, restored in full) | **CAUGHT (F=1 of 8)** |
| `ContextPlan.boundedPressure()` | `Math.min(1.0, pressure())` | stop saturating | **CAUGHT (F=2)** |
| `CompactionPolicyDecider.consult` | inline `Math.min(1.0, Math.max(0.0, …))` | clamp removed | **CAUGHT (F=4 E=2)** |
| `LiveTurnDriver` → `ProfileDecider` | `plan.boundedPressure()` | hand it the raw `plan.pressure()` | **CAUGHT (F=2)** |

So the measurement is unclamped, and every port that requires a bound has one. The two
ports clamp by *different* mechanisms — a named method and an inline expression — and
both are individually mutation-covered, which is the part a "the fix is in" reading would
have missed.

### The interesting gap: `ProfileDecider` has no clamp of its own

It passes `contextPressure` straight into `DecisionEngine.State`, which **rejects**
anything outside `[0,1]`. That is safe only because the driver hands it
`boundedPressure()`. Nothing inside `ProfileDecider` protects itself, and the driver
wraps the profile consult in a catch-all `RuntimeException`, so the failure mode is not
a crash — it is `"profile: unavailable"` printed to stderr and the profile decision
silently disabled **on precisely the over-budget turns where context pressure is the
signal**. `CompactionTriggerWiringTest` already pins that the line does not appear; that
test is the only reason this is covered at all.

### Two invalid experiments, caught by the harness labels

Both of my first mutation attempts **did not compile**, and were labelled
`INVALID-EXPERIMENT` rather than counted:

- re-adding a clamp to the estimator, because the method no longer takes an allowance;
- a mutation anchored on `boundedPressure()` inside `ProfileDecider`, which never
  contained that string — I had assumed the decider clamped, and it does not.

And one **false "pass"**: running Maven with `-q` returned `NO-SUMMARY` for the restored
tree, which I could have reported as green. Without `-q` it is `SURVIVED (27 tests ran)`.
`NO-SUMMARY` is not a pass; a run that proves nothing must be reported as proving nothing.

512 tests green.
