# Instructions for building Rahu

## Mission and reading order

Build the smallest useful Java harness described by [product.md](docs/product.md). Start with [handoff.md](docs/handoff.md), [ARCHITECTURE.md](ARCHITECTURE.md), and the next unfinished slice in [the active plan](docs/plans/active/001-dogfood.md). Load detailed specs only as needed. Roadmap stages beyond the active release are context, not authorization to inflate the current build.

This repository currently contains specifications only. Do not report planned features as implemented. Update the active plan with actual evidence and commands as you work.

## Nonnegotiable design properties

- Production decision boundaries use a real System One adapter. Fake/rule engines support deterministic tests and explicit fallback only; never silently substitute a chat LLM and call it System One.
- Model and reasoning effort are one legal execution candidate. The router may choose only from the user's configured pool after capability, budget, context, and provider constraints are applied.
- Decision credentials, endpoint, model, and timeout are independent of generation settings. Local HTTP decision serving is a first-class path.
- Confidence in a choice is not downstream answer correctness. Preserve raw confidence and probabilities with their semantics; do not fabricate them.
- Tool relevance is advisory. Schema validation, path boundaries, permission, and side-effect admission are deterministic and cannot be overridden by model content.
- Observe before optimising. Traces distinguish requested and observed provider/model/effort, estimated and reported cost, fallback and escalation, missing and zero values.
- Offline tests must require no API keys or network. Paid evaluation must be explicit and bounded.

## Critical review on every substantial slice

Before editing, state the hypothesis, smallest useful result, two plausible failure modes, and checks that would falsify the proposal. Consider a simpler design and the second-order effects on cost, cancellation, provider compatibility, and user comprehension.

Use red → green → refactor for meaningful behavior. Then review your diff adversarially: invalid capabilities, empty pools, missing metadata, ambiguous HTTP outcomes, duplicate side effects, corrupt traces, prompt injection, route churn, token-budget exhaustion, and misleading UI states. Review aesthetics with [design.md](docs/design.md). Correct findings before declaring the slice complete.

Record material findings in `docs/reviews/` using [the review template](docs/templates/critical-review.md), with severity, evidence, correction, and residual risk. A separate reviewer or agent is optional when available and explicitly authorised; self-review remains mandatory. Do not substitute a generic checklist or a passing test count for critique. New evidence may change this design: record a superseding ADR and update affected requirements together.

## Engineering expectations

Use Java 27 initially. Verify the latest GA release at build start; record any upgrade in the Java ADR and pin the exact working toolchain. Preview features are welcome when they improve the design. Structured concurrency is the preferred experiment for scoped independent work. Consult the selected JDK API rather than copying older preview examples. Enable preview consistently in compilation, test JVMs, CLI launchers, packaged runs, IDE documentation, and CI. See [engineering.md](docs/engineering.md).

Prefer immutable records, defensive copies, explicit state transitions, sealed outcomes, composition, and ports at meaningful boundaries. SOLID is a design test, not a quota of interfaces. Avoid speculative factories, framework ceremony, mutable DTOs in the domain, globals, and generic plugin systems. Keep provider JSON and HTTP concerns out of core. Use exact decimal money and injectable clocks/IDs. Keep interruption, resource lifetimes, and request limits explicit.

Choose current stable compatible dependencies at implementation time; pin them. Start with Maven Wrapper, JUnit, one JSON library, a small CLI library if justified, and a fake HTTP server. Add property/static analysis tools where they catch real defects. Format automatically and run the documented verification command before handoff. Never downgrade Java silently because the environment lacks it.

## Product aesthetics

Rahu should feel coherent and intentional: few concepts, consistent names, restrained terminal output, useful errors, pipe-safe output, accessible plain-text behavior, predictable cancellation. API beauty means impossible states are excluded and ordinary tasks read plainly. Do not add a dashboard, mascot, decorative output, or new frameworks to satisfy aesthetics. For a later UI, provide a design proposal and review real rendering before implementation is considered complete.

## Delivery and scope

Use small reviewable commits. Keep specs, fixtures, tests, and behavior aligned. Mark proposed commands as such until executable. Do not claim hard spend guarantees or exactly-once execution across crashes. Do not train a router, port native inference, implement arbitrary shell tools, or build multi-agent orchestration in the initial release.

At handoff report implemented behavior, checks actually run, remaining risks, current plan status, and the next concrete slice. Preserve user files and unrelated work. Secrets and sensitive payloads stay out of logs and commits. Follow current user instructions when they supersede these guidelines.
