# OpenRouter adapter specification

## Catalog

Fetch `GET https://openrouter.ai/api/v1/models`, preserve a bounded raw snapshot and build provider-neutral profiles. Record fetched-at time, content hash, adapter version and configured overrides. Parse decimal prices as exact USD per token using the documented field units; do not confuse displayed per-million prices with wire units. Capture modality, context and output limits, supported parameters, reasoning metadata when available, and provider constraints.

The catalog may describe model-level rather than every endpoint's behavior. Treat absence as unknown and reconcile endpoint restrictions when relevant. Unknown required capability excludes a candidate; unrelated unknown capabilities do not block an answer-only task. Cache TTL is 24 hours. Offline mode uses a frozen fixture. Live mode fails on stale required evidence by default; an operator can explicitly permit stale use with a trace warning and maximum stale age.

A reviewed override needs exact model/provider scope, evidence URL or fixture, date, expiry, author/reason, and supported values. An override cannot enlarge the configured pool. Prefer a refreshed catalog to accumulating permanent hand-maintained assertions.

## Generation mapping

Use non-streaming `POST /api/v1/chat/completions` for the first release. Supply one exact selected model, ordered messages, validated tool descriptors, combined output limit, selected effort when explicit, and provider constraints. Set `provider.require_parameters=true` for controlled requests. Pin providers where reliable effort/continuation behavior requires it; allowed fallback providers must satisfy the same constraints. Do not use an OpenRouter dynamic model router or a multi-model fallback list to bypass Rahu's decision trace.

ProviderDefault omits effort; None requests supported disabled reasoning rather than omitting. Other efforts send the exact supported value. Capture the request controls but mark effective effort **unobserved** unless the provider supplies evidence. Effort acceptance does not imply a comparable amount of reasoning across models.

## Conversation and continuation

Preserve role ordering, assistant tool-call IDs and one result per call. Parse multiple tool calls deterministically. Keep opaque provider reasoning/signature/encrypted continuation data in a bounded adapter-owned envelope. Echo required continuation data exactly to the same compatible provider; do not reorder or summarise it. Core stores its reference/lifecycle without interpreting its contents.

Do not log raw reasoning. Do not pass one model's opaque envelope into another. Route switching is allowed only after the adapter confirms a compatible boundary; otherwise pin the model or terminate with an actionable compatibility failure. Test a tool request followed by a tool result with continuation data, not merely a single-turn answer.

## Usage and failure contract

Capture request/response IDs, requested and returned model, provider when supplied, usage totals, reasoning token count when available, finish reason, HTTP duration and reported cost. Missing fields are unknown. Reasoning tokens are normally part of completion usage; never add them a second time. Capture cached-token categories without assuming all tokens use the same rate. Reconcile reported cost separately from a catalog estimate. Reported cost is converted to exact integer micros from the provider's literal decimal digits, never through binary floating point. A reported cost that is positive but below half a micro is **unknown, not zero**: the provider billed something we cannot hold at micro resolution, and settling a confident zero would assert otherwise. An exact reported zero is a real observation of free and settles as zero. A negative or unrepresentable cost is unknown.

Defaults: connection 5 seconds, total generation request 120 seconds within the run deadline, maximum response 4 MiB. `finish_reason=length` is incomplete, including an empty answer where reasoning exhausted the allowance. Never display this as success. Malformed tool JSON, refusal, missing choice, provider error and inconsistent usage become typed outcomes.

401/403 and invalid requests fail without retry. A definitive rejected request such as 429 may be retried once when configured and admitted. Network failure after send, timeout, and unspecified 5xx may represent a billed outcome: default no automatic retry. An operator-enabled retry must count an uncertainty reserve and disclose duplicate billing risk. Never re-execute tools because generation was retried.

## Contract evidence

Build local-server fixtures for catalog variants, effort mapping, unsupported effort, ignored-parameter prevention, multiple calls, empty length response, missing costs, opaque continuation and typed errors. Pin upstream docs/fixture revision and verify against live API only in opt-in integration runs. New vendor fields require a contract review; normal tests never depend on today's live catalog.

Before generation, summary, retry or fallback transport, enforce [privacy](privacy.md) over the exact serialised body and metadata, independently of core safe-view planning. Preserve only privacy-admitted compatible opaque continuation; unverified opaque content blocks. Credential injection is restricted to this service's authentication field and cannot enter prompts or diagnostics.
