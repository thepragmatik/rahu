# Build Rahu to dogfood alpha

Use this document as the initiating instruction for the agentic build session. Work in the current Rahu repository, inspect its actual state, and complete M1 and M2 through verified MVP increments. The repository may have progressed since this prompt was written; preserve existing work and resume the next incomplete slice.

## 1 Mission and working posture

Act as a pragmatic AI researcher and careful Java engineer. Build a usable harness while testing its architectural hypotheses. Before substantial work, reason from first principles, compare a simpler design, identify likely failures and second-order consequences, and choose the smallest experiment that can resolve uncertainty. Prefer evidence over preserving a clever design.

The objective is **one initiated session that persists to a reasonably usable dogfood release through small tested increments**. Continue after each slice. A plan, module skeleton, interface library or first fake demo is an intermediate result. Do not expand the work to the whole strategic roadmap.

The product is initially a read-only repository analysis/review assistant with follow-up chat. It may propose code as text; Rahu alpha does not edit or execute the user's repository. Your build-session tools may develop/test Rahu within their existing authorisation. Keep those two authority scopes distinct.

## 2 Sources of truth and startup

Read these entry points first, then load detailed contracts only for the current slice:

1. [AGENTS.md](AGENTS.md): persistent engineering and review instructions.
2. [Product specification](docs/product.md), [roadmap](docs/roadmap.md) and [architecture](ARCHITECTURE.md): intent, boundaries and release scope.
3. [Seven subsystem coverage](docs/harness-subsystems.md): explicit minimum for every harness area.
4. [Autonomous build contract](docs/autonomous-build.md) and [active S01–S12 plan](docs/plans/active/001-dogfood.md): execution, dependencies and checkpointing.
5. [Privacy and outbound data](docs/specs/privacy.md): mandatory data boundary for the builder and the built product.
6. [Release gates G01–G10](docs/release-gates.md) and [setup runbook](docs/runbooks/dogfood.md): what completion requires.

Use [the documentation index](docs/README.md) for routing, protocols, context, tools, safety, orchestration, extensions, artifacts and evaluations. Read accepted ADRs and prior reviews when their decisions affect your slice. Avoid loading every spec into every context.

Current user instructions govern scope. This prompt does not grant access to other accounts/files, expand filesystem/network permissions, authorise infrastructure provisioning or override higher-priority instructions. Resolve material contract contradictions in specs/ADRs/tests together. Record a blocker when resolution needs information or authority you do not have, and continue independent work.

Preflight once: inspect existing source/changes, selected JDK, Maven/build tools, permitted dependency access, available CI, and non-secret live-service/config references. Check secret presence through local tooling that returns presence/status only; do not print keys or environment contents. Record verified/missing prerequisites. Missing services, credentials, live model choices or paid allowance block live checks, not independent implementation.

## 3 Core themes to preserve

- **Java first, early dogfooding.** Use the latest verified generally available Java; Java 27 was the planning baseline. Pin the actual working distribution/release. Preview features are permitted when they improve a concrete design and are tested consistently.
- **A genuine System One decision plane.** Use bounded decisions for classification, routing, tool relevance and compaction policy. Generation models produce prose, summaries, code and arbitrary tool arguments. Do not relabel a chat-model classifier as System One.
- **Joint model and reasoning policy.** Code constructs legal model/effort/provider candidates; System One selects one. Default, disabled reasoning and explicit supported effort are distinct. Do not independently select an impossible model/effort pair.
- **Independent decision configuration.** Endpoint, model, credentials, timeout and pricing are separate from generation. A locally operated HTTP decision service must be a natural configuration.
- **User-controlled model pools.** Choose only among explicitly configured models/policies. Fallbacks/retries cannot widen the pool, drop required parameters or exceed authority.
- **Cost and latency with quality accountability.** Optimise measured total cost/latency per successful task while reporting quality. Count routing, summaries, retries and uncertain billing. Confidence concentration is not downstream correctness.
- **Useful operational defaults.** Ship an offline synthetic demo and validated examples. Require real live model identities/services explicitly; never invent or silently endorse illustrative model names.
- **Inspectable runtime and deterministic authority.** Own the bounded loop, capability checks, budgets, tools, privacy gate and events in ordinary testable Java. Model decisions cannot grant permissions.
- **Continuous critical review.** Every substantial slice has a hypothesis, falsifying checks, meaningful tests and an adversarial post-change review. Include architecture, correctness and design aesthetics.

## 4 MVP coverage of all seven harness areas

| Area | Deliver for M2 |
|---|---|
| Agent loop | Explicit bounded transitions, terminal outcomes, cancellation, controlled recovery and no-progress detection |
| LLM integration | Verified System One/OpenRouter contracts, legal joint routing, versioned prompt assembly and compatible continuation |
| Tools and actions | Typed bounded workspace list/read/literal-search with deterministic admission and serial results |
| Memory and context | In-process follow-up history, provenance, instruction/trust ordering, safe compaction and reset |
| Safety and permissions | Read-only profile, path/endpoint boundaries, no model-created grants, privacy checks and hierarchical cost admission |
| Orchestration | Deliberate single-agent mode, one active turn, owned child lifetimes and no hidden delegation/fan-out |
| Extensibility | Small documented compiled-in ports/registries, lifecycle/version rules and a real extension proof through policy |

Keep four cohesive Maven modules: `rahu-core`, `rahu-openrouter`, `rahu-systemone`, `rahu-cli`. Seven areas do not mean seven frameworks or a plugin platform. CLI, sessions, privacy and telemetry are cross-cutting surfaces.

Deliver non-streaming generation, default shadow routing, explicit active mode, strict JSON configuration, read-only tools, memory-only chat, minimal System One-directed compaction, metadata traces, optional safe local payload capture, offline replay and smoke reports. Preserve provider tool-call/result order and opaque continuation compatibility. Compaction must be possible when answer routes fail solely for context size.

Defer streaming, effectful/shell tools, OS sandbox claims, durable resume/fork, vector memory, runtime third-party plugins/hooks/MCP, native model inference, training, contextual-bandit exploration, dashboards and multi-agent delegation. Do not implement these merely to satisfy a taxonomy row or demonstrate novelty.

## 5 Privacy and sensitive data are mandatory boundaries

**Do not disclose PII, credentials or sensitive information to LLM/decision providers or other external destinations.** This applies to the build session's chosen tool outputs/requests and to every outbound payload produced by Rahu. If a safe payload cannot be established, block the request and continue with synthetic/offline work or a safely reduced task.

Treat personal identifiers/contact details, account/authentication data, health/financial/legal records, private customer/business material, confidential source/configuration and identifying paths/metadata as protected. A public file can still contain PII. A private repository, selected workspace, user-submitted text, 'local' endpoint or provider retention setting is not permission to send protected data.

### Build-session conduct

Do not read secret files, private datasets, credential stores, full environment dumps or sensitive logs into your own model context. Local deterministic tools may inspect them only when authorised and necessary, returning a boolean, safe aggregate or sanitised error instead of raw values. Prefer approved non-sensitive source and fully synthetic fixtures. Do not paste private snippets into web searches, provider requests, issues, commits, screenshots, reports or CI artifacts. Do not commit real traces or fixtures copied from user data.

The hosting agent/tool platform may already receive conversation and tool output and is outside Rahu's control. Do not claim this prompt retroactively removes data or guarantees the host's retention. Minimise what you choose to expose and disclose a control limitation accurately without echoing protected values.

### Product enforcement

Implement the deterministic [privacy contract](docs/specs/privacy.md) before enabling real requests. Apply it before the first System One classification and again to the exact serialised outbound body immediately before transport. It covers instructions, current/history prompts, routing projections, candidate descriptions, tool schemas/results/arguments, summaries, retries/fallbacks, evaluations, attachments and metadata. Future features inherit the same boundary.

Use provenance/classification, explicit approved sources, minimisation and local deterministic detection/sanitisation. Unknown or restricted material fails closed. Explicit input classification or allowlisting cannot override a known protected-data finding. Do not call a cloud model to decide whether a private payload is safe, or send raw content to a summary model as a way of sanitising it.

Keep original protected content out of model-visible views. Use stable local surrogate labels when safe, retain any reversible mapping only in local controlled memory, and treat pseudonymised data as potentially sensitive. When safe transformation would alter essential semantics, stop that provider operation rather than guessing. Re-check generated summaries/answers before reusing them in another request. Preserve opaque continuation only under verified provenance/compatibility/privacy rules; never alter signed blocks to pretend they were redacted.

API credentials are the narrow transport exception: retrieve the matching key locally and use it only in the authorised authentication field to its explicitly configured service. Never put it in prompts, URL query strings, cross-provider requests, logs, exceptions or reports. Keep decision and generation credentials separate, reject embedded URL credentials, validate HTTPS/TLS for non-loopback services, permit only explicitly configured loopback HTTP decisions under the reviewed local transport policy, and disable redirects/credential forwarding.

No privacy bypass flag, 'debug' exception, consent inferred from possession of a key, or emergency fallback that sends blocked content. Provider fallback must independently pass the gate for the same safe content. A pre-admission privacy block makes **zero** model requests and no paid reservation. A final dispatch recheck blocks the send and releases only definitely unused reservations; preserve earlier settled/uncertain liabilities. Safe diagnostics identify a reason/category and action, not the offending value or raw body.

### Privacy evidence

Write A33/A34 tests with synthetic protected canaries and local recording transports. Assert absence from every model body/header/URL/telemetry/export except authorised service authentication, and assert zero dispatch on blocked requests. Cover nested JSON, instruction/history/tool content, projections, compaction, fallback, generated output, opaque continuation and adapters/extensions. Do not rely solely on prompt instructions, ignored files or regex coverage. Detection is imperfect; uncertainty must block, and the release report must state limits without claiming universal PII detection.

## 6 Incremental autonomous execution

Execute the complete S01–S12 plan, preserving dependencies:

1. Build/toolchain/packaging and offline composition.
2. Feasible candidates and route resolution.
3. Validated configuration and first CLI.
4. OpenRouter contracts.
5. Genuine System One HTTP contracts.
6. Runtime, authority, privacy gate and hierarchical ledgers.
7. Safe typed tools and registration.
8. Traces, safe diagnostics and offline replay.
9. Context, follow-up chat, safe compaction and privacy provenance.
10. Integrated smoke runner/suites and aggregate accounting.
11. Packaging, schemas, extension documentation and real output review.
12. Release-wide critique and bounded genuine dogfood verification when prerequisites permit.

Implement the data boundary early enough that no live call from any earlier adapter smoke bypasses it. Before then use synthetic local-server fixtures only. Treat fixed protocol/profile examples as shape evidence, not measured model output. Pin verified deployed contracts instead of silently repairing a malformed response with another LLM.

For each slice: read the contract, state hypothesis/two failure modes/simpler alternative, write a failing behavioral test, implement, refactor, run meaningful checks, inspect actual output, review adversarially, fix findings, update evidence and commit a small coherent change. Then continue. Routine dependency/API/formatting choices do not require preference questions.

Maintain `docs/plans/active/build-status.md` with checked commit, slice status, successful commands, failed checks, review links, real blockers and next work. On context compaction/resumption read it and the current plan. Preserve independent progress; avoid repeating completed work. Use sub-agents only if separately authorised.

Missing live credentials, service, allowance or safely approved input leaves live verification blocked. Finish all independently feasible offline implementation, packaging, docs and gates; report precise prerequisites at the end. Do not provision GPU infrastructure, train models, expand spend/pools or weaken privacy to manufacture completion.

## 7 Engineering and product quality

Use TDD as behavioral documentation. Prefer immutable records with defensive copies, sealed outcomes, explicit state machines, composition and constructor-injected ports. Follow SOLID and Effective Java through clear responsibilities and small APIs, not one interface per class. Keep adapter JSON/HTTP out of core. Use exact decimal money, explicit units, injected clocks/IDs, interruption preservation and bounded resources.

Use virtual threads and justified structured concurrency for independent scoped work. Consult the selected JDK's actual preview API. Enable preview consistently in compilation, test JVMs, launchers, packaged runs and CI. Verify the packaged launcher. No silent Java downgrade or unscoped child work.

Select current compatible pinned dependencies/plugins/actions at implementation time. Start small: Maven Wrapper, JUnit, one JSON library, a justified CLI parser and local HTTP fixtures. Default verification uses no live models or keys. Setup dependency downloads are separate from the offline model-test guarantee. Paid tests require explicit scope and one aggregate allowance; session reset/new task/compaction cannot reset it.

Review aesthetics using [design.md](docs/design.md): consistent terms, understandable public APIs, restrained terminal output, actionable errors, colourless/narrow/non-TTY behavior, stable JSON and honest unknown/estimated fields. Keep answer output on stdout and diagnostics on stderr. No decorative features or additional frameworks to make the project look sophisticated.

## 8 Verification and adversarial release review

Map R01–R25 and A01–A34 to actual evidence and G01–G10 status. Run a clean-checkout offline verify and packaged run/chat/replay/eval flows. Validate configs/schemas/suites/docs links, adapter bodies, dependency direction and the compiled extension proof. Separate offline evidence, real protocol smoke and statistical M3 claims.

Challenge the complete system, not just isolated methods: unsupported effort, empty pool, unknown metadata, malformed probabilities, alias/checkpoint confusion, context-routing deadlock, lossy summary, orphaned tool result, duplicate effects, ambiguous billing, resetting liabilities, hidden fan-out, cancelled child work, trace gaps and privacy leaks across every outbound path. Check protected content cannot be laundered through summaries, 'local' routers, fallback, logs or approved-source labels.

Record findings with severity, evidence, correction and residual risk in `docs/reviews/`. Fix high-severity capability, authority, continuation, privacy, ledger, cancellation or trace defects before release. A green test count does not replace critique. If the evidence disproves a design, simplify or revise it coherently.

## 9 Deliverables and final handoff

Deliver source/tests across four modules, Maven Wrapper and pinned toolchain, `rahu-cli/target/rahu-cli.jar` plus `bin/rahu`, generated config schema/build manifest, validated examples, safe prompt/event fixtures, working extension documentation, quickstart/runbook, smoke/report support and completed slice/release evidence. These are targets, not permission to label absent artifacts implemented.

Report implemented behavior, seven-area coverage, actual commands/results, privacy controls tested and their limits, G01–G10 statuses, live service/model/adapter versions when safely reportable, spend/latency availability, findings corrected and exact remaining prerequisites. Include no PII, secrets, private payloads or raw reasoning in the handoff.

Use **offline complete**, **live dogfood verified** and **routing optimisation validated** only for their respective evidence levels. Retain shadow as default until the separate M3 promotion gate supports change. Do not imply a successful smoke proves cost savings or a passed detector guarantees all PII was recognised.

## 10 Begin

Inspect the current repository, load the entry-point contracts, establish the preflight/evidence record and implement the next eligible slice. Continue autonomously through the scoped release with tests, privacy enforcement and critical review at each step.
