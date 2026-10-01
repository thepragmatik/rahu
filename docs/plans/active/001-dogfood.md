# Dogfood implementation plan

This plan builds M1 and M2 through small executable slices. All checkboxes remain open because no source implementation exists. Run a critical review after each substantial slice, not only at release.

## Ordered slices

### S01 Build and offline composition

- [ ] Verify/pin latest GA JDK, Maven Wrapper and current compatible dependencies.
- [ ] Create four modules with permitted dependency direction; formatter and preview-enabled verification.
- [ ] Create immutable run/candidate/decision/money types and fake ports.
- [ ] Run a packaged offline demo through the launcher; document actual commands.

Acceptance: A01, A19. Evidence: clean-checkout verify command and demo result. Do not scaffold future modules. If preview APIs are used, demonstrate compile/test/package/run agreement first.

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

Acceptance: A02, A06. Evidence: contract tests; one explicitly budgeted real service smoke check if available. M1 live gate remains open if services/keys are unavailable.

### S06 Bounded runtime and ledger

- [ ] Write transition and attempt/deadline/admission tests before loop implementation.
- [ ] Count summary/fallback attempts and decision costs; retain uncertainty reserves.
- [ ] Propagate cancellation and cleanup; prevent retries of ambiguous paid/effect outcomes.
- [ ] Add serial tool batch state and complete terminal semantics.

Acceptance: A09, A12, A13. Evidence: fake-server/latch tests with no leaked work. Start structured concurrency only where independent child work exists.

### S07 Read-only dogfood tools

- [ ] Implement bounded list/read/literal-search with descriptor/schema validation.
- [ ] Enforce root, exclusions and no symlink traversal; document race limitations.
- [ ] System One chooses relevance; code retains authority and validation.
- [ ] Preserve IDs/results and expose invalid/denied observations correctly.

Acceptance: A08, A09. Evidence: realistic repository fixtures including injected tool text and sensitive path cases. No shell/write tool.

### S08 Traces and offline replay

- [ ] Implement versioned envelopes, JSONL writer, metadata privacy and optional payload capture.
- [ ] Test persistence failure before/after effect start and incomplete/corrupt run reading.
- [ ] Implement trace inspect and deterministic policy replay using recorded inputs/fakes.
- [ ] Document replay limitations and forbid network/effects in replay.

Acceptance: A10, A11, A16. Evidence: golden traces, integrity tests and reproducible replay. Required effect-start persistence must be in place before live tools are enabled; use an in-memory event sink while S06/S07 tests are offline.

### S09 Minimal context compaction

- [ ] Write pinned-context and complete-tool-pair tests.
- [ ] Add System One compaction policy and joint summarisation routing.
- [ ] Preserve original context on failure; enforce next-request fit and compaction limits.

Acceptance: A14, A18. Evidence: long fake transcript and bounded real summary experiment when available. No vector memory or hierarchical summariser.

### S10 Dogfood and evaluation baseline

- [ ] Freeze development/held-out task suites, exact versions and baseline policy before results.
- [ ] Add eval offline mode and explicit bounded live runs; include failure records and all costs.
- [ ] Run read-only live repository Q&A with a genuine System One service and OpenRouter.
- [ ] Record critical review, remaining gaps and shadow/active promotion decision.

Acceptance: A17, A20, full R01–R16 matrix. Evidence: sanitised report with actual commands/versions and task outcome, not raw private traces. This establishes M2; a robust M3 quality comparison may require more samples.

## Release review

Run all offline checks from a clean checkout; run a packaged command; verify examples/schema/docs links; inspect route/error output; review credentials/redaction; confirm every requirement is mapped to passing evidence. Document live gates honestly. Any high-severity authority, capability, duplicate-effect or cancellation finding blocks dogfood release.

## Progress record

For each slice record commit, actual checks, result, review link and residual risk here. Move this plan to completed only when M2 gate is met. Keep the next active plan linked from README.
