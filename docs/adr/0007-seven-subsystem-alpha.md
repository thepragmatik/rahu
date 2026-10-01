# Seven subsystem dogfood scope

Date: 2026-10-01. Status: Accepted.

## Context

The owner wants one initiated autonomous build to reach a reasonably usable dogfood harness through increments. The first handoff specified the routing/tool kernel well but allowed first-slice stopping and left some subsystem contracts implicit.

## Decision

Require explicit coverage of all seven canonical areas with a minimal implementation or deliberate absence. Add in-process multi-turn memory, deterministic prompt assembly/trust/projection, one authority pipeline, single-agent-only orchestration and compiled-in extension contracts. Retain four modules, read-only tools, no dynamic plugins, no cross-process recovery and no sub-agents. Build S01–S12 continuously to M2, with slice/release reviews and independent offline/live gates.

## Alternatives and consequences

Only routing plus one-shot requests is cheaper but insufficiently usable for follow-up dogfooding. Building full memory/MCP/sandbox/multi-agent systems now would overwhelm the MVP. In-process sessions add state/aggregate-ledger requirements and several tests; they avoid persistence/recovery infrastructure. A single-agent position honestly covers the orchestration design decision without claiming delegation.

## Validation and revisit

Acceptance A22–A32 supplements existing tests. G01–G10 defines release evidence. Full live status needs supplied service/key/pool/budget; autonomous work completes all independent offline deliverables despite missing prerequisites. Revisit persistent sessions/effects/delegation only after dogfood evidence and separate designs.

Source: [seven subsystem taxonomy](https://arxiv.org/html/2609.00006v1#S2.SS3). Its survey motivates the coverage map; it does not validate Rahu's quality or guarantee autonomous build success.
