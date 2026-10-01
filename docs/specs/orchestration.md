# Orchestration specification

## Explicit MVP position

The seven-subsystem taxonomy uses orchestration for spawning/coordinating agents. Rahu alpha deliberately has one agent and no delegation, worker pool, recursive agent, handoff protocol or external-agent bridge. An explicit `orchestration.mode=single` is the only supported mode. Unknown or multi-agent settings fail validation before I/O; model output cannot enable them.

Within the single agent, the run driver owns operation sequencing: classify/relevance → candidates → joint route → admission → generate → validate tools → serial observations → continuation/finish. A summarisation operation is a child operation of the same run with the same ledgers, deadline and authority, not a sub-agent. All operations have parent IDs for observability without importing a workflow framework.

## Ownership and coordination

One driver mutates run state; one active turn owns a session. All paid requests are sequential. No hedging, speculative generation, hidden additional evaluator LLM or parallel tool calls in alpha. Independent preparatory local/read-only work may use structured concurrency when scoped to the owner and independently bounded. Every child must finish/cancel before owner cleanup; it cannot outlive the session or start paid work after cancellation.

Tool batches preserve provider order/IDs. Model switching follows continuation compatibility; routing is not a handoff to another independent agent. Summary routing may select a different generation model without inheriting opaque continuation. Report complete, failed, cancelled or indeterminate outcomes directly; do not hide child failures in a plausible final answer.

## No-progress stop

Add `NO_PROGRESS` as a terminal run reason. The detector stores a bounded fingerprint of each completed proposed tool batch: ordered tool names/versions, canonical arguments excluding call IDs, and observation outcome/content hashes. If the same fingerprint occurs three times in the same turn with no different intervening observation, terminate after that third completed batch without starting a fourth generation/execution cycle. A different observation, different arguments or new user turn resets the streak. Completion answers and summaries do not count as repeated tool batches.

This exact-match guard reduces obvious repetition, not semantic proof of progress. It is secondary to generation-attempt/deadline limits. Repeated errors with differing strings can still exhaust the step limit. Trace the stop reason/count without raw arguments. Compaction cannot erase detector state. Identical call IDs follow deduplication first; new IDs do not evade batch repetition checks.

## Deferred multi-agent requirements

When evidence justifies M8, create a new design before implementation: typed child objective/result, bounded fan-out/depth, isolated context/tool authority, shared atomic run/session cost admission, cancellation propagation, join/failure policy, continuation isolation, and trace parentage. System One may choose a bounded specialist only after code constructs legal workers. Every worker has a stricter or equal capability set; authority never expands by delegation.

Quality gains must exceed coordination cost on held-out tasks. No implicit recursive orchestration or unlimited child budgets. A checkpointed task tree needs explicit recovery/journaling; ordinary offline replay cannot resume it. These are future acceptance constraints, not alpha implementation scope.

## Verification

A25 rejects unsupported delegation and proves no hidden fan-out with counted fake ports; A27 proves exact-repeat termination and reset on a real new observation. A12 proves child cancellation/cleanup and one-owner session semantics. This establishes a deliberate, honest single-agent orchestration position.
