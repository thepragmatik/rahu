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
