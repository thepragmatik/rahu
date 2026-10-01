# Own the agent loop

Date: 2026-10-01. Status: Accepted.

## Context

The research question concerns routing boundaries, continuation, budgets and evaluation. Delegating the loop to a general agent framework would obscure those decisions and couple the kernel to its lifecycle.

## Decision

Own a small bounded loop in `rahu-core`. Use ordinary Java and narrow ports for decision, generation, tools and observations. Four Maven modules separate domain, two HTTP adapters and CLI. Libraries may handle JSON, CLI parsing and HTTP fixtures; no agent framework controls the loop.

## Alternatives and consequences

Spring AI/LangChain4j can reduce initial plumbing and remain useful references or future adapters. The owned loop creates responsibility for protocol correctness and cancellation. Avoid compensating with a large home-grown framework; start with explicit serial transitions.

## Validation and revisit

Fake integration and provider continuation tests must prove the loop. Revisit if compatibility maintenance outweighs the experimental value, or a framework exposes the exact policy hooks without compromising tests/authority. Evidence, not framework loyalty, decides.
