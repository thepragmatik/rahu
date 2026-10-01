# Dogfood release gates

Rahu alpha is a usable read-only repository research/review assistant with in-process follow-up conversation. It can propose code in its answer but cannot edit, compile or test a repository on the user's behalf. The separate build agent develops Rahu using its own authorised tools. Permissioned writes and shell execution remain later work.

## Required deliverables

| Gate | Evidence required | Blocking policy |
|---|---|---|
| G01 Reproducible build | Clean checkout `./mvnw verify`; packaged demo; selected JDK/preview flags and pinned dependencies | Required for offline complete |
| G02 Legal routing and real protocol contracts | A02–A07; synthetic contract fixtures with provenance; independent adapters | Required for offline complete; real model calls needed in G09 |
| G03 Tools and deterministic authority | Workspace list/read/search, schema/path/injection and registry tests; A33/A34 safe-view/final transport tests | Required; high-severity authority/privacy defects block release |
| G04 Context/session usability | Follow-up retains facts, `/reset` behaves safely, compaction fits, trusted inputs/continuation remain correct | Required; no hidden persistence claim |
| G05 Loop coordination and limits | No-progress/steps/deadline/session budget tests, cancellation cleanup, one active turn, no delegation | Required |
| G06 Traces/replay | Golden events, gaps/errors, safe diagnostics/export and privacy defaults, offline replay of captured synthetic inputs | Required; trace failure cannot become success |
| G07 Extension and design quality | Real compiled extension example/tests; dependency boundaries; help/error/JSON/plain-text output review | Required; no speculative plugin platform |
| G08 Install/run documentation | New developer can build/demo/configure/inspect/run/chat; setup errors actionable; examples/schema checked | Required for operational readiness |
| G09 Real dogfood smoke | Genuine System One + OpenRouter; configured legal pool; approved non-sensitive inputs/source views; verified privacy gate; bounded repository Q&A, follow-up, tool and summary evidence | Required only for live dogfood verified; mark blocked if prerequisites absent |
| G10 Release-wide critique | Cross-subsystem review and resolved high-severity findings; requirement-to-test evidence manifest | Required; code presence alone is not evidence |

G01–G08/G10 establish **offline complete**. Adding G09 establishes **live dogfood verified**. M3 promotion establishes **routing optimisation validated** separately. A service outage does not undo offline evidence but cannot be disguised as a passed live gate.

## Required task set

Use fixed synthetic fixture inputs for deterministic tests and selected non-sensitive repository content for live checks. The dogfood set must demonstrate:

1. Architecture Q&A using read/search and source paths, with a genuine route decision and shadow/active distinction visible.
2. A follow-up question in the same process that relies on a fact from the first turn, plus session aggregate accounting.
3. A long synthetic completed transcript that triggers System One compaction and routed summarisation without losing pins/tool units.
4. A denied path/effect or injected tool instruction that cannot widen authority.
5. A cancellation, no-progress or limit failure reported clearly with complete metadata.
6. Offline inspection and policy replay using captured synthetic inputs; replay makes no external calls.

A single paid smoke need not force all rare failures/compaction paths against a live provider. Tests 3–6 can be deterministic offline; G09 additionally requires at least one real System One compaction choice and real routed summary in a separately admitted small synthetic run. Record each case's execution mode so offline evidence is never presented as live service evidence.

Do not force the decision model to pick a particular route to call the test successful. Real quality is assessed by rubric. A live run where every decision times out and uses fallback does not establish System One compatibility: require at least one validated classification, relevance and route, plus real compaction-policy/summary-route evidence across the bounded runs. Failure modes may be simulated offline.

## Report format

Create `docs/reviews/dogfood-release.md` during implementation with date/commit, G01–G10 passed/blocked/failed, acceptance IDs mapped to actual tests/results, exact commands, environment/service/model/adapter versions, costs reported/estimated/unknown, and warnings. Link the seven-area map and slice reviews. Attach sanitised output or fixture references, not private transcripts.

High-severity issues in privacy, capability, authority, continuation, duplicate effects, cancellation, ledger reset or trace truthfulness block declaring completion. Lower-severity issues have an owner, impact and next check; do not hide them in a generic 'future improvements' paragraph.

## Scope control

No dashboard, streaming, durable recovery, vector memory, effectful tools, native ML, training or delegation is necessary for alpha. Their absence must be visible in help and release notes. The seven-area coverage requirement does not authorise implementing later maxima.
