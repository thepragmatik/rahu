# Implementation session handoff

The repository is intentionally documentation only. Your objective is a usable M2 dogfood release through tested increments in one initiated build session. Begin with a routing slice and continue through the full active plan; future M3–M8 research is outside this build.

## First action

Read [AGENTS.md](../AGENTS.md), [product.md](product.md), [routing.md](specs/routing.md), and [the active plan](plans/active/001-dogfood.md). Inspect the current repository and any intervening changes. Verify the current GA JDK and tool availability. Java 27 was confirmed during this planning pass; preview use is explicitly authorised by the owner.

Implement S01 and S02 first: Maven Wrapper plus the four modules, one `verify` command, immutable candidate types, capability fixtures, and a fake end-to-end route. Write `routesOnlyAmongConfiguredCandidates` and `neverSelectsUnsupportedEffort` before the selector. Add no live calls to the default test suite.

Then continue S03–S12 without treating the first slice as completion. Follow [autonomous-build.md](autonomous-build.md), [the seven-area coverage map](harness-subsystems.md), [the setup runbook](runbooks/dogfood.md) and [release gates](release-gates.md). Maintain checked progress in the repository so context compaction can resume work without repeating completed slices.

## Fixed decisions

Own the loop; separate ports; joint model/effort candidates; OpenRouter generation; generic HTTP System One adapter with verified compatibility profiles; default shadow mode; JSON configuration v1; serial read-only tools; bounded in-process chat; deterministic prompts/trust; explicit compiled-in extensions; JSONL metadata traces; no router training/delegation. Preview features may be used in the kernel when their purpose and cancellation tests justify them.

## Choices the implementer must verify

Select/pin current Maven/dependency versions and JDK distribution; verify System One response envelopes against pinned upstream tests; freeze a small live model pool from the actual catalog; confirm provider effort metadata; select a token-counting approach; decide the minimal JDK 27 structured concurrency use after a small compatibility spike. Record these decisions and failure evidence, not just the chosen version.

The examples use synthetic offline models and environment-resolved live IDs. They are not endorsements of model names from the original conversation. No credentials, source payloads, benchmark data, or upstream model implementations are present.

## Completion definition

A clean checkout passes offline verification and packaged run/chat/replay scenarios. The candidate trace explains exclusions and mode. Preview flags work through packaged execution. Configuration errors are readable. Seven subsystem decisions are verified, and release-wide critique closes high-severity findings. A real bounded smoke additionally establishes live dogfood status. If services are absent, finish offline work and supply a precise live-verification runbook; do not stop at a library.

## Kickoff prompt for the build session

Use [the comprehensive root prompt.md](../prompt.md) to initiate the build. This short version points to the same objective and must not omit the detailed privacy requirements.

```text
Build Rahu from this repository through M1 and M2 to a reasonably usable read-only dogfood release. Read root prompt.md and follow its complete objectives/privacy instructions, AGENTS.md, docs/autonomous-build.md, the seven subsystem map, detailed specs and the active S01–S12 plan. Execute incrementally with TDD, small commits, critical reviews and actual output/design checks; keep going after each slice. Use the latest verified GA Java and justified preview features. Preserve genuine System One routing, independent local decision configuration, legal model/effort pools and deterministic authority. Do not disclose PII, secrets or sensitive content: implement local safe-view/provider dispatch checks before live requests; unknown/restricted inputs fail closed, including routing, summaries, tools, fallbacks and the builder's own tool outputs. Deliver packaged CLI, in-process chat, compaction, traces/replay, extension proof, offline tests and setup docs. Validate R01–R25/A01–A34 and G01–G10. Use only supplied live services, safe inputs and an explicit total smoke allowance. If these are absent, complete all independent offline work and report the exact live blocker. Do not implement later training, native inference, dynamic plugins, effectful tools or delegation. Record evidence and residual risks; never claim fake runs as real dogfood or smoke as routing-quality/privacy guarantees.
```
