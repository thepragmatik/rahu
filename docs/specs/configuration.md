# Configuration specification

## Format and precedence

Use UTF-8 JSON v1 for the first implementation. This avoids implicit YAML scalar conversions and a second parser dependency; YAML can be added later as a translation into the same validated domain types. Ship a generated JSON Schema when the configuration code exists. The current examples specify the target contract, not an implemented parser.

Precedence: built-in operational defaults < explicit configuration file < documented environment settings < explicit CLI flags. Resolve `${ENV_NAME}` only in documented string fields (`model.id`, credentials references and endpoint); missing required values are errors. No shell expansion, recursive interpolation or arbitrary templating. Environment API keys override named credential references without writing resolved secrets back to disk. Unknown keys, duplicate JSON keys and incompatible schema versions fail with a field path and correction.

`mode` is offline or live. Offline requires fake adapters/frozen fixtures and disallows network. Live requires a real System One adapter, generation credentials, explicit pool and baseline/fallback references. There is no implicit real model chosen because it appears cheap or popular. Operational defaults are supplied; user-authorised live model identity is a necessary input.

## Fields

| Field | Contract |
|---|---|
| `schemaVersion` | Integer 1 |
| `mode` | `offline` or `live` |
| `decision` | adapter, baseUrl, compatibilityProfile, model, optional apiKeyEnv, timeoutMillis |
| `generation` | adapter, baseUrl, apiKeyEnv, requireParameters, allowedProviders |
| `routing` | mode shadow/active, pool, baseline, fallback, confidenceField, confidenceFloor, maximumCandidates |
| `pools` | Named models with alias, exact or env-resolved ID, allowed reasoning policies, optional evidence-backed descriptions |
| `summarisation` | pool, baseline, fallback; defaults to routing pool/references when feasible |
| `agent` | maxGenerationAttempts, deadlineSeconds, maxCostUsd, maxCompletionTokens, maxCompactions |
| `catalog` | cacheTtlSeconds, allowStale, maximumStaleSeconds, optional offlineFixture |
| `tools` | root, enabled names, exclusions, maxCallsPerStep, resultBytes |
| `trace` | directory, capture metadata/payloads, onFailure stop |

Candidate references are `alias@policy`. A reference must exist in its named pool. The same alias cannot identify different models within a pool. Efforts cannot be an empty list. Local-service model is independent from generation model aliases. Provider constraints apply to every attempt. Allowlist fields never accept wildcard expansion by model text.

Limits are finite: timeouts/deadlines and byte/token/attempt counts must be positive integers, candidate maximum must fit the deployed decision profile, and confidenceFloor is in [0,1]. `maxCostUsd` is a nonnegative decimal string with no exponent, parsed exactly; zero permits free/offline work only. Invalid negative/NaN/infinite values and excessively large configured limits fail validation before I/O. Each adapter also enforces fixed defensive ceilings independent of model content.

## Defaults

| Setting | Default |
|---|---|
| Routing | shadow; chosen_probability gate 0.65; max 32 candidates |
| Local decision URL | `http://127.0.0.1:8000` |
| Decision timeout | 5000 ms total; 3000 ms connect |
| Generation URL | `https://openrouter.ai/api/v1` |
| Provider parameter enforcement | true |
| Run | 8 generation attempts, 180 seconds, USD 1.00 admission, 4096 output tokens, 2 compactions |
| Catalog | 86400-second TTL; stale use false; maximum permitted stale age 172800 seconds when enabled |
| Tools | three read-only tools; selected workspace root; max 8 calls/step; 65536-byte result |
| Trace | `.rahu/runs`; metadata only; stop on persistence failure |
| Retry | zero automatic ambiguous retries; definitive-rejection retries opt-in |

## Validation and disclosure

`config validate` resolves references without generation and reports whether catalog/decision compatibility was verified or only structurally validated. Network catalog checks are explicit in live mode; paid model checks are separate opt-in. `config show` displays resolved non-secret configuration, source of each override, and redacts secrets. `route inspect` explains candidates and exclusions without calling a decision model unless requested.

Do not persist `${ENV}`-resolved credentials or include them in config hashes. Hash a canonical redacted non-secret representation. Reject credentials embedded in URLs; redact error bodies. A local model can receive sensitive request content, so endpoint selection is a trust decision even when its monetary provider cost is zero.
