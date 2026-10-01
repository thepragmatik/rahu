# Architectural decision records

ADRs explain decisions, tradeoffs and the evidence that could overturn them. Accepted means accepted for this project design, not implemented or scientifically proved.

| Record | Decision | Status |
|---|---|---|
| [0001](0001-own-loop.md) | Own a small loop behind provider ports | Accepted |
| [0002](0002-decision-plane.md) | Bounded System One judgment with deterministic authority | Accepted |
| [0003](0003-execution-candidates.md) | Route joint model/effort/provider candidates | Accepted |
| [0004](0004-java-preview.md) | Java 27 with deliberate preview use | Accepted |
| [0005](0005-traces-and-replay.md) | Append-only observations and offline policy replay | Accepted |
| [0006](0006-configuration-and-shadow.md) | JSON configuration, explicit live pools, default shadow | Accepted |

New ADRs include date, context, alternatives, decision, consequences, validation and revisit triggers. To change one, add a superseding record, link it from the old record, and update affected requirements/specs/tests in the same change. Do not rewrite history to conceal a failed hypothesis.
