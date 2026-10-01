# Traces and policy replay

Date: 2026-10-01. Status: Accepted.

## Context

Research requires observable routing decisions and failures. Event sourcing the entire runtime and promising deterministic LLM regeneration would create unnecessary complexity and false guarantees.

## Decision

Append schema-versioned JSONL observations from an ordinary in-memory state machine. Metadata capture is default; payload capture is explicit. Provide offline inspection and deterministic policy replay only with sufficient frozen inputs/recorded outcomes. Live rerun is a separate paid operation. Stop new work on trace persistence failure by default.

## Alternatives and consequences

Full event sourcing could enable recovery but requires a journal/recovery protocol especially for tools. Plain text logs are easy but unsuitable for quantitative evaluation. JSONL can be incomplete after crashes and may contain sensitive metadata; readers detect gaps and report limitations. No automatic resume or exactly-once effects in MVP.

## Validation and revisit

Golden schemas, corruption/persistence-failure tests and offline replay establish guarantees. Revisit persistent state/journaling before adding mutating tools or resumability. Never treat replay as crash recovery.
