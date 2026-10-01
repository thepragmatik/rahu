# Rahu product specification

## Goal

Give a developer a small Java harness they can use to develop Rahu itself, while investigating whether fast System One decisions improve cost and latency without unacceptable quality loss. Its first audience is the project owner and coding agents working with the owner. It is a research instrument and useful CLI before it is an SDK platform.

## User journeys

**First use:** a developer runs an offline demo, sees a complete route/tool/termination trace, and learns the concepts without API keys. For live use, they configure a System One endpoint and a generation pool, validate it, inspect candidates, then submit a bounded task.

**Constrained routing:** a developer allows two or three generation models with selected supported efforts. Rahu asks System One to choose a legal candidate, with a separately configured baseline/fallback. Invalid or uncertain decisions never enlarge the pool.

**Local decisions:** the developer points the decision adapter at a local service. Only generation goes to OpenRouter; hosted decision credentials are unnecessary. Router/network/model latency are measured separately.

**Dogfooding:** Rahu reads repository files through bounded read-only tools, answers an architecture question, summarises a long transcript when needed, and leaves an inspectable trace. Arbitrary shell execution and repository mutation are deferred.

**Follow-up:** the developer opens an in-process chat, asks a repository question and follows up using remembered facts. Clear status/reset semantics preserve cost liabilities. The harness does not promise cross-process resume.

**Experimentation:** the developer compares frozen baseline, shadow, and active routing runs. The report includes successes and failures, router overhead, total spend, and limits of the evidence.

## Required behavior

| ID | Requirement | Acceptance |
|---|---|---|
| R01 | Own a bounded single-agent Java runtime and CLI | A01, A12, A13 |
| R02 | Use a genuine System One adapter for live classification and model/effort routing | A02, A06 |
| R03 | Jointly select model and supported reasoning policy | A03, A04 |
| R04 | Keep decision endpoint/model/auth independent from generation | A02 |
| R05 | Enforce user-defined pools in all routes, retries and fallbacks | A03, A05 |
| R06 | Provide defaults for operational settings and actionable errors for necessary user inputs | A01, A15 |
| R07 | Use System One for advisory tool relevance; Java validates/authorises execution | A08, A09 |
| R08 | Use System One to select compaction policy and summary route; generation writes summaries | A14 |
| R09 | Record requested/observed routes, confidence semantics, usage, cost, latency and terminal reason | A10, A16 |
| R10 | Support safe offline replay and frozen evaluation inputs | A11, A17 |
| R11 | Start in shadow mode and expose explicit active routing | A07 |
| R12 | Keep continuation data, tool-call/result ordering and side-effect semantics correct | A09, A18 |
| R13 | Build with latest verified GA Java, preview features permitted and tested | A19 |
| R14 | Apply TDD and recurring critical review to substantial changes | A20 |
| R15 | Bound filesystem reads, output size, time, token allowance and estimated spend | A08, A12, A13 |
| R16 | Support calm, accessible, scriptable CLI behavior | A15, A21 |
| R17 | Retain bounded in-process session context with aggregate turn/spend limits | A22, A32 |
| R18 | Assemble versioned prompts with provenance/trust and bounded System One projection | A23, A29 |
| R19 | Apply one deterministic authority/admission sequence to every operation | A24 |
| R20 | Declare single-agent orchestration, prohibit hidden delegation and stop exact repeated no-progress loops | A25, A27 |
| R21 | Provide documented compiled-in extension ports/registries without bypassing policy | A26, A28 |
| R22 | Deliver reproducible packaging, setup and usable run/chat/error output | A28, A31 |
| R23 | Continue an autonomous incremental build to M2 with release-wide critical review | A20, A30 |
| R24 | Produce schema/fixture/suite/release evidence with honest offline/live status | A31 |

## Initial release boundary

The first dogfood release includes HTTP System One routing, OpenRouter catalog/generation, configured pools, shadow and opt-in active modes, bounded in-process text conversations, versioned context/instruction assembly, typed read-only filesystem tools, minimal context compaction, deterministic authority, compiled-in extension proofs, JSONL traces, offline replay, packaging/setup and a smoke/release report. Classification, tool relevance, and compaction judgments are bounded System One questions. Arbitrary tool JSON arguments and summaries remain generative work.

Begin with non-streaming generation. First-token latency is unavailable in that implementation and must be reported as unavailable. Streaming is a later slice with explicit incomplete-response and cancellation semantics. A dashboard, persistent vector memory, skills platform, MCP, native ModernBERT inference, training, write/shell tools, and multi-agent delegation are outside the initial release.

## Success criteria

The first success is a reproducible live dogfood run through a real decision service and OpenRouter, with zero side-effect tools, explicit budgets, complete trace structure, and clear failure outcomes. The runtime and adapters must pass all offline acceptance tests. Dynamic routing remains experimental until the evaluation promotion gate is satisfied. No percentage cost reduction or millisecond latency promise is a product claim yet.

[Seven subsystem coverage](harness-subsystems.md) is mandatory at MVP depth. [G01–G10](release-gates.md) distinguishes offline complete from live dogfood verified; absent credentials block the live claim, not independent implementation. This alpha helps analyse/review a repository and proposes code as text; it does not autonomously edit/test that repository. The build session is a separate agent with its own authorised engineering tools.

## Product principles

Expose one coherent candidate concept. Prefer observable controls over prompt magic. Minimise configuration surprises and expensive build/training cycles. Let failed hypotheses remove complexity. Keep defaults useful without silently selecting unapproved models. Treat user data and traces as private by default. Make quality tradeoffs visible rather than assuming cheaper is better.
