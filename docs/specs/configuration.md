# Configuration specification

## Format and precedence

Use UTF-8 JSON v1 for the first implementation. This avoids implicit YAML scalar conversions and a second parser dependency; YAML can be added later as a translation into the same validated domain types. Ship a generated JSON Schema when the configuration code exists. The current examples specify the target contract, not an implemented parser.

Precedence: built-in operational defaults < explicit configuration file < documented environment settings < explicit CLI flags. Resolve `${ENV_NAME}` only in documented string fields (pool model IDs, decision.model, credentials references and endpoints); missing required values are errors. No shell expansion, recursive interpolation or arbitrary templating. Environment API keys override named credential references without writing resolved secrets back to disk. Unknown keys, duplicate JSON keys and incompatible schema versions fail with a field path and correction.

`mode` is offline or live. Offline requires fake adapters/frozen fixtures and disallows network. Live requires a real System One adapter, generation credentials, explicit pool and baseline/fallback references. There is no implicit real model chosen because it appears cheap or popular. Operational defaults are supplied; user-authorised live model identity is a necessary input.

## Operational defaults

Every default below is applied when the field is absent, and each one is declared in
`rahu.cli.config.OperationalDefaults` — the single authoritative source. `SchemaGenerator`
emits them into the JSON Schema, and `OperationalDefaultsTest` fails if the schema, this
document and the shipped value ever disagree.

| Field | Default | Why |
|---|---|---|
| `context.maxPromptTokens` | 8192 | Prompt budget the router packs against before deciding whether to compact. Modest on purpose: a larger value defers the first compaction until the prompt no longer fits. |
| `routing.maximumCandidates` | 32 | Ceiling on the candidate set. Also the schema's `maximum`, so the bound and the default cannot disagree. |
| `routing.confidenceFloor` | 0.0 | See "Confidence floor" below. |
| `agent.maxCompletionTokens` | 2048 | Per-generation completion ceiling. |
| `agent.maxCostUsd` | 0 | No spend ceiling is enforced from configuration alone; provider-side limits still apply. |
| `tools.resultBytes` | 65536 | 64 KiB per tool result. Matches `WorkspaceTools.DEFAULT_RESULT_BYTES` (`64 * 1024`) — two constants, one meaning. |
| rerank `maxCandidates` | 20 | Candidates re-ranked by the tool-relevance reranker. |

## Confidence floor

`configuration.md` previously described the default routing gate as a 0.65
`chosen_probability` threshold. **The shipped default is 0.0**, and this section records why
the two were reconciled.

`routing.md` calls its own 0.65 "a provisional concentration gate, not an accuracy
guarantee" — it is a heuristic for discarding low-probability choices, not a correctness
claim. Shipping it as the *default* would mean every run without an explicit
`confidenceFloor` silently discards routes, which contradicts the shadow-mode default: the
kernel proposes, and the operator's configured floor is what gates. An operator who wants the
concentration behaviour opts in by setting `routing.confidenceFloor` explicitly.

The direction of the earlier mismatch mattered. The code applied the *more permissive* 0.0
while the spec advertised 0.65, so a reader of the doc would have got a stricter product than
the doc described, and a reader of the code a looser one than the doc promised. Either way
the disagreement was silent. `OperationalDefaultsTest.confidenceFloorAgreesWithTheSpec` now
fails on any future divergence, so this cannot drift again.

## Fields

| Field | Contract |
|---|---|
| `schemaVersion` | Integer 1 |
| `mode` | `offline` or `live` |
| `decision` | adapter, baseUrl, compatibilityProfile, model, optional apiKeyEnv, timeoutMillis, costMode, optional pricing |
| `generation` | adapter, baseUrl, apiKeyEnv, requireParameters, allowedProviders |
| `routing` | mode shadow/active, pool, baseline, fallback, confidenceField, confidenceFloor (default 0.0), maximumCandidates (default 32), rerank maxCandidates (default 20) |
| `pools` | Named models with alias, exact or env-resolved ID, allowed reasoning policies, optional evidence-backed descriptions |
| `summarisation` | pool, baseline, fallback; defaults to routing pool/references when feasible |
| `agent` | maxGenerationAttempts, deadlineSeconds, maxCostUsd (default 0, meaning no spend ceiling is enforced from configuration alone), maxCompletionTokens (default 2048), maxCompactions |
| `catalog` | cacheTtlSeconds, allowStale, maximumStaleSeconds, optional offlineFixture |
| `tools` | root, enabled names, exclusions, maxCallsPerStep, resultBytes (default 65536) |
| `trace` | directory, capture metadata/payloads, onFailure stop |
| `context` | instructionFiles (explicit ordered local paths), optional maxPromptTokens (default 8192), routerStateBytes |
| `session` | mode `in-process`, maxTurns, maxCostUsd; no automatic persistence |
| `orchestration` | mode `single` only in alpha |
| `privacy` | mode `strict`, onUnknown `block`, inputClassification `unknown`/`approved-nonsensitive`, optional local sourcePolicyFile |
| `search` | mode `off`/`shadow`/`enforce`, maxCandidates 1-1000 (default 20); absent means off. Shadow scores and reports the proposed order without applying it. Each search costs one decision call once enabled |
| `injection` | mode `off`/`shadow`/`enforce`, threshold 0-1; absent means off. Do not set `enforce` until the shadow threshold is calibrated against real observations |


Candidate references are `alias@policy`. A reference must exist in its named pool. The same alias cannot identify different models within a pool. Efforts cannot be an empty list. Local-service model is independent from generation model aliases. Provider constraints apply to every attempt. Allowlist fields never accept wildcard expansion by model text.

Limits are finite: timeouts/deadlines and byte/token/attempt counts must be positive integers, candidate maximum must fit the deployed decision profile, and confidenceFloor is in [0,1]. `maxCostUsd` is a nonnegative decimal string with no exponent, parsed exactly; zero permits free/offline work only. Invalid negative/NaN/infinite values and excessively large configured limits fail validation before I/O. Each adapter also enforces fixed defensive ceilings independent of model content.

## Defaults

| Setting | Default |
|---|---|
| Routing | shadow; confidenceFloor default 0.0 (see below); max 32 candidates |
| Local decision URL | `http://127.0.0.1:8000` |
| Decision timeout | 5000 ms total; 3000 ms connect |
| Generation URL | `https://openrouter.ai/api/v1` |
| Provider parameter enforcement | true |
| Run | 8 generation attempts, 180 seconds, USD 1.00 admission, 4096 output tokens, 2 compactions |
| Catalog | 86400-second TTL; stale use false; maximum permitted stale age 172800 seconds when enabled |
| Tools | three read-only tools; selected workspace root; max 8 calls/step; 65536-byte result |
| Trace | `.rahu/runs`; metadata only; stop on persistence failure |
| Retry | zero automatic ambiguous retries; definitive-rejection retries opt-in |
| Context | no instruction files; model-derived prompt allowance; 16384-byte router state content |
| Session | in-process; 20 turns; USD 3.00 aggregate admission; no persistence |
| Orchestration | single; exact no-progress streak 3; no delegation |
| Privacy | strict; unknown blocks; input classification unknown; no implicit source approval |
| Search rerank | off; cap 20 candidates once enabled |
| Injection overlay | off; threshold unset |

## Validation and disclosure

`config validate` resolves references without generation and reports whether catalog/decision compatibility was verified or only structurally validated. Network catalog checks are explicit in live mode; paid model checks are separate opt-in. `config show` displays resolved non-secret configuration, source of each override, and redacts secrets. `route inspect` explains candidates and exclusions without calling a decision model unless requested.

Do not persist `${ENV}`-resolved credentials or include them in config hashes. Hash a canonical redacted non-secret representation. Reject credentials embedded in URLs; redact error bodies. Apply [the privacy contract](privacy.md) to every decision/generation request, including local services. Endpoint selection and read permission never authorise disclosure of protected content. No sensitive-body or privacy-bypass configuration is supported.

Limit settings including `context.maxPromptTokens`, session amounts/counts and routerStateBytes follow exact numeric validation/defensive ceilings; routerStateBytes may reduce but cannot raise the 16384-byte default content ceiling in alpha. Instruction files are explicitly selected data-only inputs; no recursive loading or executable hooks. Unknown adapter names and orchestration modes fail; they never trigger discovery/download. `config validate --live-check` can fetch catalog/service model metadata but must not invoke billable decisions/generation.

For decisions, `costMode=local-unbilled` is valid only for fake or explicitly operated loopback services and means no upstream provider invoice, not zero local compute cost. This is the loopback example's explicit policy. Hosted/token-billed services require known pricing from verified service metadata or `costMode=configured-tariff` with exact-decimal pricing inputUsdPerToken, outputUsdPerToken, requestUsd, evidence and expiry. Fixed-fee tariffs may set token rates to zero. Unknown/expired required price evidence prevents paid decision admission, even before generation. Estimate the bounded typed response schema, not an unconstrained autoregressive output. Report actual/estimated/unknown settlement honestly.

## Privacy source policy

Only `strict`/`block` are supported for privacy mode/unknown handling; reject other values and unknown privacy keys. Explicit `approved-nonsensitive` input classification records operator assessment of submitted text, not permission to transmit a protected finding. The CLI can override this field for a selected safe input. Only the built-in fixture loader can establish synthetic provenance; arbitrary user config/text cannot self-declare synthetic.

An optional `privacy.sourcePolicyFile` resolves to a local UTF-8 JSON file, read with bounded size and the same duplicate/unknown-key rejection as config. Version 1 is `{"schemaVersion":1,"entries":[]}` with each entry containing exactly `path` (workspace-relative regular-file path), `sha256` (64 lowercase hexadecimal characters over exact file bytes) and `classification` (`approved-nonsensitive`). Reject duplicates, unsafe paths, symlinks and other classifications; no wildcards, URL loading or recursive includes. An empty manifest approves no source. Changing bytes invalidates approval. Approval covers the selected file view, subject to local scanning, and cannot weaken exclusions or authorise unrelated directory metadata.

Generate hashes only with authorised local tools that do not print file contents. Review selected sources as non-sensitive before creating the local manifest; do not classify a whole private repository by assumption. Keep the policy/mappings local and ignored, for example `privacy.local.json`. Safe errors identify categories and corrective actions without printing identifying paths, hashes or blocked values. Config display/validation output is also subject to safe diagnostics, not an unrestricted dump.
