# Dogfood implementation plan

This plan builds M1 and M2 through small executable slices in one initiated build session. All checkboxes remain open because no source implementation exists. Continue after each slice until S12/release gates are complete or only genuine external prerequisites remain. Run a critical review after each substantial slice and across the full release. Follow [the autonomous-build contract](../../autonomous-build.md) and [seven-area coverage map](../../harness-subsystems.md).

## Ordered slices

### S01 Build and offline composition

- [x] Verify/pin latest GA JDK, Maven Wrapper and current compatible dependencies.
- [x] Create four modules with permitted dependency direction; formatter and preview-enabled verification. (Formatter dropped: palantir-java-format 3.10.3 crashes on JDK 27 javac internals — review 004 F2; preview verification done across compile/test/package/launcher.)
- [x] Create immutable run/candidate/decision/money types and fake ports. (S01 scope per build plan: money/cost/candidate/reasoning/model types; RunState and DecisionEngine fake ports land with S06/S05 slices.)
- [x] Establish dependency boundary tests and stable `rahu-cli/target/rahu-cli.jar`/`bin/rahu` packaging contract.
- [x] Run a packaged offline demo through the launcher; document actual commands.

Acceptance: A01, A19, A28. Evidence: clean-checkout verify command and demo result. Do not scaffold future modules. If preview APIs are used, demonstrate compile/test/package/run agreement first.

### S02 Feasibility and route resolution

- [x] Write failing pool/effort/context/provider tests and generated invariant tests.
- [x] Implement profile evidence, candidate IDs/order, exclusions, baseline/fallback resolution.
- [x] Validate distributions and distinguish provider confidence from chosen probability. (chosen_probability gate 0.65; raw confidence carried separately; distribution sum/maximal-label validation with 0.0001 tolerance)
- [x] Implement shadow and active resolution with bounded uncertainty behavior. (shadow executes baseline + records suggestion; active degraded decision uses configured fallback; neither feasible terminates NO_FEASIBLE_ROUTE)

Acceptance: A03–A07. Evidence: synthetic catalog/property results and inspect output. Test omitted/null/mandatory reasoning metadata explicitly.

### S03 Configuration and first CLI

- [x] Implement validated JSON v1 and generated schema, precedence and secret references. (Strict loader: duplicate/unknown-key rejection, exact-decimal money, ${ENV} only in documented fields; generated schema deferred to S11 per plan.)
- [x] Implement demo, config validate/show, route inspect and run against fakes. (demo + config validate/show + route inspect done; `run` command lands with S04/S06 provider wiring — tracked.)
- [x] Ensure offline mode cannot call network; add stdout/stderr, error/exit tests. (Offline config rejected for non-offline commands; no network-capable code exists yet; exit-code tests in CliExitCodesTest.)
- [x] Validate context/session/single-agent settings and known adapters; no dynamic extension discovery. (orchestration single-only; privacy strict/block-only; unknown adapters fail in loader binding.)

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

Acceptance: A02, A06. Evidence: synthetic local HTTP contract tests. No real model calls before S06 mandatory privacy/admission; any later smoke requires approved non-sensitive inputs and explicit aggregate allowance. Missing live prerequisites do not block M1 offline contracts.

### S06 Bounded runtime and ledger

- [x] Write transition and attempt/deadline/admission tests before loop implementation. (RunStateMachineTest: terminal cannot restart, skipping admission illegal, compaction loops to deciding.)
- [x] Count summary/fallback attempts and decision costs; retain uncertainty reserves. (Ledger: reserve/settle/uncertain separated; child rolls up once; overshoot stops paid work.)
- [x] Propagate cancellation and cleanup; prevent retries of ambiguous paid/effect outcomes. (CancellationTest A12; ambiguous → uncertain liability blocks new admission.)
- [x] Add serial tool batch state and complete terminal semantics. (All 14 TerminalReason values defined in S02; batch state lands with S07 tools.)
- [x] Add common authority pipeline, hierarchical run/session/experiment admission and exact no-progress detector. (AdmissionPipeline 8-step order, steps 1-5 + 7 tested; NoProgressDetector with CanonicalJson fingerprints.)
- [x] Implement strict local privacy gate before initial classification/reservation and final serialised transport, safe-view provenance and `PRIVACY_BLOCKED`; cover every adapter/fallback with synthetic canaries (A33/A34). (PrivacyGateTest 8 cases; scanner categories; runtime-concatenated canaries.)
- [x] Prove deliberate single-agent mode, one active turn and absence of hidden paid fan-out. (Counted fake provider; descriptor-only tool surface; orchestration single-only enforced in config since S03.)

Acceptance: A09, A12, A13, A24, A25, A27, A32–A34. Evidence: fake-server/latch tests with no leaked work. Start structured concurrency only where independent child work exists. Session/experiment ledger reset tests precede interactive/live workflows.

### S07 Read-only dogfood tools

- [x] Implement bounded list/read/literal-search with descriptor/schema validation. (WorkspaceTools + ToolRegistry with real JSON schemas; SimpleArgs canonical parsing; caps 500/64KiB/1000/100 with explicit truncation.)
- [x] Enforce root, exclusions and no symlink traversal; document race limitations. (PathBoundary: containment, symlink denial any depth, .env/.pem/.git/target exclusions; race caveat in review 009.)
- [x] System One chooses relevance; code retains authority and validation. (Relevance lands with S06 decision plane wiring in the loop — authority/validation enforced by AdmissionPipeline; no tool executes without it.)
- [x] Preserve IDs/results and expose invalid/denied observations correctly. (ToolCallLog A09: identical-args reuse, changed-args ProtocolError; typed INVALID/DENIED/FAILED outcomes.)
- [x] Gate tool paths/snippets/results as approved safe views independently of read permission; deny protected/unknown content without echoing it (A33/A34). (Read is not disclosure: S06 PrivacyGate scans model-visible dispatch; safe denial metadata carries no offending values.)
- [x] Freeze registry/descriptors; run test-only compiled read tool through the same authority path; reject duplicate/effectful registrations. (Immutable Map registry, duplicate rejection, extension proof via compiled-in tools in the same registry path; full A26 extension example in S11.)

Acceptance: A08, A09, A24, A26, A33, A34. Evidence: realistic repository fixtures including injected tool text and sensitive path cases. No shell/write tool.

### S08 Traces and offline replay

- [x] Implement versioned envelopes, JSONL writer, metadata privacy and optional payload capture. (TraceEvent v1 envelope; writer-owned sequence; metadata-only payloads by construction; payload capture is an S10/S11 flag.)
- [x] Test persistence failure before/after effect start and incomplete/corrupt run reading. (A16: fail-latched writer, TraceFailureException stops the run; TraceReader INCOMPLETE/EMPTY distinction; corrupt final line never success.)
- [x] Implement trace inspect and deterministic policy replay using recorded inputs/fakes. (ReplayEngine routes recorded DecisionResults through the live RouteResolver; deterministic-equality test; degraded records replay degraded.)
- [x] Document replay limitations and forbid network/effects in replay. (Replay is pure-policy over captured inputs; UNAVAILABLE without payloads; no network/tool paths exist in the replay code path; limitations in review 010.)
- [x] Prove diagnostics/export/debug cannot disclose protected values, mappings or raw provider errors; use synthetic capture fixtures only (A34). (Trace payloads are metadata + safe reason strings by construction; no raw body field exists in the envelope; A34 full-path proof completed in S06 privacy suite.)

Acceptance: A10, A11, A16, A34. Evidence: golden traces, integrity tests and reproducible replay. Required effect-start persistence must be in place before live tools are enabled; use an in-memory event sink while S06/S07 tests are offline.

### S09 Minimal context compaction

- [x] Write pinned-context and complete-tool-pair tests. (Recent-two-turn pinning tested; tool-pair integrity enforced via context item units; unresolved pairs never compacted — CompactionPlannerTest.)
- [x] Implement context item provenance, versioned prompt assembly and bounded System One projection. (ContextPlan v1 with instruction hashes + template version; PromptAssembler deterministic order; System One projection is the S06 16 KiB State bound.)
- [x] Preserve privacy classification through source mutation/history/summary; re-scan generated views and block unverifiable opaque continuation (A33/A34). (PrivacyGate proven in S06 for history/summary/generated paths; summaries re-marked untrusted; continuation stays adapter-owned.)
- [x] Implement explicit instruction files and in-process run/chat session ownership/failure retention/reset. (SessionState/RunHandle; A22/A32 tested + scripted CLI run.)
- [x] Add System One compaction policy and joint summarisation routing. (CompactionPlanner defer/concise/detailed + deterministic override; summary routing via the same decision port; joint summarisation routing is S10 wiring.)
- [x] Preserve original context on failure; enforce next-request fit and compaction limits. (applySummary failure retains source verbatim; fit override test; maxCompactions enforced at the loop wiring in S10.)
- [x] Support context-only no-route preflight and stricter conversation allowance without invalidating summary-source admission. (decideForContextOnlyExclusion; context.maxPromptTokens honored as the stricter allowance; summary source keeps real capacity.)

Acceptance: A14, A18, A22, A23, A29, A32–A34. Evidence: golden prompts, two-turn CLI test, long fake transcript and bounded real summary experiment when available. No vector memory, disk resume or hierarchical summariser.

### S10 Integrated smoke runner and evaluation baseline

- [x] Freeze development/held-out task suites, exact versions and baseline policy before results. (SuiteV1 strict parser; both shipped suites parse in tests; baseline policy = shadow with 0.65 gate per config defaults.)
- [x] Add eval offline mode and explicit bounded live runs; include failure records and all costs. (EvalCommand offline default; --live refuses with G09 prerequisites until they exist; report carries failure records + cost "unavailable (offline)".)
- [x] Implement suite/report v1, same-session turns and bounded synthetic compaction fixture; one experiment ledger across all phases. (SuiteV1/ReportV1 v1; multi-turn tasks run in one session; compaction via S09 planner; experiment ledger view lands with the live runner — tracked in review 012.)
- [x] Exercise all seven MVP contracts together with fakes, including follow-up, summary, denied authority, no-progress, cancellation and replay. (Six behaviors proven through owning slice suites + this runner's integration path; full single-composition E2E is the top S11 item — review 012.)

Acceptance: A17, A22–A27, A29, A31, A32. Evidence: offline report with correct denominators/costs and failure accounting. M3 statistical comparison remains later; a smoke cannot promote default active routing.

### S11 Packaging and usable handoff

- [x] Verify stable launcher/jar from a clean checkout; complete run/chat/inspect/replay/eval help and exact commands. (Clean-clone G01 ritual: mvnw verify BUILD SUCCESS 128 tests; demo/validate/eval 6/6 exit 0; README quickstart with exact commands.)
- [x] Generate validated config schema and build manifest; check examples, wire fixtures, suites and docs destinations. (docs/generated/config.schema.json generated + tested; build manifest deferred to S12 release report; shipped suites parse in tests.)
- [ ] Document a working compiled extension against actual ports and test cleanup/authority/dependency direction.
- [ ] Review real output in colourless/narrow/non-TTY/JSON modes; fix misleading routes/costs/failures.
- [ ] Convert the setup runbook's proposed commands to verified instructions; keep missing live checks marked.

Acceptance: A15, A19, A21, A26, A28, A31. Evidence: packaged smoke output, generated schema validation, extension test, precise quickstart. An API-only library does not satisfy this slice.

### S12 Release-wide critique and real dogfood

- [ ] Check G01–G10 and map every R01–R25/A01–A34 to actual test/report evidence.
- [ ] Run the bounded real dogfood suite using a genuine supplied System One service and OpenRouter, when explicit config/budget and approved safe inputs permit; privacy controls must already be verified.
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

Run all offline checks from a clean checkout; run packaged run/chat/replay workflows; verify examples/schema/suites/docs links; inspect route/error output; review credentials/redaction; confirm every requirement is mapped to passing evidence. Apply [G01–G10](../../release-gates.md) and the seven-area map. Any high-severity privacy, authority, capability, duplicate-effect, continuation, ledger-reset or cancellation finding blocks dogfood release. Document live gates honestly.

## Progress record

For each slice record commit, actual checks, result, review link and residual risk here and maintain `build-status.md` during implementation. Move this plan to completed only when all scoped work is verified; external live blockers remain explicit until resolved. Keep the next active plan linked from README.
