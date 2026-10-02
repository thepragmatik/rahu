# S12a live dogfooding wiring review

## Scope and hypothesis

Date: 2026-10-02. Change: S12a (LiveWiring, ChatCommand live path, OpenRouterProvider
billed-cost capture, config.local.json decision plane moved to the hosted Jev endpoint).
Affected requirements: R04, R05, R10, R24 (A10, A21, A22, A33, A34). Hypothesis: the
harness can complete a real turn — operator prompt to model answer — using only an
OpenRouter credential, with no local decision service, and account for the cost exactly.

## Evidence

- Unit: 134 tests green (`./mvnw verify` BUILD SUCCESS). New: LiveWiringTest 4 (adapter
  selection, unknown-adapter rejection, DotEnv credential resolution), OpenRouterCostTest
  2 (reported dollar cost -> exact micros + `usage.include` requested; absent cost stays
  unknown, never zero).
- Live decision-plane probe (cost $0.000021): `typesafe/jev-1.13` via
  `https://openrouter.ai/api/alpha/decisions`, object-valued state accepted, choice answer
  returns `{type, choice, probabilities{<criteria label>: p}, confidence}` with labels
  matching the submitted criteria and probabilities summing to 1.0 — exactly the shape
  `SystemOneHttpAdapter.parseChoice` already validates. No adapter change was needed; only
  the endpoint and credential moved.
- Liveness proof for the "is this cached?" question: two byte-identical requests returned
  different bodies (score 0.74 vs 0.67) with distinct response IDs — live inference, not a
  cache.
- Live end-to-end turn (packaged `./bin/rahu chat --config config.local.json
  --input-classification approved-nonsensitive`): decision (shadow, typesafe/jev-1.13):
  granite@default (confidence 0.88); model=mistralai/mistral-nemo; tokens in=78 out=7;
  cost=$0.000004; 533 ms; ledger settled 0.000004 USD; exit 0. Answer matched the requested
  literal.
- Credential handling: the value is read from the environment or the gitignored `.env`
  through `DotEnv`; the adapter receives a `Supplier<Optional<String>>` and the CLI prints
  only a presence check. A secret-pattern scan of the staged diff was run before commit;
  the only match was a synthetic test fixture, which was replaced with a non-credential
  shape so the scanner stays meaningful.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | Live chat runs routing in **shadow** mode only: the Jev decision is recorded and printed, but the baseline model always executes. Active routing (decision selects the model) is not yet honoured | Deliberate for the first spin — the decision plane is exercised and evidenced without letting an unvalidated router choose a paid model. Promote to active once decisions look sound on real prompts | Owner: S12b |
| Medium | The dispatch re-check scans the prompt text, not the adapter's exact serialised body (the adapters build their own JSON). `PrivacyGate.admitDispatch` is therefore applied to a faithful superset, not the literal bytes | Adapters should expose the serialised body for the A33 byte-exact recheck, or take the gate as a collaborator | Owner: S12b |
| Low | Live turns write no trace file; replay covers offline runs only | Wire TraceWriter into the live loop for G09 replay evidence | Owner: S12b |
| Low | Live chat does not yet run compaction (history is small in these turns) | Reuse the S09 planner in the live loop before long sessions | Owner: S12b |
| Low | Decision cost (~$0.00002/call) is not metered into the session ledger; only generation cost is | Fold decision usage into the ledger when the adapter surfaces it | Owner: S12b |

## Decision

Proceed. The dogfooding path is unblocked: with a credential in the environment or `.env`,
`chat` completes a real turn against the cheap pool with the decision plane live and the
cost accounted. Remaining S12 work is active routing, byte-exact dispatch checks, live
traces and compaction wiring.
