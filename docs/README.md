# Documentation index

These documents are the implementation contract for Rahu. Requirements and scenarios are normative; research hypotheses are explicitly exploratory. Change a contract by updating its tests, specification, and decision record together.

## Product and execution

- [Product specification](product.md)
- [Roadmap](roadmap.md)
- [Build handoff](handoff.md)
- [Ordered dogfood implementation plan](plans/active/001-dogfood.md)
- [Engineering practices](engineering.md)
- [Design aesthetics](design.md)

## Detailed specifications

| Specification | Focus |
|---|---|
| [Routing](specs/routing.md) | Feasibility, effort semantics, uncertainty, route stability |
| [System One](specs/systemone.md) | Typed questions, HTTP compatibility, response validation |
| [OpenRouter](specs/openrouter.md) | Catalog, generation protocol, costs and continuation |
| [Runtime](specs/runtime.md) | Bounded loop, cancellation, budgets, compaction |
| [Tools](specs/tools.md) | Registry, permissions, filesystem boundaries, side effects |
| [Configuration](specs/configuration.md) | Schema, precedence, defaults, offline/live modes |
| [Observability](specs/observability.md) | Event contract, privacy, replay guarantees |
| [CLI](specs/cli.md) | Commands, streams, errors, interaction |
| [Acceptance scenarios](specs/acceptance.md) | Executable behavior specifications and failure cases |

## Evidence and governance

- [Evaluation protocol](evals/protocol.md)
- [Primary research evidence](research/evidence.md)
- [Architectural decisions](adr/README.md)
- [Initial critical review](reviews/001-specification-review.md)
- [Critical review template](templates/critical-review.md)
- [Experiment template](templates/experiment.md)
- [Examples](../examples/README.md)

## Document authority

The latest user instruction governs scope. Within the repository, product requirements define intent, detailed specs define observable behavior, ADRs explain decisions, and plans order implementation. Research notes do not override contracts. On contradictions, record the conflict and resolve it in all affected documents before implementing. Do not silently follow whichever file was read last.

Status words mean: **proposed** is design; **implemented** needs code/tests; **verified** needs cited execution evidence. A green checkbox requires actual verification, never merely the presence of a specification.
