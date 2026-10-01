# Joint execution candidate routing

Date: 2026-10-01. Status: Accepted.

## Context

Independent model and effort selection can produce unsupported combinations. Model/provider reasoning semantics differ, and omitted configuration is not disabled reasoning.

## Decision

Java constructs legal model/effort/provider tuples from configured pools and capability evidence. System One chooses a stable candidate ID. Provider default, disabled reasoning and explicit effort are separate policies. Baseline/fallback obey the same feasibility checks. No automatic pool widening.

## Alternatives and consequences

Two-stage routing reduces choice count but requires conditional validation and may conceal joint tradeoffs. Joint choice increases options and description length; default candidate count is capped and oversized pools fail explicitly. This ensures feasibility, not quality. Provider metadata can remain incomplete; reviewed scoped overrides expire.

## Validation and revisit

Generated invariant tests establish legal-only execution. Option permutation, duplicate descriptions and model/effort counterfactual tests assess judgment. Revisit hierarchical selection if joint candidate count hurts quality/latency at realistic pool sizes, preserving final joint feasibility.
