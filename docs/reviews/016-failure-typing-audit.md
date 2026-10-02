# Review 016 — failure-typing and decision-port audit (N2)

Date: 2026-10-02. HEAD after fixes: `5d81b85`. Two lenses were run as independent
subagent audits: **failure typing at every boundary**, and **test quality plus
extensibility at the decision port**. Their consolidated reports are in the delegation
transcripts; this document records what survived verification.

## Method: the reports were audited too

Both subagents produced confident, line-cited findings. Several did not survive
contact with HEAD, and that is the most useful part of this review.

| Child claim | Verdict |
|---|---|
| `PrivacyGate:75` loopback `startsWith` is a live fail-open | **STALE.** Fixed at `4002166`. The child read pre-fix code |
| `ToolLoopRerankTest` rerank test passes vacuously | **STALE.** Corrected at `4067ea0`. The second child caught itself reading a 285-line version of a 316-line file and said so |
| Empty 200 body throws NPE at `SystemOneHttpAdapter:171` | **CONFIRMED**, fixed |
| `UncheckedIOException` escapes `Files.walk` | **CONFIRMED**, fixed |
| NUL byte escapes as `InvalidPathException` | **CONFIRMED**, fixed |
| `confidenceField` accepted and never read | **CONFIRMED**, recorded not fixed — see below |
| NaN passes the `[0,1]` guards | **CONFIRMED**, fixed |
| `DecisionResult` is `sealed` | **TRUE BUT MISLEADING.** It has no `permits` clause and every consumer uses `instanceof`, so no exhaustiveness is enforced anywhere |

A report that names a real file and line can still be describing a file that no longer
exists. The stale rows above were caught only because each claim was re-derived from
the working tree before acting.

## F-6 HIGH — three raw exception types escaped the tool boundary → `cdef376`

The sealed `DecisionResult` hierarchy is genuinely well defended at the parser. It does
not survive contact with the filesystem edge.

| Trigger | Escaped | Reachability |
|---|---|---|
| NUL byte / lone surrogate in a path | `InvalidPathException` | one model-authored argument |
| Any unreadable subdirectory | `UncheckedIOException` from `Files.walk` | **no adversary needed** |
| Unreadable file during `search` | silent skip, `SUCCESS` | ordinary |

`InvalidPathException` is neither `BoundaryViolation` nor `IOException`, so it passed
through every `catch` in `WorkspaceTools`. Worse, its message carries the offending
path, bypassing the `safeReason`-never-echoes-the-value discipline at
`PathBoundary:19-31` — the one rule that existed precisely to keep values out of model
output.

`Files.walk` wraps directory-read failures in a `RuntimeException`. This is the most
likely real-world case on the boundary and it aborted the call outright.

The `search` skip stays non-fatal by design; only its **invisibility** was the defect.
A search that examined 9 of 10 files returned `SUCCESS` with `truncated=false`, so
"I found nothing" was indistinguishable from "I could not look" — absence of evidence
reported as evidence of absence. The skip is now visible and sets `truncated`.

The tests assert the **contract**, not the historical defect: they keep passing if the
throws are removed and fail if a raw exception escapes again. One negative control
proves the new marker does not fire on a healthy tree — a marker that always fires is
noise the model learns to ignore.

## F-7 HIGH — empty decision body threw NPE out of `askAll` → `5d81b85`

`MAPPER.readTree` returns `null` for a zero-byte body and throws nothing, so the
`catch (IOException)` above never ran and `root.has(..)` raised a raw
`NullPointerException`. That defeated the batch-isolation promise in the `askAll`
javadoc: one malformed response killed every question rather than one.

The adjacent case was already handled correctly — a body of literal `null` yields
`NullNode` and a typed `PROTOCOL_ERROR`. The empty body was a strictly worse outcome
than the case sitting next to it.

## F-8 HIGH — the loopback rule was duplicated, so the F-3 fix was incomplete

`4002166` fixed the prefix match in `PrivacyGate`. It did not fix the **second copy**
at `SystemOneHttpAdapter:49-52`, still `startsWith`, where
`http://localhost.evil.example` passed while the core policy rejected it.

The core fix survived only because the duplicate was missed. This is the same root
cause as the project's recurring defect class — a rule expressed twice, enforced once.
The duplicate is deleted; the adapter calls `PrivacyGate.endpointPolicy`. **One rule,
one owner.** This is the concrete argument for searching for every copy before
declaring a duplicated rule fixed.

## F-9 MEDIUM — `Question` is unsealed → `5d81b85`

The original score-question defect was a documented type with no implementation, which
serialised as `{}`. An unsealed marker interface is what let that stay invisible: the
adapter's serialise and parse chains are `if/else if` with **no `else` and no
exhaustiveness**, so a forgotten type produces an empty object on the wire, or no
result entry at all.

`Question` is now `sealed ... permits ChoiceQuestion, NoulQuestion, ScoreQuestion`.
Verified by observation: a hostile question type in the new test **stopped compiling**,
which is the guarantee working rather than a claim about it.

The codebase already uses `sealed ... permits` in `AdmissionPipeline:19`,
`ReasoningPolicy:9` and `CompactionPlanner:30`. The decision port was the one place
that deliberately did not. That inconsistency was the finding.

## F-10 MEDIUM — NaN passed both `[0,1]` guards → `5d81b85`

Every comparison against `NaN` is false, so `x < lo || x > hi` admits it. Consequences
were silent in both places:

- `SystemOneHttpAdapter:65` serialised it, and Jackson writes `{"contextPressure":"NaN"}`
  — a **string where a number is contracted**.
- `RouteResolver:60` evaluated `chosenProb >= NaN`, always false, so **every** decision
  was rejected and the resolver degraded to fallback forever with no error anywhere. A
  NaN floor in a config file is a permanently-degrading router.

Both guards are now written in positive form (`!(x >= lo && x <= hi)`), which rejects
`NaN`. Worth noting the project already knows the idiom — the parser guards non-finite
numbers explicitly with `Double.isNaN`. It just was not applied to its own inputs.

## F-11 HIGH — `confidenceField` is documented, plumbed, and never read — **RECORDED, NOT FIXED**

Confirmed against HEAD: `confidenceField` appears in `ConfigLoader:226`,
`RahuConfig:43`, `ActiveRouter:84`, `SchemaGenerator:57` and `configuration.md:19` —
and in `RouteResolver` **only** at the record component declaration, line 25. The actual
comparison at line 54 is hardcoded to `probabilities.get(chosenLabel)`.

An operator who sets `confidenceField: "raw_confidence"` gets no error, no warning and
no behavioural change. This is the score-question bug in configuration form.

**Why it is not fixed here:** the only correct implementation is to have the parser emit
the configured field, which changes the wire contract and the A06 typed-failure table —
a contract change that needs its own red tests and its own review, not a drive-by in an
audit commit. Interim exposure is bounded: the default is `chosen_probability`, which is
what the resolver hardcodes, so **the default path is correct** and only a non-default
operator setting is silently ignored.

Recorded with its proof obligation: either honour the field in the parser, or reject a
non-default value at config validation (A15) so it cannot be set and ignored.

## Also confirmed, recorded, not fixed

- **Transport faults collapsed to `TIMEOUT`** (`SystemOneHttpAdapter:117-121`). Connect
  refusal (definitely never processed) and read timeout (may have been processed) are the
  same value, so a caller retrying on `TIMEOUT` cannot tell whether the retry is a
  duplicate. `FailureKind` has no member for the ambiguous case, so the information is
  destroyed at the boundary. Needs an enum addition and a spec change.
- **`DecisionResult` is `sealed` with no `permits` and no exhaustive `switch` anywhere.**
  Every consumer uses `instanceof` chains, so a new subtype compiles and falls through.
  Low risk in practice (the three leaves are stable) but the word "sealed" is doing less
  work here than it appears to.
- **Search-skip marker vs `truncated`** — resolved in favour of `truncated` because the
  child's report was right that a marker alone is weak: a caller checking the flag would
  still read a clean success.

## Verdict on the two lenses

**Test quality: genuinely good, and the child verified it by execution rather than by
reading.** `CostGateTest.spentAllowanceRefusesBeforeDispatch` asserts the provider was
**never called** rather than that a method was invoked — the right shape. The rerank
membership test was probed with four level patterns and confirmed inversion-sensitive.
Two weak spots, both low: `ToolLoopRerankTest.onlySearchIsReranked` and `offCostsNothing`
assert dispatch counts without asserting output.

**Extensibility: one production implementation per port** (`SystemOneHttpAdapter`,
`OpenRouterProvider`) — clean hexagonal discipline, 8 and 3 fakes in test scope
respectively. Adding a question type was 3 files and 5 unlabelled edit sites, three of
them silent; F-9 makes the type half a compile error and the `permits` clause is now the
single place to look.

## Links

[015 — architecture and design audit](015-architecture-audit.md) ·
[release gate evidence](dogfood-release.md) ·
[`docs/specs/acceptance.md`](../specs/acceptance.md) ·
[`docs/release-gates.md`](../release-gates.md)