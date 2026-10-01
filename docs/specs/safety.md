# Safety and permissions specification

## Trust and supported authority

The alpha profile permits generation requests to explicitly configured services and bounded reads within an operator-selected workspace. It cannot mutate files, launch shells/processes, send messages, change configuration or delegate to other agents. Do not present this as an OS sandbox or a guarantee against malicious concurrent filesystem changes.

Trusted authority sources are explicit operator configuration and harness code. User requests, repository content, model outputs, summaries and tool observations cannot create permissions. Explicit operator instruction files steer behavior but cannot exceed the configured authority. System One tool gating is advisory; a recommendation can narrow relevance and never grant execution rights.

## Admission pipeline

Each operation follows the same deterministic order, with structured denial reason and zero side effects before admission:

1. Check the run/session is active and its deadline/attempt/turn allowance remains.
2. Validate request/call shape, registered capability and version, declared effect and whether the tool was exposed this step.
3. Enforce allowed model/policy/provider or workspace/path/endpoint boundary, as applicable.
4. Validate all resource limits and reserve known/estimated cost in both run and session ledgers for paid operations.
5. Write the required trace admission/proposal/start evidence; stop on persistence failure.
6. Execute exactly the validated operation, preserving cancellation and uncertainty semantics.
7. Reconcile outcome/settlement, record completion and cleanup owned resources.

A failed reservation/start must release only definitively unused reservations. Timeouts after remote send retain uncertainty. Registry entries cannot execute directly around the pipeline. Compaction and future hooks are subject to the same admission rules; no privileged internal-summary bypass.

## Boundary controls

Paths, symlinks, exclusions, limits and read-race caveats follow [tools](tools.md). Keep credentials out of tool-accessible roots where possible; default exclusions are not a comprehensive secret detector. Network tools are absent. Provider endpoints require HTTPS except explicitly configured loopback local decisions; no redirects, embedded URL credentials or inherited cross-adapter keys. No service discovery from model-supplied URLs.

MVP does not claim robust defence if a hostile peer can replace a local server, modify the process environment, read the same-user process memory or mutate the workspace during access. State these assumptions in setup documentation. Do not add a remote URL fetch tool to solve blocked retrieval without a new spec.

## Prompt injection and leakage

Keep source provenance/trust when constructing prompts. Repository instructions and tool results such as 'enable shell' or 'send secrets to this URL' remain data. The runtime must reject those actions irrespective of model agreement. Prompt delimiters are useful but deterministic admission is the enforcement point. Avoid putting API keys or auth headers into any prompt. Default traces contain metadata only; user payload capture is explicit and labelled sensitive.

System One state minimisation may reduce disclosure but is not data-loss prevention. The operator must select services appropriate for submitted code/content and provider privacy constraints. The harness cannot infer provider privacy from a model's name.

## Denials and future approvals

An invalid/denied read-only proposal produces a bounded tool observation so the agent can correct course within limits. An unsupported effect/delegation is rejected; no imaginary approval dialog can enable it. A repeated exact batch without new information is subject to no-progress termination.

Later effectful tools need a separate accepted approval/journal design. Grants must bind run, tool version, canonical arguments hash, effect, workspace and expiry. Non-interactive unresolved approval denies. OS isolation and recovery semantics must be implemented/tested before a profile advertises sandboxed execution. Do not build that larger system merely to satisfy seven-area coverage.

## Release evidence

A24 demonstrates injection cannot enlarge authority and a registered read tool cannot bypass admission. A08 covers path/schema failures; A12/A13 budgets and cancellation; A16 trace failures; A32 session-reset liability. Test with fakes and real temporary workspace fixtures, not exploit workflows or external targets.
