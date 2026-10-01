# Autonomous build contract

The intended execution is one initiated agentic build session that continues through M1 and M2 using all S01–S12 as small verified slices. It is not one giant commit, one untested generation, or a guarantee that external credentials/services are available. Start with [handoff](handoff.md); execute [the active plan](plans/active/001-dogfood.md); finish against [release gates](release-gates.md).

## Completion objective

Deliver a packaged Java CLI that can perform read-only repository assistance with follow-up conversation, genuine System One model/effort routing, tool relevance and compaction, configured OpenRouter generation, explicit limits, traces, offline replay, and a bounded smoke report. Include all seven subsystem MVP decisions from [the coverage map](harness-subsystems.md). Do not stop at an elegant routing library or first fake demo.

Operationally complete means offline tests/packaged demos pass and a new developer has exact setup/run instructions. Live dogfood verified additionally requires real services and the live gate. Cost/quality optimisation validated is a third, later claim requiring M3 statistics. Do not use one status to imply another.

## Preflight once, then keep working

Inspect repository changes and AGENTS guidance; check Java/Maven/git/network/tool availability; identify explicit live config, decision endpoint/model, generation IDs/key and a user-authorised total smoke allowance. Validate non-secret references without printing secrets. Keep a prerequisites record with available, missing or verified status.

If no live services/keys are available, build every offline-verifiable feature and package/run it anyway. At the end report only the unresolved live prerequisites and exact commands to finish verification. Never replace System One with a generative classifier to claim completion. Do not provision GPU infrastructure, train/download large models, subscribe to services, expand the model pool or increase spending to avoid a blocker.

Install/use a verified permitted JDK/dependencies within the authorised workspace/environment when possible. No silent Java downgrade or privileged workaround. If a toolchain/network restriction prevents local checks, finish independent code/docs and use already-authorised CI if available; clearly mark unexecuted checks. Environment workarounds cannot become false evidence.

## Autonomous implementation choices

The agent may select current compatible pinned Maven/JUnit/JSON/CLI versions, package names, small utilities and test patterns, consistent with the four modules and contracts. Recommended defaults: one JSON library such as Jackson, picocli for the growing command surface, JUnit for behavior and JDK/local fake HTTP server. No framework decision is a reason to pause for preferences. Exact versions are verified at build start and recorded in the build manifest.

Use short compatibility spikes before broad implementation: JDK preview compiler/test/launcher agreement, one real protocol fixture parse, one fake tool-continuation exchange. If a spike disproves an assumption, correct specs/ADR/tests coherently. Do not embark on repeated full builds or native-model experiments before narrowing the failure.

Mandatory scope cannot be dropped silently. Routine API/code organisation is the implementer's judgment; material changes to authority, provider semantics or release scope require a recorded decision and, if outside existing authorisation, a focused owner question. Continue independent work while a genuine blocker is unresolved.

## Working loop

For each slice: read the relevant contract → state hypothesis and two failure modes → write failing behavior tests → implement → refactor → run appropriate checks → inspect actual output → critically review → fix findings → update plan/evidence → commit → continue the next eligible slice. One owner controls mutable run/session state. Independent test/read tasks may overlap; do not spawn agents unless separately authorised.

Keep a concise build status file in `docs/plans/active/build-status.md` with current slice, checked commit, exact successful commands, failed checks, unresolved issues and next work. Create it during implementation. Checkpoint source/tests/docs through small git commits as permitted by the build session. On context compaction or resumed work, read this file and current plan before continuing. A checkpoint is not a reason to yield back after S01/S02.

Propose no optional features until the release gate is met. Avoid reopening accepted design decisions without new evidence. Before the final handoff, do a release-wide critical review across the seven areas, not just reviews of individual methods.

## Stable implementation outputs

Required build artifacts/interface at implementation:

- Four Maven modules and a committed Maven Wrapper; clean `./mvnw verify` runs offline checks and packages the CLI.
- `rahu-cli/target/rahu-cli.jar` as the documented runnable distribution, with dependencies included; `bin/rahu` uses the selected JDK and `--enable-preview` to launch it. No shell evaluation of user prompt strings.
- Matching preview flags in compile/test/packaged/CI runs; a packaged offline demo is part of verification.
- Generated `docs/generated/config.schema.json`, prompt/event fixtures, and a build manifest of toolchain/plugins/dependencies/adapter revisions. Generated docs remain identified as generated.
- README quickstart, validated example configs, documented extension example against actual interfaces, offline smoke script and a separate explicit live smoke invocation.
- Sanitised dogfood/release report with gates, versions and critical findings; no keys/private payloads committed.

These are build targets, not artifacts present in the specification revision. The agent should choose the smallest packaging that fulfills them; do not add a native image or container platform.

## Paid work and completion

User-supplied credentials permit connection only within the build session's explicit scope and smoke allowance. A smoke command must require a separately declared total budget and never exceed it by resetting budgets between tasks. Run sequential tasks with aggregate admission/reservations. Missing allowance blocks paid smoke only, not implementation. No adaptive hyperparameter search or broad benchmark matrix for M2.

The final response identifies delivered behavior, seven-area evidence, commands actually passed, live status, cost/latency observation limits, critical findings fixed and remaining prerequisites. No claim that a one-shot instruction guarantees a fully validated model-quality result.
