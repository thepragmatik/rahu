# Documentation index

These documents are the implementation contract for Rahu. Requirements and scenarios are normative; research hypotheses are explicitly exploratory. Change a contract by updating its tests, specification, and decision record together.

## Product and execution

- [Complete initiating build prompt](../prompt.md)
- [Product specification](product.md)
- [Roadmap](roadmap.md)
- [Build handoff](handoff.md)
- [Seven subsystem coverage](harness-subsystems.md)
- [Autonomous build contract](autonomous-build.md)
- [Dogfood release gates](release-gates.md)
- [Setup and verification runbook](runbooks/dogfood.md)
- [Ordered dogfood implementation plan](plans/active/001-dogfood.md)
- [Engineering practices](engineering.md)
- [Design aesthetics](design.md)

## Visual architecture documentation

Self-contained HTML with hand-authored SVG diagrams. No CDN, no remote font, no
telemetry: these pages make no network request, so they render identically offline.

- [Overview](architecture-html/index.html) — what Rahu does and what is not finished
- [Architecture](architecture-html/architecture.html) — layers, modules, planes, candidates, the run loop and the ledger
- [Decision records](architecture-html/decisions.html) — the eight ADRs and what was deliberately deferred
- [Audit evidence](architecture-html/evidence.html) — defects found across three review rounds, and the stale claims that were caught
- Regenerate diagrams: `python3 docs/architecture-html/make_diagrams.py`

## Detailed specifications

| Specification | Focus |
|---|---|
| [Routing](specs/routing.md) | Feasibility, effort semantics, uncertainty, route stability |
| [System One](specs/systemone.md) | Typed questions, HTTP compatibility, response validation |
| [OpenRouter](specs/openrouter.md) | Catalog, generation protocol, costs and continuation |
| [Runtime](specs/runtime.md) | Bounded loop, cancellation, budgets, compaction |
| [Memory and context](specs/context.md) | Sessions, prompt assembly, trust, projection and compaction |
| [Safety and permissions](specs/safety.md) | Common authority/admission sequence and explicit limits |
| [Privacy and outbound data](specs/privacy.md) | Mandatory safe views, classification/provenance, blocked dispatch and credentials |
| [Orchestration](specs/orchestration.md) | Deliberate single-agent mode, coordination and no-progress guard |
| [Extensibility](specs/extensibility.md) | Compile-time ports, registries, instruction inputs and evolution |
| [Tools](specs/tools.md) | Registry, permissions, filesystem boundaries, side effects |
| [Configuration](specs/configuration.md) | Schema, precedence, defaults, offline/live modes |
| [Observability](specs/observability.md) | Event contract, privacy, replay guarantees |
| [CLI](specs/cli.md) | Commands, streams, errors, interaction |
| [Acceptance scenarios](specs/acceptance.md) | Executable behavior specifications and failure cases |
| [Artifact contracts](specs/artifacts.md) | Checked wire envelope, evaluation suites/reports and generated schemas |

## Evidence and governance

- [Evaluation protocol](evals/protocol.md)
- [Primary research evidence](research/evidence.md)
- [Architectural decisions](adr/README.md)
- [Initial critical review](reviews/001-specification-review.md)
- [Seven subsystem and autonomous build review](reviews/002-seven-subsystem-build-review.md)
- [Build prompt and outbound privacy review](reviews/003-build-prompt-review.md)
- [Critical review template](templates/critical-review.md)
- [Experiment template](templates/experiment.md)
- [Examples](../examples/README.md)

## Document authority

The latest user instruction governs scope. Within the repository, product requirements define intent, detailed specs define observable behavior, ADRs explain decisions, and plans order implementation. Research notes do not override contracts. On contradictions, record the conflict and resolve it in all affected documents before implementing. Do not silently follow whichever file was read last.

Status words mean: **proposed** is design; **implemented** needs code/tests; **verified** needs cited execution evidence. A green checkbox requires actual verification, never merely the presence of a specification.
