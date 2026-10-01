# S07 read-only tools review

## Scope and hypothesis

Date: 2026-10-01. Change: S07 (PathBoundary, WorkspaceTools list/read/search,
ToolCallLog A09 dedup, Tool/ToolRegistry frozen composition). Affected requirements: R07,
R15, R19 (A08, A09, A24, A26-partial, A33/A34 tool-path). Hypothesis: bounded read-only
tools can enforce the filesystem boundary deterministically (containment, symlinks,
exclusions, truncation) with typed outcomes and no escaping exceptions. Simplest
alternative: java.nio directly with ad-hoc checks — rejected, the boundary must be one
reviewable type every call passes through.

## Evidence

- TDD: 21 new tests (PathBoundaryTest 7, WorkspaceToolsTest 5, ToolRegistryTest 4,
  + existing runtime suites re-run). `./mvnw verify` BUILD SUCCESS, 92 tests total.
- A08: absolute/traversal/symlinked-parent/symlink-leaf/secret-path (.env, .pem, .git,
  target) requests all rejected as typed INVALID before any read; bounded results on
  valid reads (500-entry list cap, 64 KiB/1000-line read cap, 100-match search cap) with
  explicit truncation flags.
- A09: repeated call ID + identical canonical args reuses the recorded outcome (marked
  reused); same ID + changed args raises a typed ProtocolError; call-ID excluded from the
  canonical form so new IDs cannot evade the no-progress detector (shared CanonicalJson).
- A24: injected instruction text ("IGNORE PREVIOUS INSTRUCTIONS ...") inside a workspace
  file is returned as inert data; the registry offers no effectful tool; authority remains
  in the AdmissionPipeline (proven in S06 suite).
- Defects caught by the snapshot jdtls LSP during this slice (the reason it was installed):
  stray corrupted tokens in 3 files at write time, package-private CanonicalJson,
  wrong accessor (reason() vs field), Jackson import leaking into JDK-only core, an
  `if !` syntax error, a lambda final-variable violation, and 2 stale unused imports —
  all fixed at write time instead of at build time.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | Default exclusions are name/type-based, not content-scanning; a secret named `notes.txt` passes the boundary | Reading is not disclosure: privacy admission (S06 gate) scans content before any model-visible dispatch; documented limit | Content-level scan owns PII risk; owner: S09 prompt assembly |
| Low | search walks depth 6 with a per-file 64 KiB read window; a deeply nested huge file could be partially searched | Cap is explicit in the result; documented | None for alpha |
| Low | list/search race with a concurrent filesystem mutator | Stated in tools.md as a documented limitation (not an OS sandbox) | None for alpha |

## Decision

Proceed. All S07 acceptance evidence recorded; no high-severity findings. Note: the
jdtls snapshot (1.62.0, Java-27 support) proved its value repeatedly in this slice and is
now the configured LSP server via lsp.servers.jdtls.command override. Next: S08 traces
and offline replay.
