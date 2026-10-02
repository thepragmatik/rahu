# jdtls adoption — evidence and baseline

Date: 2026-10-03. Repo HEAD at time of writing: `57fdc6b`.

## Which jdtls (corrected 2026-10-03)

Two builds exist on this host and they are **not** the same. Binding to the
Homebrew one would silently run an older compiler on a Java 27 project.

| | Homebrew 1.61.0 | **Snapshot (the one to use)** |
|---|---|---|
| path | `/opt/homebrew/Cellar/jdtls/1.61.0/libexec/plugins` | `~/.hermes/profiles/uplift/lsp/jdtls-snapshot/plugins` |
| LTK refactoring | `3.16.0.v20260702-0744` | **`3.16.100.v20260923-1850`** |
| batch compiler | `3.46.100.v20260826-1225` | **`3.46.200.v20260930-0211`** |
| plugin count | 114 | 114 |

The snapshot is roughly seven weeks newer and is the build Hermes itself is
configured to use (`lsp.servers.jdtls.command` in the uplift profile's
`config.yaml`). It is pinned because milestone jdtls releases lag on Java 27.

`LspSession.resolveBinary()` therefore resolves, in order: the `RAHU_JDTLS`
environment variable, the snapshot path, then `jdtls` on PATH. **Never a
hardcoded Homebrew path.**

## Server capability (verified 2026-10-03)

- Snapshot jdtls ships **114 plugins**, including:
  - `org.eclipse.ltk.core.refactoring_3.16.100.v20260923-1850.jar` — the LTK
    refactoring engine (rename, extract, inline, move).
  - `org.eclipse.jdt.core.compiler.batch_3.46.200.v20260930-0211.jar` — the batch
    compiler.
  - `org.eclipse.search.core_3.16.700.v20260505-0615.jar` — search core.
- Conclusion: **the server can rename, extract, inline, move, and report
  diagnostics.** There is no capability gap in the server. The gap is that no
  client in this repo drives it.

## Why there is no CLI

`jdtls --version` does not return. It starts a server, logs
`Registered provider ch.qos.logback...` from `org.apache.aries.spifly.BaseActivator`,
and blocks until an external 90-second timeout kills it.

This is correct behaviour, not a defect: **jdtls is a stdio JSON-RPC LSP server
that waits for an `initialize` request.** It has no query subcommand, so any
integration must speak LSP over stdio.

## Current client state

There is **no LSP client in this repo.** A
`grep -ril "jdtls|language server|lsp" --include=*.java --include=*.md .` hits only
`LedgerTest.java`, `TaskClassTest.java`, and four docs — all incidental word
matches. `docs/plans/active/build-status.md` contains no `lsp` or
`language server` text at all.

## Core-module constraint

`rahu-core` is **JDK-only by architecture** (enforced by a module-boundary test):
its only declared dependency is `junit-jupiter`. There is no Jackson in core.
`CanonicalJson` exposes exactly one public method, `canonicalize(String) -> String`
— there is no parse or write API.

Consequence: the LSP client needs a small local JSON encoder/decoder in
`rahu-core`. It must not add a JSON dependency, per the module-boundary test.

## Measured (2026-10-03)

From `LspCostProbeTest` against the pinned snapshot, on this machine:

| metric | value |
|---|---|
| capabilities advertised | **26** |
| cold startup + `initialize` | 980 / 962 / 970 ms |
| `workspace/symbol` round trip | 535 / 548 / 536 ms |
| full session incl. close | 2440 / 2418 / 2425 ms |

Three consecutive runs, no leaked processes, tight variance. The client is
stable; the close cost (~900 ms) is the polite-shutdown wait.

The server really does advertise `renameProvider`, `referencesProvider`,
`definitionProvider`, `workspaceSymbolProvider`, `codeActionProvider` and an
`executeCommandProvider` carrying Java commands.

### Cost-effectiveness verdict: NOT YET PROVEN

~1.5 s per session is cheap in wall-clock terms, but that is **not** the
comparison that matters. The question is whether it beats what the agent already
does, and the honest answer is that this has not been measured:

- jdtls gives **exact** references, rename and type info. Grep gives approximate
  refs and costs tokens; the compiler gives exact compiles and costs ~30 s.
- The saving is only real if the agent currently burns tokens on symbol discovery
  often enough to matter. **That has not been quantified.**

So the verdict is neither "it works, ship it" nor "it doesn't work". It is
"the plumbing works, the economics are unmeasured".

**Token baseline still to be collected** (the numbers the decision needs):

1. Mean tool-call tokens for a Java edit in `rahu-core`.
2. Edit-retry count (a compile cycle that returns an error the agent must fix).
3. Mean tool-call tokens for a "where is X used" question.

### Leak found and fixed

The first cost probe hung on run 2 with two jdtls processes alive. `close()`
did not reap the server. A leaked server holds the workspace index lock, so the
next run cannot start against the same directory — a leak here is not a slow
down, it is a hard failure on the following run. Fixed: `close()` is now
idempotent, waits briefly for a clean exit, destroys only if still alive, and
closes both streams. `closeLeavesNoJdtlsProcessBehind` guards it.

