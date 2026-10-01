# Seven subsystem and autonomous build review

Date: 2026-10-01. Scope: MVP completeness, build autonomy, cross-document consistency and evidence. This is a specification review; no runtime code/live smoke has been executed.

## Hypothesis and alternatives

An autonomous builder can reach useful M2 dogfooding from the repository without reconstructing prior chat if each of the seven areas has an explicit small contract, tests and release gates, and the builder is directed to continue through increments. More prose alone is insufficient; operational setup, packaging, dependency ordering and observable completion must be concrete.

The simpler prior plan gave a strong routing/tool kernel but risked stopping after S01/S02 and did not define follow-up session semantics. A full platform with durable memory, plugins, OS sandbox and delegation would make completion harder. The revised scope adds memory-only chat, clear trust/context, deterministic authority, compiled-in extension proofs and deliberate single-agent orchestration; it retains four modules and read-only tools.

## Research check

Re-read [the source study section 2.3/table 1](https://arxiv.org/html/2609.00006v1#S2.SS3). The seven categories match [the coverage map](../harness-subsystems.md). Its orchestration category means agent coordination, and a single-agent deliberate absence is a valid minimal position. We therefore avoid rebranding the ordinary runtime loop as implemented multi-agent orchestration.

Revisited primary guidance on simple composable agents and repository knowledge in [the evidence map](../research/evidence.md). Checked System One envelope against upstream conformance-source blob `86841aad9e740b73697196de49d89b9426fafc15`; authored synthetic fixtures instead of copying code or presenting invented probabilities as actual results.

## Findings and changes

| Severity | Finding | Correction and evidence to implement | Residual risk |
|---|---|---|---|
| High | First-handoff language let a builder stop after scaffold/tests | Continuous S01–S12 contract, checkpoint/status guidance, kickoff prompt and G01–G10 | Environment/time/context can still interrupt a session |
| High | Seven-area coverage was uneven and deferrals looked like implementation | Explicit map with before/after, owners/tests and minimal absence for orchestration | Coverage does not establish quality |
| High | Follow-up memory ownership and reset semantics were undefined | In-process sessions; one active turn; reset preserves aggregate allowance/liability | No cross-process resume/recovery |
| High | Empty context-feasible candidate sets could prevent the compaction intended to fix them | Context preflight checks larger candidates/source-summary admission before bounded rebuild | Source may still exceed every model/budget |
| High | Separate eval/chat phases could reset spending limits | Hierarchical experiment/session/run ledger; totals counted once; suite runs phases in one owner | Remote billing may still overshoot estimates |
| High | Prompt/repository instructions could be silently promoted to authority | Versioned context items/roles, explicit data-only instruction files, common deterministic admission | Prompt injection may affect answer quality despite denied effects |
| High | Failed tool batches risked orphaned future tool-role messages | Retain bounded diagnostics as untrusted source data or omit incomplete units; no fake completions | Provider envelopes need real compatibility tests |
| High | Reported System One model labels can echo request aliases | Reported identity separated from checkpoint evidence; unknown explicit | Real server may not expose served checkpoint |
| Medium | Hosted decision pricing did not have a clear independent admission source | Explicit local-unbilled versus configured-tariff evidence, expiry and independent reservation | Tariff/usage drift needs verification |
| Medium | A live compaction test could require an enormous expensive context | Stricter normal-conversation soft allowance and bounded synthetic fixture; summary source uses real capacity | Semantic fidelity and routing quality remain empirical |
| Medium | Exact repeat guard could be vague or require another cycle before stopping | Three completed identical batches terminate immediately; state retained across compaction | Semantically repetitive differing calls still hit ordinary limits |
| Medium | Extensibility was only an interface diagram | Compiled registration/lifecycle/version rules, extension acceptance proof, no policy bypass | API shape may evolve before release |
| Medium | A one-command build could hide packaging or setup failure | Stable launcher/jar, schemas, manifest, verified commands, output/aesthetic checks | Actual preview/dependency compatibility remains untested |
| Medium | A missing service might cause either early stopping or fabricated live success | Independent offline/live/optimisation statuses, external-prerequisite preflight and runbook | Live gate requires owner-supplied services/config/budget |

## Whole-system critical decisions

Proceed with this uplift. Do not add effectful tools or delegation to fill taxonomy rows. Dogfood alpha supports repository analysis/review and follow-up, not autonomous editing/testing by Rahu itself. The builder's tools are separate from the built harness's authority. Training, native inference, durable memory and third-party code loading remain deferred.

Real model-quality confidence is not implied by protocol validity, concentration or successful smoke. Keep default shadow routing. Compile-time ports are useful extensibility; a plugin ecosystem is not needed. Seven subsystems remain four cohesive modules, with progressively loaded documentation to protect build-agent context.

## Documentation verification

Validate local Markdown destinations/code fences, JSON syntax, R01–R24/A01–A32 uniqueness and references, example candidate/session settings, suite IDs/input/rubric shape, and synthetic distribution membership/sums/confidence arithmetic. Check plan S01–S12 and G01–G10 appear in the handoff and active release. GitHub commit is made only after these checks and review corrections; remote blob-hash comparison verifies the committed artifact set afterward.

The final local audit covers 57 files, including 48 Markdown documents and seven JSON artifacts. An initial consistency assertion found the autonomous contract referred to the complete active plan without naming S01–S12 explicitly; that ambiguity was corrected before the final passing validation. Runtime/CLI/model tests remain unperformed because this turn updates specifications only.

## Build-time checks still required

Run red/green tests for A22–A32 and all previous scenarios, real protocol roundtrips, packaged preview launcher, session reset/failure/aggregate ledger, source-summary preflight and actual CLI output. Close high-severity implementation findings before release. If G09 cannot execute, finish all other work and disclose the exact blocker; do not reinterpret it as a passed live gate.
