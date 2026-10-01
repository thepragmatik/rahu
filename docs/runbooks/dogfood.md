# Dogfood setup and verification runbook

This runbook defines what the implementation session must make executable. Commands are target CLI contracts until the build is present. Convert them to verified instructions and record outputs when implementing S11/S12.

## Offline path

1. Verify the pinned JDK with `java --version`; follow the actual setup instructions for the selected distribution. No silent older-Java fallback.
2. Run `./mvnw verify` from a clean checkout. It must use no external model keys/network tests.
3. Run `./bin/rahu demo`, `./bin/rahu config validate --config examples/offline.json`, and `./bin/rahu route inspect --config examples/offline.json --prompt "Explain the planned modules"`.
4. Run `./bin/rahu chat --config examples/offline.json --input-classification approved-nonsensitive` with reviewed synthetic two-turn text; test `/status`, `/reset`, `/exit`.
5. Inspect the synthetic trace; use explicit payload capture for the replay fixture and verify offline replay.

Dependency/toolchain downloads during setup are separate from the offline model-test guarantee. A clean environment may need network to obtain Maven/JDK artifacts; the installed test suite must not contact models or live catalogs.

## Live prerequisites

Provide one running genuine System One service (local Laya/Kev or a verified hosted endpoint), its actual compatible profile/model and any key, an OpenRouter key, exact allowed generation IDs and supported efforts, and an explicit total smoke allowance and approved non-sensitive prompt/source views under the privacy contract. Do not paste secrets into issues/docs/commands that print them. Populate environment references through the user's approved secret mechanism.

The Java build does not require installing Python/model weights. Local decision serving is separately operated infrastructure; the implementation session documents the tested revision/start command if supplied, but does not train/provision it automatically. A model health response does not prove decision quality. Warm an authorised service before measuring warm latency; record cold-start latency separately. Configure a longer decision timeout explicitly only when the operator intends it.

Use [the live example](../../examples/live-local-systemone.json) and adjust its exact IDs/efforts to catalog evidence. Validate endpoint/auth/capabilities without generation first: `./bin/rahu config validate --config config.local.json --live-check`. `--live-check` fetches catalog and optionally service model metadata only; it must not bill a decision/generation request. Unsupported metadata endpoints report not verified without guessing. The subsequent smoke verifies actual wire exchange.

## Bounded live path

Use selected non-sensitive task inputs, the live config reference and an operator-supplied aggregate decimal USD ceiling. `./bin/rahu eval --suite docs/evals/suites/dogfood-alpha-v1.json --config config.local.json --live --max-cost-usd AMOUNT --report PATH` runs sequentially under that aggregate allowance. The suite contains follow-up and a bounded synthetic compaction fixture; the smaller seed smoke suite remains useful for plumbing. Neither is a statistical quality benchmark. AMOUNT is chosen by the user; no implicit paid default.

Reduce tasks/output limits if conservative admission cannot fit. Do not enlarge the allowance, change pool or disable required effort enforcement to make it run. The compaction task uses a stricter conversation prompt allowance of 4000 tokens and a bounded synthetic source, without changing the summary model's real input capacity. All phases use the same experiment ledger; invoking a new child session cannot reset the ceiling.

For interactive follow-up use `./bin/rahu chat --config config.local.json` with explicitly configured session allowance. Record it as a separate authorised experiment if outside the aggregate smoke plan; never label several independent session caps a single fixed experiment budget.

## Recovery and troubleshooting

| Symptom | Next action |
|---|---|
| Preview class version/flag error | Use the pinned JDK and launcher; verify test/compile/package flags together |
| No feasible candidates | Inspect exclusions; fix explicit IDs, effort evidence, context or allowance; do not widen automatically |
| Decision service timeout/refused input | Verify endpoint/profile/state bound and warm/cold status; fallback remains visibly degraded |
| All routes use fallback | Live System One gate remains unverified; inspect protocol/errors/projection rather than declaring success |
| Empty/length generation response | Record incomplete result; review combined output allowance/effort within budget |
| Tool read denied | Check root/exclusions/input and disclosure policy; no shell workaround |
| Privacy blocked | Reduce to approved non-sensitive/synthetic input, review stale source approval locally; no provider sanitiser or bypass |
| Transcript exceeds context | Use supported compaction source candidate or narrow input; pinned content cannot be dropped |
| Trace/replay incomplete | Report incomplete and inspect gaps; no live rerun from replay |
| API keys/service/budget unavailable | Finish offline deliverables; mark live gate blocked with exact prerequisites |

The release report follows [release-gates.md](../release-gates.md). Mark every check with mode and observed evidence. Success means a usable bounded read-only product, not proof of cost savings.

## Safe input and source preparation

Before any live model smoke, verify A33/A34 and mandatory local privacy/admission. The supplied live config intentionally defaults to unknown input and approves no source. Assess each prompt as non-sensitive, then use `--input-classification approved-nonsensitive` for run/chat or the same config field for the selected evaluation prompts. This records assessment only; scanning may still block it. Do not use the option for secret/customer/personal data.

For repository Q&A select non-sensitive files and create an ignored `privacy.local.json` using [the exact manifest contract](../specs/configuration.md#privacy-source-policy); add its path as `privacy.sourcePolicyFile` in `config.local.json`. Compute exact hashes locally without printing content. Review path metadata as well as bytes; source changes invalidate approval. The suite's repository-followup task reads `ARCHITECTURE.md`, so it requires that selected file's approval and safe metadata; no repository-wide automatic approval.

If safe source cannot be established, run authored synthetic live inputs only and report repository dogfood blocked. Such a run does not satisfy the full G09 repository-Q&A gate. Do not submit the original to a model for sanitisation, disable privacy or broaden approvals to clear the gate. Built-in synthetic fixture provenance does not transfer to arbitrary files/history.
