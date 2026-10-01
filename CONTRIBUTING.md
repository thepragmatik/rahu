# Contributing to Rahu

Rahu is a small research-led Java project. Read [AGENTS.md](AGENTS.md), the relevant specification and active implementation plan before changing behavior. The repository is currently specification only; add actual build/test instructions with S01.

## Development cycle

1. State the hypothesis, smallest useful change and falsifying checks.
2. Add a failing behavioral test, implement it, and refactor.
3. Run appropriate offline checks and inspect actual API/CLI output.
4. Review the change critically and correct findings.
5. Update specifications, ADRs and progress evidence; submit a small reviewable change.

Use Java 27 initially and consistent preview flags where applicable. Verify/pin actual versions rather than relying on sample commands. Keep paid service runs explicit and bounded. Never add keys, private traces, raw reasoning or user datasets to commits. Sanitised fixtures need provenance and must not imply a live result that did not occur.

Use the PR template. No license choice is presumed; discuss it with the owner before distribution. Security-sensitive defects should be reported privately to the repository owner through an existing agreed channel; do not include secrets or harmful payloads in public issues.
