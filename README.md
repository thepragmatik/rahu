# Rahu

Rahu is a Java agent harness with a separately configurable System One decision plane. It selects legal execution candidates that combine a generation model, reasoning effort, and provider policy. Java code owns the agent loop, budgets, tool authority, and observability.

**Status:** specification foundation. No runtime, build, benchmark result, or model-quality guarantee exists yet. This repository is the handoff for the implementation session.

## Start here

1. Read [AGENTS.md](AGENTS.md) for implementation instructions and the recurring critical-review workflow.
2. Read [product specification](docs/product.md) and [roadmap](docs/roadmap.md) for scope and release gates.
3. Read [architecture](ARCHITECTURE.md), then the relevant [specifications](docs/README.md).
4. Follow the ordered [implementation plan](docs/plans/active/001-dogfood.md). The [handoff](docs/handoff.md) identifies the first executable slice.

## Product intent

- Dogfood early through a calm, inspectable CLI.
- Use System One decisions for task classification, joint model/effort routing, tool relevance, and summarisation policy.
- Let users define narrow model pools and independently host the decision model locally.
- Measure quality, total cost, and total latency before enabling routing by default.
- Keep generative work in generation models and side-effect authority in deterministic code.
- Develop with Java 27, including justified preview features, TDD, and Effective Java idioms.

The initial implementation will own its loop and use OpenRouter for generation. HTTP adapters isolate System One implementations such as Laya, Kev, and hosted services. Native inference and learned contrastive routing are research stages, not prerequisites.

## Repository map

| Path | Purpose |
|---|---|
| [docs/product.md](docs/product.md) | Requirements, user journeys, MVP boundaries |
| [docs/roadmap.md](docs/roadmap.md) | Stages, dependencies, measurable exit criteria |
| [docs/specs](docs/README.md) | Routing, protocols, runtime, tools, configuration, traces, CLI |
| [docs/engineering.md](docs/engineering.md) | Java, build, tests, concurrency, delivery practices |
| [docs/design.md](docs/design.md) | API and CLI design aesthetics and review rubric |
| [docs/evals/protocol.md](docs/evals/protocol.md) | Quality/cost/latency experiments and promotion gate |
| [docs/research/evidence.md](docs/research/evidence.md) | Primary sources, limits, research hypotheses |
| [docs/adr/README.md](docs/adr/README.md) | Architectural decisions and how to change them |
| [docs/reviews/001-specification-review.md](docs/reviews/001-specification-review.md) | Critical review and corrections to the initial plan |
| [examples](examples/README.md) | Proposed offline and live configuration contracts |
| [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) | Evidence and critical-review requirements |

The four intended Maven modules are `rahu-core`, `rahu-openrouter`, `rahu-systemone`, and `rahu-cli`. Create them when implementing the first slice; empty module skeletons are unnecessary now. Commands in the specs describe the target interface and are not executable in this revision.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Every substantial change needs a hypothesis, observable evidence, a critical review, and corresponding specification updates. Never commit credentials, user traces, or live evaluation data.

License selection remains with the owner; no license has been presumed.
