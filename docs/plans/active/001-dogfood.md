# Dogfood implementation plan

This plan builds M1 and M2 through small executable slices in one initiated build session. All checkboxes remain open because no source implementation exists. Continue after each slice until S12/release gates are complete or only genuine external prerequisites remain. Run a critical review after each substantial slice and across the full release. Follow [the autonomous-build contract](../../autonomous-build.md) and [seven-area coverage map](../../harness-subsystems.md).

## Ordered slices

### S01 Build and offline composition

- [ ] Verify/pin latest GA JDK, Maven Wrapper and current compatible dependencies.
- [ ] Create four modules with permitted dependency direction; formatter and preview-enabled verification.
- [ ] Create immutable run/candidate/decision/money types and fake ports.
- [ ] Establish dependency boundary tests and stable `rahu-cli/target/rahu-cli.jar`/`bin/rahu` packaging contract.
- [ ] Run a packaged offline demo through the launcher; document actual commands.

Acceptance: A01, A19, A28. Evidence: clean-checkout verify command and demo result. Do not scaffold future modules. If preview APIs are used, demonstrate compile/test/package/run agreement first.

### S02 Feasibility and route resolution

- [ ] Write failing pool/effort/context/provider tests and generated invariant tests.
- [ ] Implement profile evidence, candidate IDs/order, exclusions, baseline/fallback resolution.
- [ ] Validate distributions and distinguish provider confidence from chosen probability.
- [ ] Implement shadow and active resolution with bounded uncertainty behavior.

Acceptance: A03–A07. Evidence: synthetic catalog/property results and inspect output. Test omitted/null/mandatory reasoning metadata explicitly.

### S03 Configuration and first CLI

- [ ] Implement validated JSON v1 and generated schema, precedence and secret references.
- [ ] Implement demo, config validate/show, route inspect and run against fakes.
- [ ] Ensure offline mode cannot call network; add stdout/stderr, error/exit tests.
- [ ] Validate context/session/single-agent settings and known adapters; no dynamic extension discovery.

Acceptance: A01, A15, A21. Evidence: example configs parse, invalid fields produce actionable errors. Add no baked-in live model assumption.

### S04 OpenRouter contracts

- [ ] Pin docs/contracts; implement catalog snapshots, decimal price units, capability freshness.
- [ ] Implement non-streaming generation with exact reasoning policy and parameter enforcement.
- [ ] Implement ordered tool-message mapping and opaque continuation envelope lifecycle.
- [ ] Test definitive/ambiguous failures, unknown costs and empty length response.

Acceptance: A04, A10, A16, A18. Evidence: local HTTP contract captures. Default test suite uses no keys/network.

### S05 Genuine System One HTTP adapter

- [ ] Pin deployed upstream contract/revision; add provenance and sanitised fixtures.
- [ ] Implement typed classification/relevance/choice/compaction requests and verified response profile.
- [ ] Bound payloads/timeouts, validate probabilities and preserve confidence semantics/model identity.
- [ ] Test invalid/missing/disagreeing answers, cancellation and independent credentials.
- [ ] Seed envelope tests from authored fixtures/provenance; reject truncation and avoid alias-as-checkpoint claims.

Acceptance: A02, A06. Evidence: contract tests; one explicitly budgeted real service smoke check if available. M1 live gate remains open if services/keys are unavailable.

### S06 Bounded runtime and ledger

- [ ] Write transition and attempt/deadline/admission tests before loop implementation.
- [ ] Count summary/fallback attempts and decision costs; retain uncertainty reserves.
- [ ] Propagate cancellation and cleanup; prevent retries of ambiguous paid/effect outcomes.
- [ ] Add serial tool batch state and complete terminal semantics.
- [ ] Add common authority pipeline, hierarchical run/session/experiment admission and exact no-progress detector.
- [ ] Prove deliberate single-agent mode, one active turn and absence of hidden paid fan-out.

Acceptance: A09, A12, A13, A24, A25, A27, A32. Evidence: fake-server/latch tests with no leaked work. Start structured concurrency only where independent child work exists. Session/experiment ledger reset tests precede interactive/live workflows.

### S07 Read-only dogfood tools

- [ ] Implement bounded list/read/literal-search with descriptor/schema validation.
- [ ] Enforce root, exclusions and no symlink traversal; document race limitations.
- [ ] System One chooses relevance; code retains authority and validation.
- [ ] Preserve IDs/results and expose invalid/denied observations correctly.
- [ ] Freeze registry/descriptors; run test-only compiled read tool through the same authority path; reject duplicate/effectful registrations.

Acceptance: A08, A09, A24, A26. Evidence: realistic repository fixtures including injected tool text and sensitive path cases. No shell/write tool.

### S08 Traces and offline replay

- [ ] Implement versioned envelopes, JSONL writer, metadata privacy and optional payload capture.
- [ ] Test persistence failure before/after effect start and incomplete/corrupt run reading.
- [ ] Implement trace inspect and deterministic policy replay using recorded inputs/fakes.
- [ ] Document replay limitations and forbid network/effects in replay.

Acceptance: A10, A11, A16. Evidence: golden traces, integrity tests and reproducible replay. Required effect-start persistence must be in place before live tools are enabled; use an in-memory event sink while S06/S07 tests are offline.

### S09 Minimal context compaction

- [ ] Write pinned-context and complete-tool-pair tests.
- [ ] Implement context item provenance, versioned prompt assembly and bounded System One projection.
- [ ] Implement explicit instruction files and in-process run/chat session ownership/failure retention/reset.
- [ ] Add System One compaction policy and joint summarisation routing.
- [ ] Preserve original context on failure; enforce next-request fit and compaction limits.
- [ ] Support context-only no-route preflight and stricter conversation allowance without invalidating summary-source admission.

Acceptance: A14, A18, A22, A23, A29, A32. Evidence: golden prompts, two-turn CLI test, long fake transcript and bounded real summary experiment when available. No vector memory, disk resume or hierarchical summariser.

### S10 Integrated smoke runner and evaluation baseline

- [ ] Freeze development/held-out task suites, exact versions and baseline policy before results.
- [ ] Add eval offline mode and explicit bounded live runs; include failure records and all costs.
- [ ] Implement suite/report v1, same-session turns and bounded synthetic compaction fixture; one experiment ledger across all phases.
- [ ] Exercise all seven MVP contracts together with fakes, including follow-up, summary, denied authority, no-progress, cancellation and replay.

Acceptance: A17, A22–A27, A29, A31, A32. Evidence: offline report with correct denominators/costs and failure accounting. M3 statistical comparison remains later; a smoke cannot promote default active routing.

### S11 Packaging and usable handoff

- [ ] Verify stable launcher/jar from a clean checkout; complete run/chat/inspect/replay/eval help and exact commands.
- [ ] Generate validated config schema and build manifest; check examples, wire fixtures, suites and docs destinations.
- [ ] Document a working compiled extension against actual ports and test cleanup/authority/dependency direction.
- [ ] Review real output in colourless/narrow/non-TTY/JSON modes; fix misleading routes/costs/failures.
- [ ] Convert the setup runbook's proposed commands to verified instructions; keep missing live checks marked.

Acceptance: A15, A19, A21, A26, A28, A31. Evidence: packaged smoke output, generated schema validation, extension test, precise quickstart. An API-only library does not satisfy this slice.

### S12 Release-wide critique and real dogfood

- [ ] Check G01–G10 and map every R01–R24/A01–A32 to actual test/report evidence.
- [ ] Run the bounded real dogfood suite using a genuine supplied System One service and OpenRouter, when explicit config/budget permit.
- [ ] Verify real classification/relevance/joint routing plus compaction/summary decisions; report degraded fallbacks rather than treating them as compatibility.
- [ ] Critically review cross-subsystem interactions and correct all high-severity findings.
- [ ] Produce sanitised release report, exact setup commands and remaining risks; keep shadow default until M3 promotion.

Acceptance: A20, A30, all release gates. If live prerequisites are missing, complete G01–G08/G10 and all code/docs, then report G09 blocked with the exact inputs needed. Do not leave other feasible slices undone. Mark M2 offline complete/live unverified rather than claiming a real run. A robust M3 quality comparison may require many more samples and paid work.

## Dependencies and continuous execution

| Slice | Requires | Unlocks |
|---|---|---|
| S01 | Preflight | S02/S03/S04/S05 foundations |
| S02/S03 | S01 | Legal config/routing/composition |
| S04/S05 | Core contracts; can progress independently as code tasks | Fake HTTP integration; real protocol smoke later |
| S06 | S02–S05 tested | Owned loop/admission/cancellation |
| S07/S08 | S06 | Safe tool behavior and persisted evidence; no live tools before both |
| S09 | S06–S08 | Chat/context/compaction |
| S10 | S09 | Integrated offline suite/report |
| S11 | S10 | Packaged operational readiness |
| S12 | S11 | Release review/live verification when supplied prerequisites permit |

Use independent work when a prerequisite is externally blocked, retaining unresolved gates. This is ordering guidance for one build owner, not authorisation to spawn parallel agents. Checkpoint/context recovery follows the autonomous contract; avoid restarting verified slices.

## Release review

Run all offline checks from a clean checkout; run packaged run/chat/replay workflows; verify examples/schema/suites/docs links; inspect route/error output; review credentials/redaction; confirm every requirement is mapped to passing evidence. Apply [G01–G10](../../release-gates.md) and the seven-area map. Any high-severity authority, capability, duplicate-effect, continuation, ledger-reset or cancellation finding blocks dogfood release. Document live gates honestly.

## Progress record

For each slice record commit, actual checks, result, review link and residual risk here and maintain `build-status.md` during implementation. Move this plan to completed only when all scoped work is verified; external live blockers remain explicit until resolved. Keep the next active plan linked from README.
