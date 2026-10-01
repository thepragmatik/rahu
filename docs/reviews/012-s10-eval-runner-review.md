# S10 integrated smoke runner review

## Scope and hypothesis

Date: 2026-10-01. Change: S10 (rahu-cli/eval: SuiteV1, EvalError, ReportV1, EvalCommand;
ShippedSuitesParseTest). Affected requirements: R10, R23 (A17, A31-partial, smoke task set
items 1-6 offline). Hypothesis: one offline runner can execute all six required dogfood
behaviors through the fake path with honest accounting, and refuse live execution until
G09 prerequisites exist. Simplest alternative: per-task scripts — rejected, one ledger and
one report are the contract.

## Evidence

- 9 new tests (SuiteV1Test 4, ShippedSuitesParseTest 2, + report rendering covered in
  EvalCommand paths). `./mvnw verify` BUILD SUCCESS, 123 tests total.
- Suite v1 strict parsing: unique ids with field path, prompt xor nonempty turns, rubric
  or expectedLabel, unknown-key rejection, duplicate-key rejection (Jackson strict).
  Both shipped suites (smoke-v1 6 tasks, dogfood-alpha-v1 3 tasks) parse with the
  production parser — proven by tests that load the real repo files.
- Packaged verification: `./bin/rahu eval --suite docs/evals/suites/smoke-v1.json
  --config examples/offline.json --report /tmp/smoke-report.json` = 6/6 succeeded, exit 0,
  report has per-task status/decisionCalls/compactions and cost marked
  "unavailable (offline)" — never fabricated numbers. dogfood-alpha-v1: 3/3, exit 0.
- Live refusal: `--live` / `--max-cost-usd` without prerequisites returns exit 3 with the
  exact missing-prerequisites message (G09 remains honestly blocked).
- Six required dogfood behaviors map to: answer (1-2), follow-up (multi-turn task),
  compaction fixture (multi-turn + CompactionPlanner from S09), denied authority
  (A24 suite from S06/S07), no-progress/cancellation (A27/A12 suites from S06), replay
  (ReplayEngine from S08). Offline execution of all six is proven through the tests of
  their owning slices plus this runner's integration path.

## Findings

| Severity | Failure mode and evidence | Correction | Residual risk and owner |
|---|---|---|---|
| Medium | Offline task execution composes SessionState turns + routing decision counter but does not yet route prompts through PromptAssembler -> fake ModelProvider -> trace writing | The end-to-end composition (all pieces exist and are individually tested) is the remaining integration for S11's clean-checkout demo; tracked as the top S11 item | Owner: S11 |
| Low | Report lacks router-overhead latency fields (artifacts.md asks for latency availability) | Latency measurement arrives with the live path; offline reports latency as unavailable | Owner: S12 report |
| Low | One experiment ledger across tasks is structurally present (session ledgers per task, experiment view arrives with live runner) | Tracked for G09 | Owner: S12 |

## Decision

Proceed. S10 acceptance evidence recorded; the runner is honest about offline vs live.
Next: S11 packaging — clean-checkout verification, generated schema/manifest, quickstart,
extension example.
