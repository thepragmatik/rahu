# System One decision plane

Date: 2026-10-01. Status: Accepted.

## Context

System One implementations support bounded decisions but do not necessarily generate prose or arbitrary JSON arguments. Model confidence is not operational permission or answer correctness.

## Decision

Use independently configured typed HTTP decisions for classification, routing, advisory tool relevance and compaction policy. Generation models write answers, summaries and tool arguments. Java owns candidate feasibility, input validation, budgets and authority. A genuine decision model is required for live decision operation; fakes/rules are explicit offline/fallback implementations.

## Alternatives and consequences

A generative controller can choose tools directly but adds cost and makes authority less inspectable. A purely static policy is a useful baseline but does not satisfy the project's primary System One experiment. The separate port limits lock-in; compatibility still needs pinned contract fixtures. Multiple decision calls can add latency, so batch independent questions and measure overhead.

## Validation and revisit

Show independent local/hosted endpoint configuration, typed failures and non-overridable tool authority. Revisit additional boundaries if decisions add more overhead than benefit. Never keep a decision call solely because the architecture diagram includes it.
