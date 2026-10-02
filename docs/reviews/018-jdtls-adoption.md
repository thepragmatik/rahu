# jdtls adoption — evidence and baseline

Date: 2026-10-03. Repo HEAD at time of writing: `57fdc6b`.

## Server capability (verified 2026-10-03)

- jdtls **1.61.0**, installed at `/opt/homebrew/Cellar/jdtls/1.61.0/libexec/plugins`.
- `ls` of that plugin directory: **114 plugins**. Relevant ones present:
  - `org.eclipse.ltk.core.refactoring_3.16.0.v20260702-0744.jar` — the LTK
    refactoring engine (rename, extract, inline, move).
  - `org.eclipse.jdt.core.compiler.batch_3.46.100.v20260826-1225.jar` — the batch
    compiler.
  - `org.eclipse.search.core_3.16.700.v20260501.jar` — search core.
- Conclusion: **the server can rename, extract, inline, move, and report
  diagnostics.** There is no capability gap in the server. This is the snapshot
  build taken specifically because milestone jdtls releases lag on Java 27, and it
  matches this project's `maven.compiler.release=27`. Do not downgrade it.

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

## Token baseline to beat

To be filled in from one week of traces before the integration is judged. Metrics:

1. Mean tool-call tokens for a Java edit in `rahu-core`.
2. Edit-retry count (a compile cycle that returns an error the agent must fix).
3. Mean tool-call tokens for a "where is X used" question.

## Measurement gate

If a single `find_references` round-trip costs more wall-clock than the token spend
it saves, jdtls is **not** cost-effective for this workload. Stop, and record that
negative result here. It is a valid outcome and must not be quietly dropped.