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

### Cost-effectiveness verdict: NOT PROVEN — and the blocker is real

Three runs show the plumbing is stable and fast enough (~970 ms startup, ~540 ms
per query). That is **not** the comparison that decides whether to wire this in.

The real question is whether exact symbol facts beat what the agent does today
(grep for approximate refs, the compiler for exact compiles), and that turns on
token spend. **That baseline cannot be collected from this repo.**

Checked and confirmed absent:

- `results/` — does not exist.
- No `*.jsonl` traces anywhere outside `node_modules`.
- No cost/usage/token evidence under `docs/`.

There is no ledger output and no trace corpus, so the three baseline numbers
(mean tool-call tokens for a Java edit, edit-retry count, mean tokens for a
"where is X used" question) have no source. They would require a live dogfood run
against a funded provider, which is an operator decision, not a code change.

**Decision: Task 5 (the `ToolRegistry` wiring) is NOT done, deliberately.** Writing
`CodeIntelTool` now would be building on an unmeasured bet. The client is committed,
usable, and tested; the wiring waits for the baseline.

What is already established, and is the part that was in doubt:

- jdtls 1.61.0 snapshot is fully capable — `renameProvider`, `referencesProvider`,
  `definitionProvider`, `workspaceSymbolProvider`, `codeActionProvider` and an
  `executeCommandProvider` with Java commands, 26 capabilities total.
- A working client costs ~1.5 s per session and leaks nothing.
- `workspace/symbol("DecisionEngine")` returns a real hit.

The original premise — "the LSP server is not enough at the moment" — was wrong on
the capability side and right on the integration side. The server was always
enough; nothing was driving it. That is now fixed.

