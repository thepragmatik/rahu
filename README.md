# Rahu

Rahu is a Java agent harness with a separately configurable System One decision plane. It selects legal execution candidates that combine a generation model, reasoning effort, and provider policy. Java code owns the agent loop, budgets, tool authority, and observability.

**Status:** dogfood-alpha implementation (S01–S10 complete, S11 packaging). The CLI builds, 564 tests pass offline, and the read-only product surface (demo, config, route inspect, chat, eval, traces, replay) works. No live-model dogfood or benchmark claim yet — G09 live verification is a separate gate.

## Quickstart (offline, no keys needed)

Requirements: JDK 27 (`export JAVA_HOME=/path/to/jdk27`), network once for Maven dependencies.

```bash
./mvnw verify          # build + all offline tests
./bin/rahu demo        # deterministic offline run; answer on stdout, trace under .rahu/runs/
./bin/rahu config validate --config examples/offline.json
./bin/rahu route inspect --config examples/offline.json --prompt "Explain the planned modules"
./bin/rahu eval --suite docs/evals/suites/smoke-v1.json --config examples/offline.json
```

Inspect and replay what `demo` just wrote:

```bash
./bin/rahu trace inspect .rahu/runs/<runId>   # read-only; reports completeness, never payloads
./bin/rahu replay .rahu/runs/<runId>          # re-derives the routing decision offline
```

`replay` needs a run that both **routed** and **captured** its inputs, so it reports
`UNAVAILABLE` (exit 3) on an ordinary offline run and says which of the two was missing.
That is the honest answer, not a failure: offline mode resolves nothing, so there are no
routing inputs to replay. Set `trace.capture=payloads` on a config that actually routes.

## Quickstart (live, OpenRouter + local decision service)

1. `cp .env.example .env` and fill it in (gitignored; loaded at startup — a real shell export overrides it). That means **four** values: `OPENROUTER_API_KEY` plus `RAHU_DECISION_MODEL`, `RAHU_FAST_MODEL` and `RAHU_QUALITY_MODEL`. The last three are interpolated by `examples/live-local-systemone.json`, and `config validate` refuses to load it while any is unset — naming the one it wanted, so a partial fill is reported rather than silently defaulted.
2. Point `config.local.json` (copy from `examples/live-local-systemone.json`) at your decision service and model pool. A ready pool of cheap models ships in `config.local.json` (Mistral Nemo, Qwen3-30B-A3B, gpt-oss-20b, Granite micro — all under $0.05/1M input tokens).
3. Validate without billing: `./bin/rahu config validate --config config.local.json` (add `--live-check` for catalog checks).
4. Chat (bounded by `session.maxCostUsd`): `./bin/rahu chat --config config.local.json --input-classification approved-nonsensitive`.
   The flag is required on a first run: `privacy.inputClassification` defaults to `unknown`, and strict mode then refuses **every** prompt with `privacy blocked (unknown-provenance)` and exit 4, regardless of content. Pass it only after assessing your prompt as free of protected data; the flag records an assessment and is not a bypass of protected-data detection.

Privacy: `privacy.mode=strict` blocks unknown/provenance-unclear content before any model call; see [docs/specs/privacy.md](docs/specs/privacy.md). Live execution of eval requires explicit `--live --max-cost-usd` and G09 prerequisites.

## Start here

For an autonomous build session, use the root [prompt.md](prompt.md). It captures objectives, scope, execution and the mandatory privacy boundary; the linked specs remain the detailed build contract.

1. Read [AGENTS.md](AGENTS.md) for implementation instructions and the recurring critical-review workflow.
2. Read [product specification](docs/product.md) and [roadmap](docs/roadmap.md) for scope and release gates.
3. Read [the seven subsystem coverage map](docs/harness-subsystems.md), [architecture](ARCHITECTURE.md), then the relevant [specifications](docs/README.md).
4. Follow the ordered [implementation plan](docs/plans/active/001-dogfood.md) continuously through M2. The [handoff](docs/handoff.md) supplies the kickoff instruction, [autonomous-build contract](docs/autonomous-build.md) defines persistence, and [release gates](docs/release-gates.md) define completion.

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
| [docs/harness-subsystems.md](docs/harness-subsystems.md) | Explicit MVP decisions and evidence for all seven areas |
| [docs/autonomous-build.md](docs/autonomous-build.md) | One initiated build completed through verified increments |
| [docs/release-gates.md](docs/release-gates.md) | Offline readiness, real dogfood and separate optimisation claims |
| [docs/specs/privacy.md](docs/specs/privacy.md) | Safe outbound views, protected data and provider dispatch gates |
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
