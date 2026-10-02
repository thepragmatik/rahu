# S09 context and chat review

## Scope and hypothesis

Date: 2026-10-01. Change: S09 (rahu-core/context: SessionState/RunHandle, ContextPlan,
PromptAssembler, CompactionPlanner; rahu-cli ChatCommand). Affected requirements: R17, R18,
R08-partial (A14, A22, A23, A29, A32). Hypothesis: in-process sessions with deterministic
prompt assembly and System One–directed compaction satisfy the memory/context MVP without
persistence services or vector memory. Simplest alternative: raw message list in the CLI —
rejected, trust/roles/reset/ledger semantics need one owned type.

## Evidence

- 17 new core tests + scripted CLI run. `./mvnw verify` BUILD SUCCESS, 117 tests total.
- A22: two-turn in-process chat retains ordered history (verified in unit test AND live
  scripted `./bin/rahu chat` run over examples/offline.json — two turns, /status shows
  turns=2, /reset clears with ledger retained, /exit clean, exit 0; turn count advanced,
  fresh run IDs per turn, one active turn enforced, session/run ledger nesting proven).
- A32: reset clears history but keeps turn count and settled liability; failed turn keeps
  the user request + terminal reason and never promotes partial output to history.
- A23: deterministic order (harness SYSTEM role -> operator instruction files as DATA in
  USER role with recorded hashes -> history -> current request last); 16 KiB per-file and
  32 KiB total instruction bounds fail with typed errors; conservative token estimate
  (3 bytes/token + framing) bounded by the allowance, never presented as exact.
- A14: System One choice maps defer/concise/detailed; deterministic override forces
  concise when the fit check fails; recent two turns pinned out of the summary source;
  failed summary retains the original verbatim; successful summary marked "[untrusted
  summary]" and never becomes a source of authority.
- A29: context-only exclusion triggers a compaction plan instead of immediate terminal
  failure; no-pressure inputs terminate (no forced compaction loops).

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | Offline chat answers are canned placeholders; the real generation path (fake provider -> prompt assembly -> ModelOutcome) is not wired into chat yet | The composition seam exists (SessionState + PromptAssembler + ModelProvider port); S10 smoke runner wires them end-to-end with fakes and asserts the integrated behavior | Owner: S10 |
| Low | Token estimate is bytes/3; multi-byte UTF-8 overestimates slightly (conservative direction) | Documented in-code as an upper bound | Fine per runtime.md |
| Low | chat /status reads the session ledger only; experiment ledger view arrives with S10 | Tracked in S10 | None |

## Decision

Proceed. S09 acceptance evidence recorded. Next: S10 integrated smoke runner + eval
baseline, which wires everything end-to-end with fakes.
