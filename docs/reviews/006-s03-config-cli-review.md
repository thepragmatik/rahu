# S03 configuration and CLI review

## Scope and hypothesis

Date: 2026-10-01. Change: S03 JSON config v1 (strict loader/validator) + CLI commands
(config validate/show, route inspect; demo/exit-code tests). Affected requirements: R06, R16,
R20-partial (A01, A15, A21, A25-partial). Hypothesis: strict JSON parsing with exact-decimal
money and field-path errors can be implemented without a schema dependency, keeping the
generated schema (S11) additive. Simplest alternative: databind to loose maps — rejected,
it silently keeps last-duplicate keys and defers validation to runtime.

## Evidence

- TDD RED: 52 unresolved symbols before implementation; GREEN after.
- `./mvnw verify` = BUILD SUCCESS; core 17 + cli 10 tests.
- Packaged verification (rahu-cli.jar via bin/rahu):
  - `config validate --config examples/offline.json` -> "config valid: mode=offline,
    routing=shadow", exit 0.
  - `route inspect --config examples/offline.json --prompt ...` -> 4 candidates
    (fast@low, fast@medium, quality@medium, quality@high) with stable alias@policy IDs,
    baseline/fallback/mode shown, exit 0; no decision call.
  - unknown top-level key -> "unknown config key ... remove it or fix the spelling", exit 2.
- ConfigLoaderTest covers: duplicate-key rejection (Jackson STRICT_DUPLICATE_DETECTION),
  exact-decimal money vs exponent/negative rejection, orchestration single-only,
  privacy strict/block-only, routing reference resolution with field path,
  ${ENV} interpolation restricted to documented fields with missing-env error.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Low (fixed in-slice) | Jackson duplicate-key message is capitalised ("Duplicate field"); initial lowercase match silently missed it — caught by test | Case-insensitive match on the error path | None |
| Low | route inspect uses synthetic offline evidence (fixed prices/context) until S04 provides the real catalog loader | Documented in-command; live configs are rejected with exit 2 until S04 | Synthetic evidence could mislead if mistaken for live; guarded by mode check |
| Medium | Money regex accepts up to 4 decimals but no thousands separators; acceptable per spec (plain decimal string) — but huge values are unbounded | validation.md wants defensive ceilings; S06 ledger adds admission ceilings; config-level ceiling deferred | Operator could set an absurd allowance; owner: S06 |

## Decision

Proceed. Exit-code contract (0/2) verified against the packaged jar; A15 field-path errors
verified. Next: S04 OpenRouter adapter contracts against a local recording server.
