# Privacy and outbound data specification

This is the mandatory alpha data boundary. It strengthens earlier guidance about logs and endpoint trust: selecting a workspace/service or supplying an API key does not permit sending PII or sensitive content. The build agent must follow [prompt.md](../../prompt.md) when selecting its own tool outputs and external requests.

## Protected data and classifications

Protected includes personal identifiers/contact information, account IDs tied to people, credentials/tokens/private keys, health/financial/legal records, private customer/business data, confidential code/configuration, and identifying paths/metadata. Public availability does not remove PII status. Private source and unknown provenance are restricted by default; only explicitly approved non-sensitive views may leave the process.

Use classification/provenance states `synthetic`, `approved-nonsensitive`, `restricted` and `unknown`. Only locally established synthetic or approved-nonsensitive views are eligible, subject to scanning. Model text and tool content cannot label themselves safe. Explicit operator classification/allowlists cannot override a known protected-data finding. A new/modified source invalidates previous approval until rechecked. Source hashes/provenance are local evidence, not anonymisation.

MVP does not promise automatic recognition of all PII or confidential material. Local deterministic detectors cover known high-risk patterns, protected structured fields and operator-declared sensitive values; provenance restrictions and unknown-input denial cover uncertainty. Do not use provider inference for privacy decisions. False positives yield actionable local diagnostics and a safe revised input, never a bypass allowing known protected content.

## Local views and outbound gate

Keep original source separate from model-visible context. Minimise before projection/assembly, scan locally and construct a safe view. Safe substitutions may use stable surrogate labels with local-only mappings. Only a locally validated transformation with adequate provenance may promote a restricted original to an eligible safe view; a relabel without changed/rechecked bytes cannot do so. Pseudonymisation can remain identifying; do not deem a text safe merely because names were replaced. If uncertainty or essential sensitive semantics remain, block or reduce the task without a provider call.

Apply the gate before initial classification; check the planned exact serialised model body/URL/metadata before reservation, then recheck immediately before handing it to transport. Include system/operator instructions, user/history content, candidates/descriptions, tool schemas/arguments/results, summaries, evaluations, retries/fallbacks and adapter-added fields. No port/extension can bypass it. Initial privacy admission precedes reservation/dispatch; a final recheck blocks dispatch and releases only definitely unused reservations. Preserve earlier settled/uncertain liabilities. Code cannot fall back to a different provider with the blocked original.

Bind admission to immutable safe-view hash, endpoint/provider scope, config/privacy-policy revision and approved provenance. Any content/endpoint change requires revalidation. Hash, classify, scan and construct the safe view from the same bounded immutable source snapshot; never check one read and send bytes from a later unchecked read. Detect sensitive keys/values in nested JSON and serialised strings; bound decoding/resource work. Unknown opaque/encoded fields are not automatically safe. Do not claim a scanner defeats arbitrary encodings or adversarial obfuscation; block unresolved content instead.

Model-visible sanitisation must respect transcript semantics: sanitise tool arguments before issuing a model request; derive corresponding tool observations from the actually validated local call and safe content. If removing content breaks schema, essential meaning or call pairing, return a bounded denial or terminate. Never relabel original tool arguments/results as sanitised without changing their bytes.

Use `PRIVACY_BLOCKED` as a terminal reason when mandatory request context cannot be admitted. A protected tool result may instead produce a generic local-policy denial observation with no offending text, letting the model continue within ordinary limits. Denied instructions/current requests cannot be silently dropped to manufacture an unrelated answer. Classification/route failure fallback is not a privacy workaround.

## Generated and opaque content

Re-scan generated answers/summaries before history/replay/export or any subsequent request. They can repeat/infer protected information. Prior admission is not a permanent clearance for changed content. Restrict cross-provider continuation: preserve opaque blocks only under a reviewed adapter profile with origin tied to a privacy-admitted request, permitted same-origin return, and no protected original data introduced. Otherwise block, never modify signed/encrypted blocks or send them to a different model/provider.

Local loopback endpoints may proxy remote services or emit telemetry. Their URL does not establish privacy. Apply the same safe-view gate to System One and generation endpoints in alpha; vetted local processing of restricted content would require a separate future policy, not a silent exception.

## Authentication and other external surfaces

Authentication is the only credential transmission exception: the matching configured service key may appear in its required auth field over validated transport (HTTPS with validated TLS for non-loopback services; explicitly configured loopback HTTP decisions follow the local transport policy in [safety](safety.md)). Never send one provider's key to another, place keys in bodies/query strings/prompts, forward across redirects or expose them through logs/exceptions. Load keys locally without echoing their values.

Default traces contain redacted local metadata, not raw prompts/results/reasoning. Mark internal paths/content hashes/mappings as potentially identifying; safe reports use project-relative or surrogate paths and omit sensitive equality metadata as needed. Explicit local payload capture does not authorise upload or commit. No remote telemetry exporter in alpha. Sanitise provider error bodies before diagnostics; do not leak the raw request to debug exceptions.

No real protected data in fixtures, issues, commits, searches, CI logs, screenshots or artifacts. Use synthetic canaries and approved non-sensitive source for live smoke. Provider no-training/ZDR/retention claims are additional constraints, not permission to transmit protected data.

## Configuration and operator workflow

`privacy.mode=strict` and `privacy.onUnknown=block` are the only supported alpha policies. `privacy.inputClassification` accepts unknown (default) or explicit `approved-nonsensitive`; both still undergo scanning. Only authored fixture loaders establish synthetic provenance; user/model text cannot self-declare synthetic. `privacy.sourcePolicyFile` may name a local UTF-8 JSON manifest, never a URL/code loader. [Manifest v1](configuration.md#privacy-source-policy) entries bind a workspace-relative path, exact SHA-256 content hash and approved-nonsensitive classification; modifying the file invalidates approval. No wildcards, recursive includes or `allowSensitive` switch.

The manifest supplies provenance for safe selected views, not proof that scanning is perfect. Sources not in it remain restricted/unknown unless created internally as verified synthetic fixtures. Built-in synthetic demos need no private-source approval. A live test selects safe input, scans eligible source and body, then requests admission; unknown/private content fails before dispatch. Show safe reason codes/categories and next action without a snippet of the blocked value.

## Acceptance and release

A33 tests outbound coverage/zero sends for blocked nested request, instruction, history, tool, candidate and adapter fields across both services and fallbacks. A34 tests safe-view provenance, source mutation, summary laundering, generated content, opaque continuation, diagnostics/export and the narrow authentication exception. Assert dispatch counts and final wire payloads using local recording transports; merely testing a regex function is insufficient.

Tests are synthetic and keep canaries out of failure logs. Privacy findings block release through G03/G06/G09/G10. Runtime proof and detection limits must be stated; this specification/prompt is not itself proof that no sensitive data can ever leave a host platform.
