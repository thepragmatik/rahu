# Engineering practices

## Java and build

Start with Java 27, verified GA during planning on 1 October 2026. Reverify latest GA at build start and supersede the ADR if upgrading. Pin a working distribution/build in CI and contributor instructions. Use a committed Maven Wrapper, explicit dependency/plugin versions, Maven toolchains/enforcer where useful, and reproducible artifact timestamps. There is no runnable Maven build in the specification revision.

Use preview features deliberately. Compile with the chosen release and `--enable-preview`; configure Surefire JVM arguments, CLI scripts and packaged launchers with the same flag. There is no Failsafe or integration-test phase today, so nothing else needs the flag; if one is added it must carry `--enable-preview` too. As of 2026-10-03 the reactor compiles **no preview bytecode at all** (every class file is major 71, minor 0), so the flag is present for the next API that needs it rather than protecting anything today; `ToolchainAgreementTest` fails the moment real preview bytecode appears. Test the packaged launcher in CI so preview bytecode cannot pass tests and then fail for users. Preserve coverage-agent JVM arguments when configuring preview. Do not use a different JDK to execute preview class files.

Structured concurrency is available but **not adopted**: neither `StructuredTaskScope` nor `ScopedValue` appears in `src/main`, and there are no virtual threads either. Do not read this paragraph as a description of the code. If independent scoped I/O is introduced, use the actual JDK 27 API (`StructuredTaskScope` changed across previews, so the older `ShutdownOnFailure` shape is stale), explicit timeout/cancellation policy, try-with-resources lifetime and tested parent/child cleanup. Virtual threads support blocking HTTP without large executor frameworks. ScopedValue is appropriate for immutable trace metadata, not hidden mutable run state. Incubator Vector API is separate from preview and is unnecessary until native inference experiments need it.

## Effective Java and SOLID

- Immutable domain records must copy lists/maps and validate values; records alone do not make referenced objects immutable.
- Use sealed outcomes for exhaustive state handling and enums for finite simple values. Avoid booleans that hide mutually exclusive states.
- Prefer composition and constructor-injected ports. Make dependencies visible. Avoid inheritance-driven provider frameworks and service locators.
- Separate validation/selection from I/O so pure policy tests are cheap. Keep HTTP DTOs at adapter boundaries.
- Use precise exceptions/outcomes, explicit null/optional semantics, and no broad catch that converts failure to success. Preserve interruption and close streams/responses.
- Use `BigDecimal`/exact units for money, `Duration` for time, and injected clocks for time-dependent behavior.
- Keep public API small. No interface for every class, speculative factory, static mutable singleton, framework just to obtain dependency injection, or generic extension platform before two concrete needs exist.

Dependency inversion matters most at decision, generation, tools, catalog, clocks and persistence boundaries. Single responsibility means understandable change reasons; it does not require splitting a cohesive 100-line policy into ten services.

## Testing strategy

Tests are living specifications. Add one failing behavior test, implement the smallest correct change, then refactor under passing tests. Avoid tests that merely assert a getter returns its constructor value. Use realistic fixture transcripts and named scenarios from [acceptance.md](specs/acceptance.md).

| Layer | What it establishes |
|---|---|
| Pure policy tests | Feasible candidates, budget admission, transitions, fallback and authority |
| Property tests | No unsupported/out-of-pool choices across generated catalogs and pools |
| Adapter contracts | HTTP bodies, vendor envelopes, error/continuation handling using a local server |
| Golden schema tests | Stable config/event versions, backwards reading and explicit missing values |
| Integration tests | Fake decision → fake provider → tools → final answer/terminal trace |
| Concurrency tests | Cancellation/deadline propagation with latches, no leaked child tasks |
| Opt-in live tests | Actual service compatibility and bounded billing, never default CI |
| Evaluations | Statistical quality/cost/latency claims, separate from deterministic unit tests |

Use current JUnit; pin a supported version. Add jqwik or equivalent for candidate/budget invariants if it materially reduces blind spots. Fake HTTP servers must record exact request mapping and allow blocked-body/error tests. No sleeps as synchronisation, network in unit tests, or giant mocks of private implementation. Test both success and realistic ambiguous failures. Run formatter, static analysis appropriate to Java preview, tests, package and docs link/JSON checks through one documented `./mvnw verify` entry point once built.

Coverage is diagnostic rather than a numeric product goal. Critical admission/authority transitions must be exercised. Mutation testing may be added when it reveals weak policy tests; it is not a prerequisite for an empty kernel.

## CI and dependencies

Create CI with JDK 27, preview-enabled verification, offline integration fixtures and a packaged demo smoke check. Pin action revisions; choose supported current revisions at implementation rather than copying stale examples. Minimal token permissions, no secrets on untrusted PRs, bounded jobs, dependency update workflow and artifact retention. Keep live eval in a manually invoked workflow with explicit budgets and trusted secrets.

Select one JSON library and stable CLI library where useful. Avoid Spring/agent frameworks in the kernel. Lock/pin transitive resolution where practical; review new dependency purpose, license, lifecycle and size. Generate an SBOM when distributing releases. Do not choose an open-source project license on behalf of the owner.

## Delivery discipline

Use small commits that correspond to verified slices. A PR explains problem and resulting behavior, hypothesis, tests/commands actually run, evidence, critical review and residual risks. Update ADRs on consequential decisions. Keep the plan current; move completed plans only after their release gate is verified. Never write that a live run succeeded without a receipt/trace. Document environment blockers precisely and retain unfinished gates.

All substantial implementations include a post-change critique using [the review template](templates/critical-review.md). Review performance after correctness with JFR/JMH only where measurement addresses a concrete hypothesis. Benchmark router end-to-end HTTP latency and service compute latency separately. Avoid expensive train/build loops before a small prototype falsifies the uncertainty.

## Continuous build and release

The initiated build targets the whole M2 release, not a first-slice handoff. Follow [autonomous-build.md](autonomous-build.md), check every [subsystem](harness-subsystems.md), and satisfy [release gates](release-gates.md). Stable packaging/launcher, generated config schema, suite/report formats, actual extension example and run/chat/replay quickstart are implementation deliverables. Verify offline/live status separately and checkpoint actual evidence through context compaction.
