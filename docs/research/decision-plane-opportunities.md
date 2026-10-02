# Decision-plane opportunities

Recorded 2026-10-02 during Track D (decision ops). Purpose: hold decisions and
evidence that shape the decision plane but are not yet wired code.

## Candidate v2 classification vocabulary (logged 2026-10-02)

`TaskClass` v1 (per docs/specs/systemone.md line 7) carries six labels:
answer, coding, analysis, classification, summarisation, unknown. User-raised
candidates for a future version: `design`, `architecture` — prompts that ask
for structural/behavioural design of software rather than code itself.

Decision (agreed with the user, 2026-10-02): keep the six spec labels for v1.
Reasons, all grounded:

- The label set is wire vocabulary frozen by the spec; changing it is a
  deliberate spec amendment, not a build-slice side effect.
- Independent Jev evaluation: intent classification degrades as label count
  grows (SNIPS 7 classes: 97.9%; Banking77 77 classes: 80.3%). Every extra
  label splits probability mass for no downstream behaviour change today.
- No current consumer distinguishes design/architecture from coding/analysis;
  the conservative default (annotate first, narrow only with evidence) applies.

Trigger for revisiting: a real consumer that would route design/architecture
prompts differently (e.g. candidate-pool narrowing in Phase F) with measured
misclassification evidence on Rahu's own tasks. `TaskClass.fromLabel` maps
unrecognised labels to UNKNOWN, so adding labels later is backward-compatible.

## How to re-verify

The benchmark figures cited here come from a third-party black-box evaluation
of jev-1.13.0 (dev.to, Sep 2026) and must be re-measured on Rahu's own tasks
before any of them is claimed as a Rahu result.
