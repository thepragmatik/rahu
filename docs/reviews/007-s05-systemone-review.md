# S05 System One adapter review

## Scope and hypothesis

Date: 2026-10-01. Change: S05 genuine System One HTTP adapter (rahu-systemone) with
jev-compatible-v1 profile, DecisionEngine port, strict response validation. Affected
requirements: R02, R04 (A02, A06). Hypothesis: the pinned upstream envelope can be validated
strictly (labels, finite probabilities, sum tolerance, truncation, duplicate keys) without a
parser-repair path. Simplest alternative: accept any well-formed choice — rejected, it
launders malformed decisions into routing.

## Evidence

- Fixtures copied from the repo's authored synthetic examples into
  `rahu-systemone/src/test/resources/systemone/` with a PROVENANCE.md sidecar
  (upstream blob SHA recorded, synthetic=true).
- Local recording server tests: request shape (model/state/questions/criteria exactly the
  pinned envelope); valid response parses choice + probabilities + separate raw confidence
  (0.6 concentration vs 0.8 chosen probability, semantics tagged); invalid-response.json's
  unknown label rejected as PROTOCOL_ERROR (no repair); malformed JSON / NaN / missing
  answers typed failures; noul parsing with [0,1] bounds; 500/429 typed failures;
  truncated=true rejected under default profile.
- `./mvnw verify` BUILD SUCCESS; suite total 38 tests (21 core + 10 cli + 7 systemone).

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Low (test-fixed) | truncation test initially injected `truncated:true` via a literal string replace that never matched the pretty-printed fixture — the check silently passed as valid | replaceFirst("{", ...) injection; also verified the adapter's `has("truncated")` check fires on real injection | None |
| Medium | Adapter returns after the FIRST question's result (MVP asks one question per call; batching is deferred) | Documented; per-question ask keeps routing/compaction simple; batching lands with S09 if needed | Extra roundtrips vs batched; cost/latency measured in G09 |
| Low | Loopback-only HTTP guard enforces prefix match (127.0.0.1/localhost/[::1]) | Spec-compliant for alpha; IPv6 literal formats beyond [::1] unsupported | None for alpha |

## Decision

Proceed. M1 (S01–S05) is now offline-verified: adapter contracts proven against local
recording servers with zero live calls, per the repo's review-003 rule that real smoke waits
for S06 privacy/authority. Next: S06 bounded runtime + privacy gate + ledgers (the keystone).
