# CLI specification

The CLI is the first product surface. It should make routing observable while letting answers remain readable. All commands below are target behavior; implementation supplies actual launcher/install instructions.

| Command | Purpose |
|---|---|
| `rahu demo` | Deterministic offline run using built-in synthetic fixtures |
| `rahu config validate --config FILE` | Structural validation; optional explicit live catalog validation |
| `rahu config show --config FILE` | Resolved, redacted configuration and provenance |
| `rahu route inspect --config FILE --prompt TEXT` | Explain feasible candidates without paid generation |
| `rahu run --config FILE --prompt TEXT` | One bounded user task; supports stdin via `--prompt -` |
| `rahu chat --config FILE` | In-process follow-up conversation with aggregate limits |
| `rahu trace inspect RUN_PATH` | Inspect metadata and completeness |
| `rahu replay RUN_PATH` | Offline policy replay when captured inputs permit |
| `rahu eval --suite FILE --config FILE` | Offline by default; live execution requires explicit live flag and budget |

`run` inherits shadow mode unless explicitly overridden with `--routing active`. Include `--format text|json`; text is default on terminal, JSON is selected explicitly for automation. Output answer/result to stdout; route decisions, warnings and progress to stderr. JSON mode emits one final structured result to stdout and never mixes progress lines there. Trace JSONL is a separate file.

`run --capture-payloads` explicitly enables bounded private capture for that run; metadata remains default. `config validate --live-check` checks non-billable catalog/service metadata and reports any unverifiable profile. `eval --live --max-cost-usd DECIMAL --report PATH` is the explicit paid form with one aggregate experiment ledger, including all tasks and compaction phases. [Artifact contracts](artifacts.md) specify suite/report formats. No paid budget default for eval.

## Conversational CLI

`chat` supports `/status` (current route, turn count and aggregate settled/reserved/uncertain spend), `/reset` (clear content/continuation, retain ledger/count), and `/exit`. EOF ends cleanly; Ctrl-C cancels the active turn and exits 130. No background paid calls while awaiting input. Each submitted user line is one turn in alpha; multiline content is supplied by one-shot stdin or file input if implemented/documented. Reject unsupported slash commands with help before a paid call.

Text chat sends prompts/progress to stderr and each completed answer to stdout. Non-TTY input consumes one turn per line until EOF; `chat --format json` emits one structured JSONL result per submitted turn, distinguished from `run`'s single final JSON document. Blank lines do not create paid turns. One session has one active turn; no parallel input queue. History is memory-only and does not survive exit. See [context](context.md) for failure retention and limits.

`run` is a fresh single-turn session; it cannot implicitly resume a prior run path. Follow-up dogfooding uses `chat`, not replay. Session/experiment limit errors expose their source without double-counting nested ledger views.

Target terminal footer on stderr:

```text
Route fast@low · shadow (suggested quality@medium)
Cost $0.004 estimated · 1 generation step · 1.8 s · trace <run-id>
```

Those numbers illustrate formatting only. Never display sample values as measured data. Show `cost unavailable` or `effort unobserved` when relevant. Avoid presenting model confidence as answer accuracy. Debug mode explains exclusions and fallback causes with bounded redacted fields.

## Errors and accessibility

Errors identify the failed operation, cause and next action: `routing.fallback: quality@none is incompatible with mandatory reasoning; choose a supported policy`. Do not dump a stack trace by default. `--debug` remains redacted. No colour-only states, emoji-only labels, decorative banners, or animation required to understand a run. Honour `NO_COLOR`, non-TTY output and narrow terminals. Help examples use consistent terminology.

Exit codes: 0 complete answer/deterministic demo, 2 invalid input/configuration, 3 no feasible route/limit reached/privacy blocked, 4 provider/decision/tool failure, 5 trace/replay integrity failure, 130 interrupted. A denied tool may still lead to a completed answer; exit code follows the final run outcome. Evaluation nonzero means protocol failure or failed asserted gate, with report details.

Ctrl-C cancels the run and child scopes, attempts terminal trace and writes a short cancellation message. Do not hide uncertain billing/effects. Prompt strings, API keys and raw provider payloads never appear in help or default diagnostics. Current CLI has no interactive approval flow because initial tools are read-only; future unresolved approvals fail safely in non-interactive mode.

## Privacy interaction

`run`, `chat` and `route inspect` accept `--input-classification unknown|approved-nonsensitive`, with config/default precedence. Evaluation uses trusted loader provenance for authored synthetic fixtures; live user prompts require explicit non-sensitive assessment. The option never overrides a detected protected value, restricted source or stale approval. Normal submitted text defaults to unknown. A live workflow must select safe prompts and separately approved source views via the local manifest before any model request; read permission does not establish disclosure permission.

A mandatory-context block returns terminal reason `PRIVACY_BLOCKED`, exit 3, a safe reason code/category and corrective action; JSON includes the same typed status without content/snippets. No interactive override or cloud sanitiser. Local inspection/replay remains offline and must not dump protected payloads by default; replay fixtures are synthetic. Capture/debug flags grant no outbound clearance. Safe diagnostics and provenance checks apply even when no model call is planned.
