# Design aesthetics and review criteria

Rahu's first aesthetic goal is a coherent, dependable developer experience. Design quality applies to domain concepts, CLI interaction, documentation and later visual surfaces. It is not achieved by decorative output or more abstraction.

## API design

Use a few precise concepts: candidate, decision, route resolution, run, observation. Keep model identity, provider endpoint and reasoning policy distinct. A caller should be able to understand an ordinary run by reading its composition. Make required dependencies explicit; hide provider quirks behind adapters while retaining enough metadata to diagnose behavior.

Review names for domain meaning, not implementation fashion. `RouteResolution` separates suggestion from execution; `confidence` alone is ambiguous. Avoid overloaded integer budgets, maps of stringly typed domain state, null as a catch-all, and builders that admit illegal combinations. Error paths deserve the same clarity as success paths.

## CLI design

The answer is the primary output. A brief route/cost/status footer provides accountability on stderr. Detailed diagnostics are opt-in. Commands and flags use consistent words; a user should not have to infer whether model, route, policy and pool mean the same thing. Defaults should be discoverable in help and resolved config.

Use restrained typography and colour. Plain text must work when piped, on a narrow terminal and with colour disabled. Explicit labels convey warnings/errors. No spinning animations in automation output. JSON results should be stable enough for scripts, not merely a dump of internal objects.

An error provides cause and a useful action. Unknown values say unavailable. Shadow routing clearly identifies both suggested and executed candidate. Estimated spend cannot look like a reconciled bill. Low-confidence fallback should be understandable without reading a raw prompt.

## Documentation design

Use the root README as an entry point. Keep agent instructions concise enough to read and put technical contracts in linked specs. Requirements have test mappings; plans have gates; research has evidence and limits. Examples distinguish proposed syntax from executable commands. Do not maintain several conflicting copies of defaults.

## Review rubric

For each criterion record pass, concrete finding, or deferred with reason. Do not average away a broken permission or misleading output.

| Criterion | Review question | Evidence |
|---|---|---|
| Coherence | Does one concept have one name and meaning? | Public API/CLI vocabulary review |
| Economy | Can a concept, module or dependency be removed without losing behavior? | Simpler alternative evaluated |
| Legibility | Can a new user explain the executed route and failure? | Demo output and error examples |
| Truthfulness | Are estimates, uncertainty and missing metadata visible? | Shadow/error/unknown-cost runs |
| Accessibility | Does plain text work in narrow, non-TTY, colourless output? | Captured CLI smoke output |
| Composability | Can tests/providers be substituted without global state? | Fake integration test |
| Reliability | Are invalid states and cancellation visible in the design? | Failure and cleanup tests |

## Later visual product

Before adding a dashboard, define the operator task and information hierarchy. Proposed views: run outcome, route explanation, budget ledger and experiment comparison. Use semantic colours, accessible contrast, concise labels and honest empty/error/loading states. Review actual screenshots across sizes and keyboard behavior. No UI work is in M1/M2; its quality cannot be claimed from these specs alone.
