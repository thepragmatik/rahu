# ADR 0008: Strict outbound privacy admission

Date: 2026-10-01. Status: accepted for design; runtime implementation pending.

## Context

The owner requires the initiating build prompt to prohibit exposing PII/sensitive data to LLM providers. Earlier secret exclusions, metadata-only logs and endpoint trust guidance were insufficient: first classification, summary source, tool observations and adapter fields could still disclose protected data. The same caution applies to selected build-agent tool output.

## Alternatives

Prompt-only prohibition does not control transport. Provider retention options do not prevent transmission. Sending raw content to a privacy classifier/summariser already discloses it. Secret regexes alone miss contextual/confidential data. Banning all live use would prevent dogfooding even on synthetic/approved non-sensitive material.

## Decision

Adopt [strict privacy](../specs/privacy.md): local provenance/classification and minimised safe views, local detection, unknown/restricted denial, initial admission before first classification/reservation, and exact serialised final dispatch checks across both services. Read permission and local endpoint selection do not grant disclosure. Explicit source approval is content-hash bound and cannot override a known finding. Restrict service credentials to their matching authentication transport field. No remote telemetry exporter or privacy bypass in alpha.

Keep the four modules; core owns policy and adapters must enforce final request checks. The build prompt governs selection of outputs into the builder's hosted context but cannot control that host's existing retention/platform behavior. Do not pretend that a detector proves universal PII recognition.

## Consequences and validation

Unknown normal text requires explicit non-sensitive assessment, and repository Q&A requires selected safe-source provenance. Some useful private-data tasks remain unsupported. False positives block safely; mappings and source policies stay local. Final dispatch failures release only unused reservations, retaining prior liabilities. Real adapter smoke waits for S06 privacy/admission instead of occurring in S05. Synthetic A33/A34 recording-transport tests and G03/G06/G09/G10 evidence are required; specification presence is not runtime proof.

## Revisit triggers

Revisit only with explicit owner scope and evidence for a different trusted-processing boundary, richer local detectors, structured safe-view transformations or new external surfaces. Provider marketing, a loopback URL, operator key possession or missing live prerequisites are insufficient reasons to weaken the policy.
