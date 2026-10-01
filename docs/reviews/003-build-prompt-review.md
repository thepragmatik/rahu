# Build prompt and outbound privacy review

Date: 2026-10-01. Scope: root initiating prompt and affected contracts, plans/examples/release gates. Method: adversarial self-review; no additional agents or live provider calls. This is a specification review, not runtime/model-quality verification.

## Hypothesis, smaller alternative and falsification

A single initiating prompt can steer continuous incremental M1/M2 delivery if it names the objective, reading order, nonnegotiable architecture, evidence gates, privacy boundaries and continuation rules. The smallest useful result is that prompt plus aligned contracts, not a new runtime feature in this session. A short link-only kickoff was considered; it omitted operating priorities and left privacy enforcement ambiguous, so the comprehensive root prompt is authoritative for initiation while detailed behaviors remain in linked specs.

Two likely failures were drift between the prompt and older contracts, and a prohibition that existed only in instructions while early provider calls bypassed enforcement. Falsifying checks include unmatched requirement/scenario references, unreachable release order, a permitted protected payload path, unusable safe-source approval, and confusing evidence claims. Review also checks that new privacy work does not expand the MVP into native inference, a plugin framework, durable recovery or delegated execution.

## Findings and corrections

| Severity | Adversarial challenge and evidence | Correction | Residual limit |
|---|---|---|---|
| High | Log redaction/path exclusions did not gate the first System One request or actual provider body | Added mandatory privacy spec/R25/A33–A34, safe views, initial admission and final adapter transport checks | Implementation must prove dispatch counts/wire bytes; detectors are imperfect |
| High | An LLM sanitiser/summary could receive the original before producing a safe result | Prohibited provider privacy inference and raw protected summary sources; local transformations only | A safe transformation must preserve essential semantics or block |
| High | Local routing, fallback or model output could launder private content | Applied gate to both services, summaries/retries/candidates/tools/generated history and opaque continuation; no loopback exception | Unknown encoded/opaque content blocks; no universal decoding proof |
| High | S05/M1 allowed a real smoke before S06 authority/privacy existed | M1 now offline adapter contracts; defer all real smoke until privacy/admission and safe input are verified | Missing inputs leave live gates blocked while offline work proceeds |
| High | Reading an allowed path or marking it approved could override disclosure checks | Separate read/disclosure permissions; exact-hash source manifest, mutation invalidation, protected findings cannot be overridden | Human non-sensitive assessment is fallible; source metadata also needs minimisation |
| High | Raw tool errors, debug, capture, export or build-agent output could leak outside normal prompts | Safe diagnostics/export, local-only mappings, synthetic fixtures, no remote telemetry; builder avoids raw protected tool output | Host agent/tool platform is outside Rahu; no retroactive deletion/retention claim |
| Medium | An absolute credential ban would make legitimate API authentication impossible | Narrow matching-service auth-field exception, distinct keys, TLS policy and no redirects | Same-user process/local-server trust assumptions remain |
| Medium | “Zero reservations” could imply clearing earlier spend when a final dispatch recheck failed | Distinguish pre-admission zero allocation from later release of definitely unused reservation | Existing settled/uncertain liabilities remain |
| Medium | Strict unknown default without a defined operator workflow could make safe dogfooding unusable | Defined input-classification option/config, exact manifest fields, synthetic provenance and runbook prerequisites | No automatic repository-wide approval; safe-only partial smoke cannot pass full G09 |
| Medium | Adding acceptance cases could leave coverage/plan counts stale | Extended R25/A33–A34, seven-area owners, slice acceptance, gates, examples and configuration | Historical reviews retain their original counts as dated evidence |

## Comprehensiveness and coherence audit

| Dimension | Reviewed direction |
|---|---|
| Objective and stopping condition | One initiated session through S01–S12, M1/M2 only; offline/live/statistical claims separate |
| Core themes | Genuine System One, joint model/effort choices, independent endpoint/model/auth/pricing, explicit legal pools, measured cost/quality and shadow default |
| Seven harness areas | Minimum and exclusions for loop/integration/tools/context/safety/single-agent orchestration/compiled-in extension |
| Engineering and aesthetics | Latest verified GA Java, justified consistent previews, four modules, TDD/immutable state/exact money/bounded lifetimes; real accessible CLI output review |
| Continuity and delivery | Checkpoint/evidence file, small commits, resume incomplete slices, complete independent work under external blockers; packaged launcher/schema/examples/runbook/report |
| Authority and privacy | Builder/product scopes distinct; no inferred grants, no protected provider content, safe provenance and auth exception, privacy release blockers |
| Evidence and honesty | Requirements/scenarios/gates mapped to actual tests; fake fixtures not real results; detector/host/quality limits explicit |

Structural validation checks relative documentation destinations, balanced fences/final newlines, JSON parsing, unique R01–R25/A01–A34 and complete slice/gate counts. The manifest contract was reviewed for exact fields and safe defaults, not presented as an implemented parser. Validation passed: 61 repository files, 214 relative links/anchors, 7 JSON files, 25 unique requirements, 34 unique acceptance scenarios, 12 slices and 10 gates. Existing files were preserved; both config examples retain valid pool references and strict privacy defaults. No runtime tests or live smoke are claimed in this documentation-only revision.

## Release risk and next proof

No unresolved high-severity contradiction was found after these corrections in the reviewed specification paths. That judgment is bounded by this self-review, not a security certification. The build must still implement/prove A33/A34 across real adapters and every extension path, establish safely approved live inputs and exercise G09 with genuine services. Local same-user/host compromise, unknown detector gaps, semantic sanitisation fidelity and opaque provider content remain explicit limits; blocking uncertain data is the prescribed response.
