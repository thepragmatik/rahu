# Configuration and initial routing policy

Date: 2026-10-01. Status: Accepted.

## Context

The owner requires sensible defaults and narrow configurable model pools. Silent real-model selection risks unexpected quality/cost. Modern provider aliases and capability data change frequently.

## Decision

Use strict JSON v1 with generated schema at implementation. Supply operational defaults and synthetic offline demo fixtures. Require explicit live model IDs, System One service and generation credentials. Live default is shadow: record System One suggestions while executing the configured baseline. Active mode is available explicitly; changing the default requires the evaluation promotion gate.

## Alternatives and consequences

YAML is more convenient for some hand editing but adds parser semantics/dependency; it can later translate into the same types. Bundled live pools simplify first run but become stale and imply endorsement; provide catalog-assisted validation instead. Shadow costs include routing overhead and cannot establish alternative-model savings without counterfactual execution.

## Validation and revisit

Test examples/precedence/errors/redaction and compare shadow/active traces. Revisit YAML if configuration editing is a demonstrated friction. Publish any recommended live pool with verified IDs/date/provider constraints and explicit user selection, not as an invisible default.
