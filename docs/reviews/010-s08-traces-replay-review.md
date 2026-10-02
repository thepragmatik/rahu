# S08 traces and replay review

## Scope and hypothesis

Date: 2026-10-01. Change: S08 (rahu-cli/trace: TraceEvent, TraceWriter, TraceReader,
ReplayEngine, ReplayOutcome). Affected requirements: R09, R10 (A10, A11, A16, A34-trace
surface). Hypothesis: an append-only JSONL envelope with writer-owned sequence and
fail-latched writes can back both inspection and deterministic policy replay without an
event-sourced runtime (ADR 0005). Simplest alternative: in-memory only — rejected, G06
requires persisted evidence.

## Evidence

- 8 new tests; `./mvnw verify` BUILD SUCCESS, 100 tests total across 4 modules.
- Envelope: schemaVersion=1, runId, writer-assigned monotonic sequence (client values
  ignored by contract test), UTC timestamp, elapsedMs, type, optional operationId,
  JSON payload (metadata-only by construction).
- A16: append failure (unwritable path) raises TraceFailureException and latches
  failed()=true — callers stop new work; deferred-open means construction never lies.
  Truncated/corrupt final line yields Completeness.INCOMPLETE with earlier events still
  inspectable; EMPTY is distinct from INCOMPLETE.
- A11: ReplayEngine feeds recorded DecisionResults through the same RouteResolver as
  live runs; identical recorded inputs replay to identical RouteResolutions (equality
  test); degraded/timeout records replay as degraded fallback with the fallback cause
  preserved — never a fabricated success; missing payloads yield UNAVAILABLE with a
  reason, matching the three-replay-concepts rule (no network, no tools).

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Low | Writer owns sequence; recorded events from an older writer with different sequences cannot be concatenated with new ones | Documented: one run = one writer; append-only | None for alpha |
| Low | Replay covers routing policy decisions; state-machine replay (full RunState transitions) is not exercised yet | A11 requires inputs-based policy replay; broader replay is M3 territory | Owner: S10 smoke runner |
| Info | Trace files created without explicit chmod owner-only; platform umask applies | observability.md asks owner-only "where supported"; macOS default umask 022 gives 644 — recorded as residual | Owner: S11 packaging (document) |

## Decision

Proceed. Next: S09 context/chat/compaction (the last heavy slice before S10/S11).
