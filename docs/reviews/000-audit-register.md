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
| F-7 | MED | `Question` is not `sealed`. A new question type compiles unregistered and fails at `questionId()` — loud, but late. | Breaking change to a port with one implementation and no second implementor. |
| F-8 | MED | All transport failures collapse to `TIMEOUT`. A read timeout (ambiguous) is indistinguishable from a connect failure (definite). The ledger handles the ambiguity correctly via `markUncertain`, so cost is sound and only the diagnostic is coarse. | Would need a new `FailureKind`. |
| F-9 | MED | `tools.resultBytes` is inert, same shape as F-4. `WorkspaceTools` uses its own `MAX_READ_BYTES`. | A behaviour change to tool output bounds; deserves its own proof. |
| F-11 | HIGH | `confidenceField` is documented, plumbed and never read — F-4's shape, on the routing path. | Bundle with the sweep below rather than as an isolated change. |
| C-1 | MED | A sub-microdollar cost rounds to a settled **zero** instead of settling as uncertain. The only `double` in any cost path; it destroys the exact-decimal guarantee at the boundary where money enters. | Cost-path change; needs its own ledger proof. |
| C-2 | MED | `CompactionPlanner` fails **open** on a typo'd label — `"CONCISE"` and `"verbos"` both map to `DEFER`. The javadoc claims absent defaults to CONCISE; it does not. At high context pressure context is silently never compacted. | `TaskClass.fromLabel` already does it correctly — copy, with a test. |
| C-3 | MED | `estimateTokens` silently clamps to the allowance it was passed, so one input yields different answers depending on the cap. `ContextPlan.estimatedTokens` then reports a clamped allowance as a measurement. | An estimate presented as a measurement; needs a rename or an unclamped method. |
| — | MED | Five accepted-and-ignored fields remain: `routing.confidenceField`, `decision.confidenceSemantics`, `OperationRequirements.isTextAnswer`, `.structuredOutputRequired`. | See the sweep below. |
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
