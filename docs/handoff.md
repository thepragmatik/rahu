# Implementation session handoff

The repository is intentionally documentation only. Your first job is to create a tested executable routing slice, not to implement the entire roadmap.

## First action

Read [AGENTS.md](../AGENTS.md), [product.md](product.md), [routing.md](specs/routing.md), and [the active plan](plans/active/001-dogfood.md). Inspect the current repository and any intervening changes. Verify the current GA JDK and tool availability. Java 27 was confirmed during this planning pass; preview use is explicitly authorised by the owner.

Implement S01 and S02 first: Maven Wrapper plus the four modules, one `verify` command, immutable candidate types, capability fixtures, and a fake end-to-end route. Write `routesOnlyAmongConfiguredCandidates` and `neverSelectsUnsupportedEffort` before the selector. Add no live calls to the default test suite.

## Fixed decisions

Own the loop; separate ports; joint model/effort candidates; OpenRouter generation; generic HTTP System One adapter with verified compatibility profiles; default shadow mode; JSON configuration v1; serial read-only tools; JSONL metadata traces; no router training. Preview features may be used in the kernel when their purpose and cancellation tests justify them.

## Choices the implementer must verify

Select/pin current Maven/dependency versions and JDK distribution; verify System One response envelopes against pinned upstream tests; freeze a small live model pool from the actual catalog; confirm provider effort metadata; select a token-counting approach; decide the minimal JDK 27 structured concurrency use after a small compatibility spike. Record these decisions and failure evidence, not just the chosen version.

The examples use synthetic offline models and environment-resolved live IDs. They are not endorsements of model names from the original conversation. No credentials, source payloads, benchmark data, or upstream model implementations are present.

## Definition of a good first handoff

A clean checkout can run offline tests and a fake CLI request. The candidate trace explains exclusions and mode. Preview flags work through packaged execution. Configuration errors are readable. The plan identifies which slices remain. A critical review records meaningful findings and fixes. Do not declare dogfood alpha until tools, compaction, trace replay, and a real bounded smoke run meet their gates.
