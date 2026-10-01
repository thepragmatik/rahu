# Agent runtime specification

## State and termination

One run has a UUID, immutable configuration/catalog references, transcript, generation-attempt count, compaction count, ledger and monotonic deadline. Terminal reasons are `ANSWER_COMPLETE`, `NO_FEASIBLE_ROUTE`, `STEP_LIMIT`, `TIME_LIMIT`, `COST_ADMISSION_DENIED`, `CONTEXT_LIMIT`, `PROVIDER_FAILURE`, `DECISION_FAILURE`, `TOOL_FAILURE`, `CANCELLED`, `TRACE_FAILURE`, and `INDETERMINATE`.

Exactly one terminal event is attempted. A missing event after process failure means incomplete run, not success. The runtime transitions through created, deciding, admitted, generating, validating-tools, executing-tools, compacting and terminal. A terminal state cannot start a new request. Inject clock and IDs; use monotonic time for deadlines, UTC wall time for records.

Defaults: maximum eight generation attempts, 180-second run deadline, eight tool calls per generation response, two compactions, USD 1.00 spend admission limit, and 4096 combined completion tokens per request capped by the profile. Summary requests, fallbacks and generation retries consume attempt and cost allowances. Decision calls have separate counters/estimated costs and consume the same run deadline and ledger. Cancel promptly and close owned resources; cancellation is not proof a remote request stopped billing.

## Ledger and admission

Maintain reported settled cost, estimated settled cost, reserved in-flight estimate, and uncertain liability as separate amounts. Before every paid operation, conservatively estimate prompt plus bounded output plus request fees where known. Admit only if remaining estimated allowance covers it. A local decision model may have zero provider charge but its time/compute cost remains a measured evaluation input.

Use a model-aware tokenizer when available. Otherwise use a documented conservative UTF-8-based upper-bound estimate plus framing/tool overhead, validated against supported model fixtures. Never claim a character heuristic is an exact token count. Unknown price/request fee evidence fails paid admission by default; explicit unknown-cost experiments need their own operator policy and report.

After a definitive response replace the reservation with reported/estimated actual cost. After ambiguous completion retain uncertain liability and disallow automatic retry under default policy. Currency and units must match. If reported cost exceeds the allowance, record overshoot and stop new paid work. This is an admission control, not a contractual spend cap: remote fees, delayed reporting and usage errors can exceed estimates.

## Tool loop

Persist the assistant's proposed calls before execution. Validate the whole batch, then execute serially; stop subsequent execution on cancellation/deadline. Associate observations with call IDs and retain original order. A denied or invalid call gets a bounded tool observation permitting correction; the runtime never coerces arguments. Repeated invalid requests consume generation steps and cannot run forever.

For an operational tool failure return a typed observation once; the next generation step may decide how to proceed. On indeterminate side effects terminate and require recovery rather than auto-retry. The initial tools are read-only, but the contract is designed to expose uncertainty before future mutation tools exist.

## Minimal context compaction

Before generation, compare conservatively estimated prompt plus completion reserve with the chosen candidate's usable context. At 80% of usable context, ask System One for defer/concise/detailed. Code overrides defer when the next request cannot fit. Then select a summarisation candidate through the same decision engine and summarisation pool; the candidate must fit the original summary input and budget. Summaries are generation operations.

Pin system/developer instructions, current user request, permission/config constraints, unresolved tool pairs and adapter-required continuation. Summarise only completed earlier conversational units. Preserve provenance references and mark the text as an untrusted summary. Retain the recent two complete generation/tool units where capacity permits. Never split a pending call from its result or summarise opaque continuation data.

Validate summary length, required facts selected from pinned structured state, and next-request fit. Semantic fidelity is evaluated separately; an LLM judge is not a deterministic proof. Failed compaction cannot discard original context; retry only within limits or stop `CONTEXT_LIMIT`. If the summary model cannot fit the source, stop with advice to narrow input; hierarchical summarisation is later work.

## Concurrency

Serial run state mutation and serial tools are deliberate MVP constraints. Independent read-only preparatory tasks may use scoped virtual threads with structured concurrency. No speculative parallel generation, hedged paid calls or unscoped executors. Parent cancellation cancels children, close joins lifetime, and bounded semaphores protect remote and local-service capacity. Cancellation tests use latches/fake servers, not sleeps.
