# Memory and context specification

## Ownership and session scope

MVP memory is a bounded in-process conversation and provenance-bearing summaries. It is not vector memory, a durable knowledge base or crash recovery. `run` starts a fresh session with one user turn; `chat` retains history across user turns within one process. Each turn creates a new run ID with the session ID and turn index. Only one turn may be active per session.

Default session limits: 20 user turns and USD 3.00 aggregate admission allowance, in addition to each run's eight generation attempts, 180-second deadline and USD 1.00 admission allowance. A session uses the intersection of session and run admission budgets. Carry reservations, reported/estimated settlements and uncertain liability across turns; starting a new turn cannot erase liabilities. No idle/background paid work. Session limit exhaustion stops new turns and reports the reason. A process restart loses history/ledger and is not a continuation of an earlier session.

Configuration/catalog snapshots are frozen for a turn. At the next turn refresh expired evidence before paid admission, preserving history and compatibility constraints. Config changes require a new session; ordinary refreshed model metadata does not change the allowed pool. If a retained opaque continuation becomes incompatible, fail clearly rather than leaking it to another model or silently stripping it.

## Context item contract

Items carry stable ID, kind, source/provenance, trust tier, content or private payload reference, content hash, creation turn and estimated token allowance. Kinds include harness instruction, operator instruction, user request, assistant answer, assistant tool batch, tool observation, summary and opaque provider continuation. A completed tool unit contains the full assistant batch plus one observation per call. Incomplete units cannot be compacted or reordered.

Do not automatically ingest the repository. Read through bounded tools or explicit instruction inputs. An `AGENTS.md` discovered through a tool is repository data, not automatically a privileged runtime instruction. The build agent's own AGENTS.md instructions are a separate concern from what the built Rahu runtime loads.

Trust tier and [privacy classification](privacy.md) are separate: a privileged operator instruction can still contain restricted data. Keep original local items distinct from model-visible safe views. Apply privacy before the initial decision projection and prompt assembly; revalidate final outbound content. File selection, pinning or approval for behavioral guidance cannot authorise sending PII/secrets/confidential text.

## Prompt assembly

Assemble deterministically using versioned templates with this order:

1. Harness behavior and capabilities: factual product role, permitted tools, authority limits, honest uncertainty and stop semantics.
2. Explicit operator instruction files, in configured order, within the harness's authority boundary.
3. Historical completed conversation units or untrusted summary with provenance.
4. Current user request and unresolved/recent tool units in chronological order.

Roles depend on provider support. Map privileged instructions to supported system/developer roles and keep tool observations in the documented tool role with call IDs. Do not put tool/repository content into a system role. Preserve opaque provider continuation through its adapter-owned representation. Prompt boundaries aid interpretation but are not a security enforcement mechanism.

Record template/instruction hashes, item order, token-estimator version and available context. Trusted instructions and tools count toward prompt allowance. An operator file is a local UTF-8 file explicitly listed in `context.instructionFiles`; max 16 KiB per file, 32 KiB total, no recursive include, symlink or remote URL. No auto-loading skills or executing code in Markdown. Oversized inputs fail with the field/path and next action. Snapshot instruction contents for the session; changes require a new session.

The generation prompt uses the full admitted context. System One gets a versioned bounded feature projection: current request, operation constraints, recent relevant observations and candidate facts. Its 16 KiB limit includes untrusted projected content; candidate/question overhead has separate limits. Record omitted item IDs/segments. Never silently truncate trusted constraints, candidate choices or a code point; if mandatory fields cannot fit, return a typed input-too-large outcome and apply the routing failure policy. Do not invent an extra unbounded summary call to prepare router input.

## Compaction algorithm

Follow [runtime](runtime.md) for policy and budget. Pin harness/operator instructions, current user intent, authority/config state, unresolved tool units and required continuation. At 80% context pressure System One chooses defer/concise/detailed, with deterministic override when fit is impossible. Use the summarisation pool; concise target is at most 512 estimated output tokens, detailed at most 1024, capped by model/output/run allowances. Route the actual summary input; do not pick a small model before checking it fits.

Summarise complete earlier units, retaining the recent two complete generation/tool units when feasible. The summary template asks for goal, accepted decisions, facts with paths, unresolved questions and failed approaches; it must label uncertain claims. Attach source item IDs/hash list. Required pinned structured facts are retained separately from the generated summary, so an unverifiable summary does not become a source of authority. Check bounded output, next-request fit and integrity; preserve the original on failure. Semantic fidelity remains an evaluation question.

When a cheaper route cannot fit existing context, consider permitted larger-context candidates before compaction. When none fit, construct a compaction plan using a feasible summary candidate and re-run candidate admission on the compacted view. This preflight path must be possible even when the initial answer candidate set is empty solely due to context. If pinned content alone exceeds capacity or no summary candidate admits source/budget, terminate `CONTEXT_LIMIT`. Never loop between routing and compaction indefinitely.

The optional `context.maxPromptTokens` is a stricter conversation prompt allowance; default is the selected model's usable context after output reserve. Use the minimum of model capacity and this allowance for ordinary conversation/pressure checks. It does not replace the summary model's true source-input capacity. A lower operator allowance can exercise compaction with modest synthetic input in smoke tests, while every actual request must still fit its real model and spend limits.

## Follow-up and reset behavior

After `ANSWER_COMPLETE`, add the completed turn to session history. On a failed turn keep the user request and an explicit failure observation; retain completed read-only observations for diagnosis, discard incomplete generated content from future prompts, and settle/retain liabilities. Never create an assistant answer from partial output. Provider-specific opaque data can be retained only at a documented safe boundary.

Never send an orphan tool observation as a future tool-role message. If only part of a batch completed, retained diagnostic observations are untrusted source data with provenance, or the whole failed unit is omitted from model context while remaining in the private run trace. Do not invent successful results for unexecuted calls to make a transcript look valid.

History and summaries retain privacy/provenance, and generated content is rechecked before later model use. Never send raw protected history to a summariser to obtain a 'sanitised' view. Opaque continuation must meet origin/privacy requirements as well as provider compatibility; unknown blocks cannot be redacted in place or silently cleared to hide incompatibility.

`/reset` clears conversational content and continuation after the active turn completes; it does not reset the session ID, aggregate budget or turn count. Starting a new session is explicit. `/exit`, EOF and interruption release memory/resources. No automatic disk transcript persistence: metadata traces remain default; payload capture is a separate explicit privacy choice. Resume/fork/export/import are later contracts.

## Verification

A22 tests multi-turn history and aggregate budgets; A23 tests template/role/projection ordering and instruction inputs; A14/A18/A29 test compaction, continuation and context-only empty candidates; A32 tests reset, failure retention and isolation. Use golden prompt fixtures per adapter, recording synthetic content only. Add no persistence service or vector store for these requirements.
