# Acceptance scenarios

These scenarios become named tests and release evidence. A scenario is a behavioral requirement, not a request to mirror implementation internals. Implement failing tests before behavior; record real verification in the active plan.

| ID | Given | When | Required result |
|---|---|---|---|
| A01 | No keys/network and synthetic fixtures | `demo` or default verification runs | Deterministic fake answer/trace; zero external calls; defaults documented |
| A02 | Local decision URL/key/model separate from generation | A live-compatible request is routed | Each adapter uses its own settings; no generation key leaks to decision service |
| A03 | Pool A/B and router selects C | Route is resolved | Reject C; use only feasible configured fallback or terminate; no C request |
| A04 | Model supports low/high with mandatory reasoning | Candidate generator sees none/medium/default | none/medium excluded; provider default distinct; low/high preserved exactly |
| A05 | Fallback needs tools but lacks support or exceeds admission | Router fails or returns low concentration | No infeasible fallback call; explicit no-feasible-route result |
| A06 | Missing label, malformed JSON, NaN, extra probability, timeout or missing distribution | Decision result is validated | Typed failure/uncertainty; fallback trace; no silent model/parser repair |
| A07 | Valid suggestion B and baseline A | Shadow then active mode run | Shadow executes A and records B; active executes B when admissible; both visible |
| A08 | Traversal/symlink/secret path, invalid arguments or unexposed tool | Tool is proposed | Denied/invalid observation before read/effect; bounded result on valid read |
| A09 | Tool call ID already completed or mismatched arguments | Provider repeats call | Identical ID reuses result; changed arguments error; generation retry cannot repeat effects |
| A10 | Provider returns token/cost/model metadata or omits it | Model completes | Accurate requested/observed fields, units and missing markers; no guessed effective effort |
| A11 | Frozen captured inputs/outcomes or metadata-only trace | Replay is invoked | Offline deterministic policy replay when inputs exist; otherwise explanation; no network/tools |
| A12 | Attempt/deadline allowance exhausted or blocked child task | Another step or Ctrl-C happens | No new operation; cancellation propagates/cleans up; correct terminal reason/exit code |
| A13 | Reservation cannot fit or prior billed outcome is ambiguous | Paid request admission/retry occurs | Deny excess admission; retain uncertain reserve; no default ambiguous retry; disclose possible overshoot |
| A14 | Context pressure and pending tool pair/pinned constraints | System One chooses compaction | Summary via feasible routed generator; pins/pairs/continuation preserved; failed summary retains source |
| A15 | Unknown config key, missing env or invalid reference | CLI validates/shows config | Field-path error and next action; secrets redacted; no paid generation |
| A16 | Trace write failure or truncated final JSONL line | Run proceeds/trace is inspected | Stop new work, mark gap/incomplete; never invent completion or repeat effect |
| A17 | Frozen baseline/candidate runs with failures and router overhead | Evaluation reports results | Paired task metrics, all costs, confidence intervals/limits; no counterfactual regret without alternatives |
| A18 | Provider returns signed/opaque continuation and multiple calls | Results return or route switch/summary is considered | Envelope roundtrip intact; correct roles/order; incompatible switch prohibited |
| A19 | Preview bytecode and selected toolchain | Compile, test, package and launcher run | Same JDK/release and preview flags succeed; no stale preview API examples |
| A20 | Substantial implemented slice | Marked complete | Hypothesis, checks, critical findings/fixes, residual risks and spec updates recorded |
| A21 | Non-TTY, NO_COLOR, narrow terminal or JSON mode | Run success/failure output | Stable stdout result, stderr diagnostics, useful exit codes and no colour-dependent meaning |
| A22 | In-process chat with two user turns and aggregate allowance | A follow-up references an earlier fact | Retains ordered facts/tool units; fresh run IDs, same session; paid admission fits session and run limits |
| A23 | Explicit instruction files, untrusted repository text and oversized router state | Context/template/projection is assembled | Deterministic order/roles/hashes; no auto-privileged AGENTS loading; no silent mandatory truncation |
| A24 | Model/summary/tool text requests expanded effects or an extension bypasses admission | An operation is proposed | Common deterministic pipeline denies before effect; no model-created grants, keys or root expansion |
| A25 | Delegation/parallel-worker config or proposed hidden child request | Validation/driver runs | Unsupported mode rejected; one agent/active turn; no speculative paid fan-out |
| A26 | Test-only compiled read tool/provider and duplicate/unknown/effectful registrations | Composition/run/cleanup occurs | Extension works through common policy; illegal registration denied; resources closed without loop changes |
| A27 | Three exact completed tool batches with same arguments/results but new call IDs | Another cycle would start | `NO_PROGRESS` after third batch; different new observation resets streak; compaction cannot erase it |
| A28 | Four modules and justified ports | Dependency/API/packaged design is reviewed | Core has no adapter DTO imports; small interfaces; documented real extension example and launcher verified |
| A29 | Routes excluded only for context, or low conversation soft allowance | Preflight plans compaction | Feasible source summary can precede candidate rebuild; constraints/pins retained; budget/context exhaustion stops finitely |
| A30 | Agent completes a verified slice or lacks live credentials | Build session continues | Proceeds S01–S12 to offline release; only external live gates blocked; release-wide critique and evidence manifest |
| A31 | Examples/suites/events/config schema and packaged product | Clean-checkout release verification runs | Formats validate; exact setup/run/chat/replay commands work; offline/live gates and costs reported honestly |
| A32 | Failed chat turn, reset, or independent session | New context is assembled/admission checked | Reset preserves counts/liability; incomplete answer omitted; explicit failure retained; no cross-session history leakage |
| A33 | Synthetic protected/unknown values in prompt, instruction, history, tool, candidate, nested JSON or adapter fields | Initial decision, generation, summary or fallback would dispatch | Local gate/final body checks give zero sends; pre-admission blocks allocate nothing and late rechecks release only unused reserves; no provider sanitisation or cross-provider bypass |
| A34 | Approved source changes, generated output repeats a canary, opaque continuation or diagnostic/export contains protected metadata | History/reuse/transport/report occurs | Revalidate provenance/body; unsafe opaque return blocks; no raw values/mappings in output; only matching service auth header can carry its credential |

## Additional invariant suites

Generate catalogs with conflicting/missing capability evidence, pools with unsupported efforts, zero/one/many choices and budgets around boundary values. Assert every executed candidate belongs to the exact feasible set, every amount retains units, and a terminal run cannot start work. Fuzz malformed adapter JSON within resource limits and test duplicate keys. Test option permutation separately from deterministic order assertions.

Context fixtures include: huge current request that cannot be summarised safely, completed and unresolved tool pairs, required opaque continuation, summary dropping a pinned structured fact, and summary budget that consumes the final generation attempt. Correct behavior may be an explicit stop; forcing a success is not a test requirement.

Filesystem tests include symlinked parent paths and bounded reads/searches, not just `../` rejection. HTTP tests include blocked response bodies, response after cancellation, definitive 429, timeout after send, refusal and empty `length` completion. Ledger assertions must count router and summary spend and never double-count reasoning tokens.
