# Synthetic System One contract fixtures

These bodies are independently authored synthetic examples matching the checked envelope assertions in [Kev tests/test_api.py](https://github.com/jaredpalmer/kev/blob/main/tests/test_api.py), source blob SHA `86841aad9e740b73697196de49d89b9426fafc15`, read on 1 October 2026. Values, timing/usage and predictions are **not measured**. They are not model-quality evidence and contain no upstream test implementation.

| File | Intended test |
|---|---|
| [request.json](request.json) | Typed Choice/Noul/compaction request mapping |
| [response.json](response.json) | Valid envelope, separate chosen probability/raw confidence |
| [invalid-response.json](invalid-response.json) | Unknown choice/probability label must be rejected |

The reported `model` is synthetic and could echo an alias. Do not infer a served checkpoint from it. Provider confidence uses concentration semantics: with two options and chosen probability 0.8 the raw confidence shown is 0.6. All arithmetic is a fixture, not calibration. Usage numbers are arbitrary nonnegative integers.

Use these in local HTTP contract tests; add assertions for absent fields, consistency, truncated inputs, malformed JSON, headers and timeout/cleanup. Verify the deployed profile with a real separately budgeted call before G09. Never require the model to reproduce these exact probabilities.

This combined request exercises field shapes only. Production questions follow the dependency ordering in the decision/runtime specs; a route cannot be batched with an answer that determines its candidate set.
