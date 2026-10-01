# Build artifact and protocol contracts

These contracts reduce handoff ambiguity without pretending source/build artifacts already exist. Implementation creates schemas/golden fixtures and verifies examples against the executable code. A generated schema is not a substitute for cross-field validation.

## System One envelope

For `jev-compatible-v1`, the checked upstream conformance source is [Kev tests/test_api.py](https://github.com/jaredpalmer/kev/blob/main/tests/test_api.py), content blob SHA `86841aad9e740b73697196de49d89b9426fafc15`. Keep that blob/provenance in fixture metadata and recheck the deployed service. This source establishes the following shape; it does not establish all servers/checkpoints behave identically.

| Element | Required profile behavior |
|---|---|
| Request | `state`, optional configured `model`, `questions` map keyed by caller IDs |
| Question | type plus instructions; Choice criteria maps label→description; Noul optional true/false criteria; Score ordered description list |
| Response | `answers` map; `model` reported label when supplied; usage input_tokens/output_tokens if supplied |
| Choice answer | type choice, choice label, probabilities map, confidence if supplied |
| Noul answer | type noul, noul finite probability in [0,1] |
| Score answer | type score, numeric zero-based expected level, string-index legend/probabilities, optional confidence |
| Optional metadata | request-ID header, server time, truncation/state-token fields; retain availability/provenance |

Core must reject `truncated=true` for decision inputs under the default profile, because the decision was not made on the admitted projection. Verify advertised `truncate_states` behavior where available; unknown remains unverified. Choice/probability/score consistency follows strict contract validation; incompatible deployments need an explicit reviewed profile rather than silent field guessing.

[Synthetic request/response fixtures](../../examples/contracts/systemone/README.md) are authored to these shapes and contain no measured outputs. They seed offline adapter tests. They do not replace a real roundtrip. Hosted auth/base URLs and fees remain operator/deployment configuration.

## Evaluation suite v1

Top-level fields: `schemaVersion` integer 1, unique `id`, `purpose`, `tasks` array. Task IDs are unique. Each task has `id`, `kind`, `tools` as needed categories, and either `prompt` or nonempty `turns` array of prompt strings. Rubric is a nonempty string array or exact expectedLabel. Allowed kinds match classification labels; a task kind is ground-truth/evaluation metadata, not a permission grant. Empty, malformed, duplicate or unsupported fields fail with a path.

Optional `fixture` is one of the built-in synthetic fixture contracts, not a filename, class, shell command or dynamic loader. Initial fixture `long-completed-conversation-v1` creates ordered completed assistant/user units with source IDs and known facts until the configured estimator exceeds the conversation soft prompt allowance by at least 512 tokens, with a hard 32 KiB synthetic-content ceiling. It contains no unresolved tools or opaque provider data. If it cannot meet pressure within limits, report fixture setup failure, never silently pad without bound.

A fixture task can specify `contextMaxPromptTokens` as a stricter test conversation allowance; default live compaction smoke uses 4000. The override cannot exceed a stricter operator setting or real model capacity, alter the pool/provider/permissions, or lift any budget. Summary source fits the actual model, as [context.md](context.md) specifies. Required retained facts are in `requiredFacts`; compare their preservation without promoting generated summary text to trusted state.

`turns` run sequentially in one session; normal prompt tasks create separate sessions. All tasks share one experiment ledger. The smoke suite includes truthful metric limitations and does not assert a particular real route. Add new fixture kinds via code/tests/specs, not arbitrary suite execution.

## Evaluation report v1

Fields: schemaVersion, experimentId, suite/hash, harness/toolchain/config/catalog/profile versions, requested mode, aggregate allowance/cost categories, elapsed duration, taskResults and warnings. Per task: ID, mode, run IDs/session ID, completion/limit/failure status, rubric result or unassessed, route suggestion/execution records, decision-operation validation status, cost/usage/latency availability and evidence references. No private prompt/tool content by default.

Include success numerator/denominator, failures and setup-blocked counts. Distinguish successful execution from assessed quality. Summarisation/decision calls are child costs included once, not separate successful user tasks. A report with no successes has undefined cost per success. Incomplete cost has availability/uncertainty markers, never a plausible fabricated total.

Admission uses one ledger across follow-up and compaction phases; remaining experiment allowance constrains every run/session. The runner does not invoke fresh standalone commands that reset the allowance. `--max-cost-usd` is an exact nonnegative decimal, required for live eval; do not continue paid work after uncertain liability exhausts remaining admission. Live API fees can still exceed estimates: retain the admission-control limitation.

## Config and event schemas

Generate `docs/generated/config.schema.json` from v1 field definitions with unknown/duplicate-key rejection and cross-field validation in code. Add complete golden v1 events and JSON result/report fixtures under test resources. File schemas distinguish optional unavailable fields from numeric zero; schema evolution follows [observability.md](observability.md). CI validates config/examples/suites and Markdown destinations alongside code.

Record build versions/revisions in `docs/generated/build-manifest.json` when building. Do not write 'latest' as a reproducible version. Synthetic event/body fixtures carry `synthetic=true` in provenance sidecars, not unknown vendor body fields. Report actual tested compatibility, not a compatibility claim copied from a README.
