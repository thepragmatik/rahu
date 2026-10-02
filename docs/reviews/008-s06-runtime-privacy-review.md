# S06 runtime, privacy gate, ledgers, authority review

## Scope and hypothesis

Date: 2026-10-01. Change: S06 (rahu-core/runtime: RunPhase/RunStateMachine, Ledger,
NoProgressDetector; rahu-core/privacy: Provenance/SafeView/Finding/PrivacyScanner/
PrivacyGate; rahu-core/authority: EffectClass/ProposedOperation/AdmissionPipeline;
cancellation tests). Affected requirements: R01, R15, R19, R20, R25 (A12, A13, A24, A25,
A27, A32-partial, A33, A34). Hypothesis: the runtime kernel can enforce deterministic
authority and fail-closed privacy without any network code. Falsifier: any path from model
text to effect bypassing AdmissionPipeline; any blocked dispatch producing a send.

## Evidence

- TDD throughout: 30 new tests (RunStateMachineTest 4, LedgerTest 7,
  NoProgressDetectorTest 5, PrivacyGateTest 8, AuthorityAndOrchestrationTest 5,
  CancellationTest 2 — incl. two bugs found and fixed during red).
- `./mvnw verify` BUILD SUCCESS; 76 tests total across 4 modules.
- A33/A34 core proofs: unknown provenance blocks pre-admission (zero dispatch); synthetic
  clean admits; canaries block by category with no value echo; late-injected canary in the
  serialised body blocks at dispatch recheck; non-loopback plaintext HTTP blocked by
  endpoint policy; source mutation changes hash and re-scan blocks; restricted provenance
  blocks unconditionally. Canary literals runtime-concatenated (no secret-shaped literals
  in source).
- A13: over-reservation denied; ambiguous outcome retained as uncertain liability
  constraining further admission; overshoot stops paid work; reset preserves settled +
  uncertain; child settlement rolls up to parent exactly once (double-count test).
- A27: three identical completed batches trigger NO_PROGRESS; different observation or
  arguments reset; fresh call IDs do not evade (call IDs excluded from fingerprint via
  JDK-only CanonicalJson, sorted keys).
- A24: effectful operation denied at step 3 before effect; terminal run denied at step 1;
  dispatch recheck blocks even for approved operations; counted fake provider proves one
  request per admitted operation (no hidden fan-out).
- A12 (cancellation): interrupt of a blocked provider call triggers in-call cleanup;
  test joins the worker via executor termination BEFORE mutating shared run state (an
  earlier version raced `future.get` on a cancelled future — get() returns immediately on
  cancellation and is not a completion barrier; fixed in-test, product code unaffected).

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium (test-fixed) | Cancellation test used future.get() on a cancelled future as a completion barrier; get() returns immediately on cancellation, letting main thread set TERMINAL while worker still evaluated | Join via executor.awaitTermination before touching shared state | Product cancellation semantics unchanged; owner: S09 loop integration |
| Low | PrivacyScanner regexes cover key-shaped tokens, emails, auth headers, private keys, credential assignments, long hex blobs; documented as imperfect by design | Uncertainty blocks at the gate (fail-closed), per privacy.md | Obfuscated/encoded canaries not detected — stated limit, never claimed otherwise |
| Low | Ledger uses max(reserved, uncertain) as committed amount (conservative, not additive) | Matches "uncertain liability constrains admission" without double-counting | Multi-reservation concurrency is serial-only in alpha |
| Info | Structural-concurrency spike deferred: all S06 paths are serial by design (single driver, serial tools); scoped parallel I/O has no consumer yet (YAGNI) | Record here; revisit in S09 if summary/compaction needs parallel reads | None |

## Decision

Proceed. All S06 acceptance evidence recorded; no high-severity findings. Next: S07
read-only workspace tools behind the same pipeline.
