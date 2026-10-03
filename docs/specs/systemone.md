# System One adapter specification

## Port and operation types

`DecisionEngine` accepts a typed request and returns a typed success or failure. Supported questions are Choice (finite labels), Boolean judgment, and ordered Score. They map to wire types `choice`, `noul`, and `score` only inside the HTTP adapter. Decision models do not write summaries, answer prose, or synthesize arbitrary tool arguments.

Operations are `TASK_CLASSIFICATION`, `TOOL_RELEVANCE`, `EXECUTION_ROUTE`, `COMPACTION_POLICY`, and `INJECTION_RISK`. Classification labels v1: answer, coding, analysis, classification, summarisation, unknown. Unknown never lowers trusted capability requirements. Tool relevance selects each bounded tool category with a Boolean question; MVP has at most eight tools. Compaction chooses defer, concise, detailed; code may require compaction or terminate when context will otherwise overflow. Injection risk is one Boolean question per untrusted tool observation, asked with the observation as the state request before that observation becomes model-visible; the answer is advisory and code owns the withholding decision, so an unjudgeable answer withholds nothing.

Classification and relevance may be batched when independent. Route selection depends on validated inputs and filtered candidates, so do not batch it with questions whose answers define those candidates. Never assume a server's question isolation implies option-order invariance.

## HTTP compatibility

Default base URL for local serving is `http://127.0.0.1:8000`; append `/v1/systemone` exactly once. Endpoint/model/key/timeout are independently configured. Permit unencrypted HTTP only for loopback by default; remote endpoints require HTTPS. Redirects off, TLS validation on, no logging authorization headers. An authenticated endpoint must never inherit generation credentials.

Example **request shape**, based on the upstream API; production fixtures must be pinned to a specific upstream revision:

```json
{
  "model": "configured-decision-model",
  "state": {"operation": "answer", "request": "Explain virtual threads"},
  "questions": {
    "route": {
      "type": "choice",
      "instructions": "Choose one permitted execution candidate using the supplied constraints.",
      "criteria": {
        "fast@low": "Text answer; tool support; low effort; estimated cost supplied separately",
        "quality@medium": "Analysis candidate; tool support; medium effort"
      }
    }
  }
}
```

Support explicit `jev-compatible-v1` and, where evidence demands, server-specific profiles. Request compatibility is insufficient evidence of response parity. Before coding response records, pin upstream README/tests/OpenAPI and store sanitised success/error fixtures with provenance. The profile must map the actual answer envelope, choice, probability map, confidence, model identity and usage. Do not pretend an invented canonical response is the vendor protocol.

Core normalised result: question ID, result kind/value, optional label probabilities, optional raw provider confidence plus semantics identifier, chosen probability, observed model, usage, server-reported compute time, measured HTTP duration, and protocol/profile revision. Missing probabilities are explicit and invoke routing fallback; Boolean/Score can be advisory without a complete choice distribution.

The verified envelope/profile and independently authored synthetic shape examples are specified in [artifacts.md](artifacts.md) and [contract fixtures](../../examples/contracts/systemone/README.md). A response `model` may echo the requested alias; store it as reported identity with evidence, not proof of the served checkpoint. Exact checkpoint remains unavailable unless independently supplied/verifiable server metadata establishes it.

Classification failure gives `unknown` and cannot lower trusted requirements. Tool relevance uses `noul` probability: include an allowed tool at probability at least 0.5; a failed/missing judgment exposes the permitted read-only set with a degraded marker. No probability grants permission. Compaction-policy failure defaults to concise if compaction is feasible/required; otherwise defer or stop by deterministic fit. Summary route follows ordinary confidence/fallback rules. Every fallback cause is traced.

## Validation and failure handling

Require all requested answers, correct types and finite numerical values. Choice membership and distribution checks follow routing. Reject unexpected labels or answer kinds, duplicate JSON keys and malformed envelopes. Unknown additive vendor fields may be retained in bounded raw metadata without affecting authority. Do not substitute an arbitrary parser repair LLM.

Defaults: connect timeout 3 seconds, total decision timeout 5 seconds, maximum response 1 MiB, bounded state 16 KiB UTF-8 plus trusted question overhead. Oversized state triggers an explicit bounded feature projection or typed input-too-large error, never silent byte slicing. Projection records omitted segments and is versioned; it cannot replace full context for generation.

No decision retry by default; fallback is usually cheaper. An explicit retry limit of one may handle a definitive pre-execution 429/503 within remaining deadline, respecting bounded Retry-After. Cancellation must interrupt HTTP/body reads. Uncertain transport outcomes remain traceable even if the decision service is nominally side-effect-free because billing may have occurred. Transport faults are typed by whether the request could have been delivered: `UNREACHABLE` when it provably was not (connection refused, host unresolvable, connect timeout) and `AMBIGUOUS` when it may have been (read timeout, or any fault this build does not recognise). A caller retrying on `AMBIGUOUS` may duplicate a billed call; a caller retrying on `UNREACHABLE` cannot. The unrecognised case defaults to `AMBIGUOUS` deliberately: an unproven delivery is not a proven non-delivery.

Decision admission uses explicit service cost policy/pricing, independent of the generation catalog. Local-unbilled mode requires an explicitly operated loopback service; hosted tariffs must be known before requests. Measure local compute separately in evaluations. If choice/probability response metadata cannot provide an exact served checkpoint, trace it unavailable rather than treating an echoed model alias as identity proof.

## Real service requirement

Live smoke testing must include one genuine local or hosted decision model. Fakes validate plumbing only. For Laya/Kev, check the deployed checkpoint's supported operations, context limits, device and model routing behavior. Keep model identity returned by the server; a request alias is not proof of which checkpoint served it. Quality and millisecond claims are hypotheses until measured on Rahu's tasks and hardware.

Every projection/candidate/instruction and final serialised decision request must pass [privacy](privacy.md) before admission/dispatch, including local loopback services. A bounded projection alone is not sanitisation. Privacy blocks are terminal/denied policy outcomes, not low-confidence results that trigger a provider fallback.
