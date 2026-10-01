# S11 packaging review

## Scope and hypothesis

Date: 2026-10-01. Change: S11 (SchemaGenerator + docs/generated/config.schema.json, DotEnv
.env key source wired into Main + ConfigLoader, config.local.json cheap-model pool,
.env.example, README quickstart, clean-checkout G01 ritual). Affected requirements: R06,
R22, R24 (A15, A19, A21, A26-partial, A28, A31-partial). Hypothesis: the build is now
operationally usable by a new developer from the README alone.

## Evidence

- Schema: docs/generated/config.schema.json generated from the v1 field table
  (SchemaGeneratorTest 2/2); strict values (privacy const strict, orchestration const
  single, effort vocabulary 8 values) encoded.
- Keys: DotEnv loads gitignored .env at CLI startup (env-first lookup, quoted values,
  export prefix, comments); ${ENV} interpolation now resolves env -> .env with an
  actionable error naming both options; DotEnvTest 3/3. .env.example committed;
  .env itself never enters git (verified: git status clean with .env present, chmod 600).
- Local spin config: config.local.json (untracked, per repo gitignore design) with a
  cheap model pool verified against the live OpenRouter catalog on 2026-10-01:
  mistralai/mistral-nemo ($0.019/$0.030 per 1M, tools), qwen/qwen3-30b-a3b-instruct-2507
  ($0.048/$0.193, tools, 262k ctx), openai/gpt-oss-20b ($0.018/$0.090, tools+reasoning),
  ibm-granite/granite-4.0-h-micro ($0.017/$0.112, no tools — answer-only fallback).
  `./bin/rahu config validate --config config.local.json` = "config valid: mode=live,
  routing=shadow", exit 0.
- Clean checkout (G01 ritual): `git clone . /tmp/rahu-clean && ./mvnw verify` = BUILD
  SUCCESS (128 tests), `./bin/rahu demo` exit 0, `config validate` exit 0,
  `eval --suite smoke-v1.json` 6/6 exit 0 — from a fresh clone with only JAVA_HOME set.
- README: offline quickstart (no keys) + live quickstart (env + cheap pool + bounded
  chat), privacy pointer, honest status line (S01–S10 done, no benchmark claims).

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | The full single-composition E2E (prompt assembly -> fake ModelProvider -> ModelOutcome -> trace) is still exercised piecewise (each link tested; EvalCommand composes sessions + decisions but answers deterministically without routing prompts through PromptAssembler) | Top S12 item: wire the composition and assert one trace with RunStarted..RunTerminated from a single assembled prompt | Owner: S12 |
| Low | build manifest (docs/generated/build-manifest.json) not yet written by the build | Deferred to S12 release report where versions are recorded authoritatively | Owner: S12 |
| Low | Live `--live-check` catalog fetch not implemented (catalog evidence comes from the config pool ids) | G09 prerequisite list already names this; harmless offline | Owner: G09 |

## Decision

Proceed. Offline dogfood spin is unblocked: cheap pool configured, key mechanism live,
quickstart verified from a clean clone. Next: S12 release-wide critique + dogfood release
report (offline-complete claim), with the E2E composition wired first.
