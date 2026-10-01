# Observability and replay specification

## Event envelope

Append JSONL records with `schemaVersion=1`, `runId`, monotonic per-run `sequence`, UTC `timestamp`, elapsed monotonic milliseconds, `type`, `operationId`, optional parent-operation ID and bounded payload. Events are immutable. Sequence is assigned by one run writer. Include version/config/catalog hashes in `RunStarted`.

`RunStarted` also includes session ID/turn index and optional experiment ID. Ledger events distinguish run/session/experiment allowance views and one reservation ID; totals are reported once at the owning level. Terminal records include no-progress fingerprints/counts when relevant, context/template/projection hashes and session aggregate status. Memory-only chat does not imply a persisted resumable session or global sequence across runs.

Events: `RunStarted`, `DecisionRequested`, `DecisionCompleted`, `DecisionFailed`, `RouteResolved`, `AdmissionReserved`, `ModelRequested`, `ModelCompleted`, `ModelFailed`, `ToolProposed`, `ToolDenied`, `ToolStarted`, `ToolCompleted`, `ToolFailed`, `ContextCompacted`, `CostReconciled`, `RunTerminated`. Schema-versioned payload definitions and golden fixtures are implemented in S08. New fields may be additive; incompatible meaning needs a version change.

## Required payloads

| Record | Essential evidence |
|---|---|
| Decision | operation kind; state hash/projection version; candidate order and trusted descriptions; prompt/adapter/model version; raw confidence semantics; probabilities or absence; measured/server latency |
| Route | suggested/executed candidate; shadow/active; exclusion causes; fallback cause; requested effort/provider policy |
| Generation | request/response IDs; requested/observed model/provider; observed effort or unavailable; finish reason; all supplied usage categories; elapsed duration |
| Ledger | reservation, reported vs estimated settlement, uncertain liability, remaining admission allowance, units and snapshot |
| Tool | call ID/version; arguments hash; effect/policy result; start/completion; outcome; truncation and provenance |
| Terminal | reason; final step/tool/compaction counts; cost categories; duration; warnings; trace completeness |

Do not emit probabilities fabricated from a selected label, inferred provider identity, guessed first-token latency, or zero for unknown cost. Fields need availability markers or explicit null plus reason.

## Persistence and privacy

Default metadata mode stores hashes and redacted operational fields without prompts, tool content, arguments or raw reasoning. Payload capture is explicit per run and stores a separate bounded transcript/decision artifact referenced by hashes; such traces are sensitive. Keep opaque continuation data separate, retain only as needed for the active run, and never include raw reasoning in normal telemetry. Provide sanitised export that omits payloads and credentials.

Create files with owner-only permissions where supported and document platform limits. Ignore `.rahu/` in git. Retention default is user-managed; do not silently delete traces. A future retention command must preview its scope. Hashes may still reveal equality and operational metadata; privacy is not guaranteed merely by hashing.

Default persistence failure stops new operations with `TRACE_FAILURE`. If a failure happens after a tool starts, preserve its outcome in memory, cleanup and disclose the persistence gap; never repeat it to recover a trace. File writes are flushed before effects start, but this does not guarantee durability across a hardware crash. Corrupt or truncated last JSONL lines make the run incomplete and are never accepted as success.

## Three distinct replay concepts

1. **Inspection:** render recorded metadata without executing anything. Always available for intact metadata traces.
2. **Policy replay:** with captured/frozen inputs, inject recorded model/decision/tool outcomes and run pure routing/state policies. Compare policy decisions and terminal state deterministically. If payloads are absent, report replay unavailable.
3. **Live rerun:** a new explicit, billable run using source inputs and pinned versions. Outcomes may differ and effects need fresh authorisation. Never call this deterministic replay.

No replay command performs network or tools by default. Replays do not recover crashed live sessions. Stronger reproducibility needs captured prompt templates, candidate descriptions/order, decision model/checkpoint, catalog, config, adapter and harness commit. Moving provider aliases are recorded as a limitation even if a seed is provided.
