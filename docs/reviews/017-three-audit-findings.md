# Audit findings register

Three read-only adversarial audits ran against `dcfc0e1` (2026-10-02). This file is
the honest ledger of what they claimed, what I independently verified, and what is
still open. It exists because two of the three reports contained claims that were
already fixed when they arrived — a review process that publishes only its hits
cannot itself be reviewed.

Rule applied throughout: a finding is only real if I read the cited file at HEAD
myself. Line numbers from a report are a claim, not evidence.

## Fixed in `58c63d7`

| # | Sev | Finding | Verified by |
|---|---|---|---|
| 1 | HIGH | `tryReserve` used `BigDecimal.max(reserved, uncertain)` instead of adding both, so the smaller bucket vanished from admission. **My own regression, from `f97d4d9`.** My original test was blind to it: it used equal buckets, where max and add agree. | Read `Ledger.java:58`; test `LedgerHonestyTest.reservedAndUncertainBothCount` |
| 2 | HIGH | A failed generation never settled its pre-dispatch reservation — leaked in `open` forever, counted as neither settled nor uncertain. A read timeout is exactly the "probably billed" case. | Read `LiveTurnDriver.java:233-237`; fixed in `account()` on the failure path |
| 3 | HIGH | `Ledger.settle(..., boolean successful)` never read the boolean; a failure booked a confident settled charge. | Read `Ledger.java:81-93`; now routes to `markUncertain` |
| 4 | HIGH | `remaining()` threw `IllegalArgumentException` on overshoot, crashing `/status` — the one command an operator runs when money is wrong. | Read `Ledger.java:176-181` + `MoneyAmount.java:15-17`; found independently by audits 1 and 2 |
| 5 | HIGH | `ToolLoop.observationOf` read `content` (always `""`) instead of `safeReason`, so a safety refusal reached the model as bare `invalid: `. | Read `ToolLoop.java:343-348` + `ToolResult.java:23-33`; red output was literally `invalid: ` |
| 6 | MED | `WorkspaceTools.read` returned SUCCESS+empty for out-of-range/inverted ranges — byte-identical to an empty file. | Read `WorkspaceTools.java:112-114` |

## Verified real, deliberately NOT fixed this batch

Each is real and confirmed. None is a quick fix; each needs its own slice.

- **`NoProgressDetector` is unreachable** (audits 1 and 2, independently). The
  charter promises no-progress detection; `ToolLoop` loops on a step cap only. A model
  emitting an identical batch forever burns `maxCallsPerStep` paid generations and
  exits as `INVALID_REQUEST`, never `NO_PROGRESS`. The step cap *is* a real bound, so
  the loop is bounded — the missing thing is cheaper, earlier termination.
  Fix: hold a detector per turn, feed it each completed batch, return a typed
  `NO_PROGRESS`. `TerminalReason.NO_PROGRESS` is currently a zero-producer constant.

- **`AdmissionPipeline` is wired into nothing** (audit 1). Its javadoc claims "every
  operation goes through this"; `LiveTurnDriver` hand-rolls a different order. Same
  class as the old loopback defect (a rule expressed twice) but inverted: the *tested*
  copy is the dead one. Either route the driver through it, or delete it and amend
  `safety.md`. Leaving three documents asserting a path no code takes is the actual
  defect.

- **The registered `Tool` executor is dead** (audit 1). `Tool.execute` is never called
  from `src/main`; `WorkspaceTools.invoke` is a hardcoded switch. A compiled-in
  extension tool would be advertised to the model and then return `invalid:` — an
  advertised lie, and the exact extension proof `extensibility.md` claims. Proven by
  the auditor with a throwaway test (`executorInvocations=0`).

- **`CONTEXT_LIMIT` is a zero-producer enum constant**; compaction is not reachable
  from a context-size failure (audit 2). `CompactionPlanner.applySummary` and
  `decideForContextOnlyExclusion` are test-only, and `LiveTurnDriver` *prints* the
  compaction policy without executing it. `build-status.md` records summarisation as
  deliberately incomplete, so this is a known gap rather than a hidden one.

- **Cost enters through `double`** (audit 2). `parseCostMicros` does
  `cost.asDouble()` then `Math.round(dollars * 1e6)`, destroying the exact-decimal
  guarantee at the one boundary where money enters, then re-wraps as
  `BigDecimal.valueOf(micros, 6)`. Sub-microdollar costs round to a settled **zero**
  rather than settling as uncertain. Verified this is the only `double` in any cost
  path. Fix: `new BigDecimal(cost.asText())`, `RoundingMode.HALF_UP`, return null
  below one micro.

- **`CompactionPlanner` fails open on a typo'd label** (audit 3). `"CONCISE"` and
  `"verbos"` both map to `DEFER` — indistinguishable from no decision. The javadoc
  claims "absent defaults to CONCISE (fail closed)"; absent does not, so at high
  context pressure context is silently never compacted. `TaskClass.fromLabel` already
  does this correctly — copy that.

- **`estimateTokens` silently clamps to the allowance** (audit 3). A method named
  "estimate" returns "the estimate, capped at a number you also passed in", so the
  same input yields wildly different answers. `ContextPlan.estimatedTokens` then
  reports a clamped allowance as an estimate — an estimate presented as a
  measurement. The `Integer.MAX_VALUE` workaround at the call site is the tell.

- **Three accepted-and-ignored fields** (audit 3): `RouteResolver.confidenceField`,
  `DecisionResult.confidenceSemantics`, and `OperationRequirements.isTextAnswer` /
  `.structuredOutputRequired` are parsed, validated, schema-documented, and never
  read. The project already named this defect class once for `tools.enabled` and
  never swept for it again. An operator who sets `routing.confidenceField` gets no
  error and no effect.

## Stale or misleading claims (the reason this file exists)

| Claim | Verdict | Reality |
|---|---|---|
| Loopback prefix check is a live fail-open | **Stale** | Fixed at `4002166`; the report read pre-fix code |
| Rerank test passes vacuously | **Stale** | Fixed at `4067ea0`; the reviewer caught itself reading a pre-fix file |
| `DecisionResult` is sealed | **Misleading** | True, but no `permits` clause and every consumer uses `instanceof`, so nothing is enforced |
| Search skip marker is missing | **Overstated** | A real gap, but the marker alone was the weaker fix; the flag was correct |
| `Ledger` defects (child leak, settled not counting) | **Fixed mid-audit** | `dcfc0e1` landed while audits ran; audit 3 explicitly noted its own H1 symptom was fixed by that commit |

## Verified-clean categories (negative findings are results)

These were checked properly and are **not** defects. Recorded so a future audit does
not re-derive them:

- **Package cycles in `rahu-core`: none.** Measured import graph is acyclic. The only
  cycle in the repo is `rahu.cli ⇄ rahu.cli.live` — a design tension at this size, not
  a defect.
- **Charter clause 1 (adapter JSON/HTTP out of core): holds.** Core declares exactly
  one dependency, `junit-jupiter`. Zero regex hits for `jackson|ObjectMapper|
  HttpClient|java.net.http` across all 74 core main files.
- **Charter clause 2 (decisions cannot grant permissions): holds on every live path.**
  `ToolRelevanceGate`, `RouteResolver` and `ToolRegistry.restrictedTo` can only narrow
  or throw. No path widens authority.
- **Charter clause 4 (joint candidate construction): holds.** An impossible
  model/effort pair is unrepresentable, not merely filtered.
- **Charter clause 3 (fallbacks cannot widen the pool): holds.** No generation-side
  retry exists at all; the port contract forbids it.
- **Concurrency / structured concurrency: clean, and vacuously so.** Zero thread
  spawns anywhere in the four modules. Nothing can outlive a parent, so "no unscoped
  child work" is satisfied trivially.
- **Interruption preservation: clean.** Every blocking call re-sets the interrupt flag
  *before* returning and maps it to a distinct failure kind.
- **Allowance forking/resetting: clean.** `/reset` clears history and provably retains
  `uncertain` and `turnCount`. `childLedger` has no main-code caller.
- **Privacy pre-admission: all three charter clauses verified literally**, including
  zero reservations on a late block.

## What to preserve as precedent

Audit 3's verdict, which I agree with: the design is genuinely good at preventing the
defects it anticipated, with real gaps where it stopped looking. Specifically
`PathBoundary` (the strongest code in the repo), the positive-form NaN guards with
their explanatory comments, `SafeView` recomputing its own hash, and the
unreachable-by-construction `UNREACHABLE_DECISION` default.

The single most useful lesson from this round is the one I inflicted on myself: **a
regression test written against equal buckets cannot detect a `max`-for-`add` bug.**
The assertion had to be rebuilt with deliberately unequal values to see it. Worth
remembering whenever a fix's test seems too comfortable.
