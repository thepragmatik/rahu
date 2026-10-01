# S02 routing review

## Scope and hypothesis

Date: 2026-10-01. Change: S02 candidate generation + shadow/active route resolution
(rahu-core/routing, rahu-core/decision). Affected requirements: R03, R05, R11 (A03–A07).
Smallest useful result: code constructs the legal candidate set; System One (typed result)
selects within it; degraded decisions use the configured fallback. Simplest alternative
considered: single "pick first feasible" policy — rejected, it deletes the decision plane
hypothesis the repo exists to test. Falsifier: any executed candidate outside the feasible
set; any malformed distribution accepted as a choice.

## Evidence

- TDD: routing tests written and run RED (missing types) before implementation; GREEN after.
- `./mvnw -pl rahu-core test` = 17/17: A04 effort intersection + mandatory-reasoning
  exclusion; A05 tool-support exclusion; unknown-evidence exclusion (routing.md step 5);
  oversized pool rejected with advice (never truncated); A03 out-of-set suggestion rejected
  with fallback; A06 malformed distribution (sum 1.2) typed-rejected, no repair; A07 shadow
  records suggestion/executes baseline, active executes admissible suggestion; low
  concentration (0.5 < 0.65 gate) uses fallback marked degraded; timeout degrades with
  fallback-cause; no feasible fallback terminates NO_FEASIBLE_ROUTE before any call;
  200-trial randomized invariant sweep (executed ∈ feasible set across modes/failures).
- `./mvnw verify` = BUILD SUCCESS.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium (fixed in-slice) | Initial resolver executed baseline on active-mode decision failure; routing.md decision table requires the fallback in active mode; 3 tests caught it | Resolver now: shadow→baseline, active degraded→fallback, neither feasible→NO_FEASIBLE_ROUTE | Fallback-vs-baseline semantics for future escalation paths; owner: S06 runtime |
| Low | Distribution validation accepts cover+sum+maximal checks only; probability semantics (raw confidence vs chosen probability) carried as separate fields but unexercised against a real adapter | S05 pins the real envelope and adds conformance tests | Upstream may report confidence in unknown semantics; owner: S05 |
| Low | Context-allowance exclusion uses profile.contextTokens directly; framing/tool-schema reserve is part of OperationRequirements.contextAllowanceTokens but token estimation itself is S06/S09 | Estimator lands with runtime slice | Conservative estimates may exclude feasible candidates; fail-safe direction |

## Decision

Proceed. Routing invariants hold under randomized trials; no high-severity open findings.
Next: S03 configuration v1 + CLI (route inspect exposes candidates/exclusions).
