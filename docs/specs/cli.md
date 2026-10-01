# CLI specification

The CLI is the first product surface. It should make routing observable while letting answers remain readable. All commands below are target behavior; implementation supplies actual launcher/install instructions.

| Command | Purpose |
|---|---|
| `rahu demo` | Deterministic offline run using built-in synthetic fixtures |
| `rahu config validate --config FILE` | Structural validation; optional explicit live catalog validation |
| `rahu config show --config FILE` | Resolved, redacted configuration and provenance |
| `rahu route inspect --config FILE --prompt TEXT` | Explain feasible candidates without paid generation |
| `rahu run --config FILE --prompt TEXT` | One bounded user task; supports stdin via `--prompt -` |
| `rahu trace inspect RUN_PATH` | Inspect metadata and completeness |
| `rahu replay RUN_PATH` | Offline policy replay when captured inputs permit |
| `rahu eval --suite FILE --config FILE` | Offline by default; live execution requires explicit live flag and budget |

`run` inherits shadow mode unless explicitly overridden with `--routing active`. Include `--format text|json`; text is default on terminal, JSON is selected explicitly for automation. Output answer/result to stdout; route decisions, warnings and progress to stderr. JSON mode emits one final structured result to stdout and never mixes progress lines there. Trace JSONL is a separate file.

Target terminal footer on stderr:

```text
Route fast@low · shadow (suggested quality@medium)
Cost $0.004 estimated · 1 generation step · 1.8 s · trace <run-id>
```

Those numbers illustrate formatting only. Never display sample values as measured data. Show `cost unavailable` or `effort unobserved` when relevant. Avoid presenting model confidence as answer accuracy. Debug mode explains exclusions and fallback causes with bounded redacted fields.

## Errors and accessibility

Errors identify the failed operation, cause and next action: `routing.fallback: quality@none is incompatible with mandatory reasoning; choose a supported policy`. Do not dump a stack trace by default. `--debug` remains redacted. No colour-only states, emoji-only labels, decorative banners, or animation required to understand a run. Honour `NO_COLOR`, non-TTY output and narrow terminals. Help examples use consistent terminology.

Exit codes: 0 complete answer/deterministic demo, 2 invalid input/configuration, 3 no feasible route/limit reached, 4 provider/decision/tool failure, 5 trace/replay integrity failure, 130 interrupted. A denied tool may still lead to a completed answer; exit code follows the final run outcome. Evaluation nonzero means protocol failure or failed asserted gate, with report details.

Ctrl-C cancels the run and child scopes, attempts terminal trace and writes a short cancellation message. Do not hide uncertain billing/effects. Prompt strings, API keys and raw provider payloads never appear in help or default diagnostics. Current CLI has no interactive approval flow because initial tools are read-only; future unresolved approvals fail safely in non-interactive mode.
