# Seven subsystem coverage

This is the release coverage map for Rahu's harness. All seven areas have an explicit MVP decision, implementation owner, test evidence and evolution path. Specification coverage is not implemented capability.

The taxonomy comes from [Harness Engineering section 2.3](https://arxiv.org/html/2609.00006v1#S2.SS3): agent loop, LLM integration, tools/actions, memory/context, safety/permissions, orchestration, extensibility. It describes minimal and richer forms; orchestration can intentionally be absent. Rahu's scope choices below are our design decisions, not claims that the paper validates this product.

## Coverage audit and MVP contracts

| Area | Before this uplift | Required for dogfood alpha | Contract and implementation | Acceptance |
|---|---|---|---|---|
| 1 Agent loop | Detailed bounded runtime | Explicit transitions, terminal outcomes, retry limits, no-progress stop and cancellation | [runtime](specs/runtime.md); core; S06 | A01, A09, A12, A13, A27 |
| 2 LLM integration | Detailed adapters/routing; prompt assembly implicit | Genuine System One classification and joint model/effort selection; OpenRouter generation; versioned prompt assembly and continuation | [routing](specs/routing.md), [System One](specs/systemone.md), [OpenRouter](specs/openrouter.md), [context](specs/context.md); adapters/core; S02/S04/S05/S09 | A02–A07, A10, A18, A23, A29 |
| 3 Tools and actions | Detailed typed read-only tools | Bounded list/read/search, correct schemas/results, deterministic authority and serial batches | [tools](specs/tools.md); core/CLI; S07 | A08, A09, A24 |
| 4 Memory and context | Compaction described; session ownership unspecified | In-process conversational session, ordered context with provenance, pinned constraints, safe compaction and explicit reset | [context](specs/context.md); core/CLI; S09 | A14, A18, A22, A23, A32 |
| 5 Safety and permissions | Checks distributed across tools/runtime/config | One documented admission sequence and trust model; read-only profile; endpoint/root/secret boundaries; no model-created grants | [safety](specs/safety.md); core/adapters/CLI; S06/S07 | A08, A12, A13, A24, A32 |
| 6 Orchestration | Multi-agent work deferred without a subsystem contract | Explicit single-agent-only mode, owned sequencing/cancellation, rejection of delegation; no hidden fan-out | [orchestration](specs/orchestration.md); core/CLI; S06 | A12, A25, A27 |
| 7 Extensibility | Ports implied; registration/version rules absent | Documented compiled-in Java ports, immutable registries, adapter profiles, explicit instruction files; no dynamic code execution | [extensibility](specs/extensibility.md); core/CLI/adapters; S03/S07/S11 | A23, A26, A28, A31 |

The paper's orchestration area concerns sub-agent coordination. Rahu does **not** implement that in alpha; its deliberate absence is tested and future requirements are recorded. Calling ordinary tool-loop sequencing multi-agent orchestration would overstate coverage.

## Cross-cutting surfaces

The CLI/session surfaces and telemetry span these areas. [CLI](specs/cli.md), [observability](specs/observability.md), [configuration](specs/configuration.md), [design](design.md) and [release gates](release-gates.md) govern their integration. Seven conceptual subsystems do not require seven Maven modules. Keep the existing four modules, with ownership boundaries and dependency checks.

## Evolution map

| Area | Alpha minimum | Later evidence-gated direction |
|---|---|---|
| Loop | Serial, bounded, observed | Streaming and persistent recovery without repeating effects |
| LLM integration | Owned HTTP adapters; legal joint routes | Additional adapters, measured caching, native inference |
| Tools/actions | Read-only workspace access | Permissioned writes, sandboxed execution, journaled recovery |
| Memory/context | In-process history and summarisation | Explicit session persistence, retrieval, durable memory with provenance |
| Safety/permissions | Deterministic read-only authority | Scoped approval grants and OS isolation for effects |
| Orchestration | Single agent; delegation unavailable | Budgeted specialists only after quality/coordination evidence |
| Extensibility | Compile-time ports, config and instruction files | Versioned hooks/skills/MCP/plugins with explicit trust and capability controls |

## Acceptance rule

Dogfood alpha requires evidence for every MVP row, including deliberate exclusions. It does not require implementing every future maximum. Use [the autonomous-build contract](autonomous-build.md) to complete M1/M2 incrementally in one initiated build session, and [the release gate](release-gates.md) to decide what can truthfully be claimed.
