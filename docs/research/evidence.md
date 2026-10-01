# Primary research evidence

Reviewed on 1 October 2026. This is a bounded evidence map, not a claim to have reproduced upstream benchmarks. URLs may move; implementers must pin relevant code/doc revisions when creating contract fixtures. Product design choices are Rahu hypotheses unless identified as protocol facts.

## Harness design

[Anthropic Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) advocates simple composable patterns and increasing complexity when it provides value. Rahu applies that guidance through a small owned loop and deferred orchestration. This does not prove that custom Java code outperforms frameworks. Compare lifecycle transparency and maintenance cost as the implementation grows.

## System One models

[Laya upstream](https://github.com/LeonaDavinci/laya-system-one) describes typed non-autoregressive decisions and an HTTP `/v1/systemone` service. Its different checkpoints and automatic internal routing mean the requested alias alone may not identify the served model. Author latency/quality results are not Rahu measurements. Verify deployed checkpoint, input limits and response envelope before claiming compatibility.

[Kev upstream](https://github.com/jaredpalmer/kev) documents Choice, Score and Boolean-style `noul` questions, its Jev-compatible endpoint and model-family architecture. Its documented confidence formulas measure distribution concentration, not accuracy. Read checkpoint-specific limits and evaluate local task quality, option order and end-to-end service overhead. Repository main resolved to tree `1d77363be5769ad8c64486a51f731f940e92a59b` during inspection; this is an evidence locator, not a selected deployment version.

Rahu does not assume hosted Jev uses the same URL/authentication as OpenRouter chat generation. Confirm the deployed decision endpoint/profile independently. No hosted adapter credential, commercial SLA or benchmark comparison has been tested in this planning pass.

## Encoders and contrastive routing

[ModernBERT paper](https://arxiv.org/abs/2412.13663) presents an encoder with efficient long-context processing and downstream fine-tuning results. An encoder plus trained readout can be appropriate for decisions, but an untuned encoder is not a competent universal router. Its published benchmarks do not establish Rahu model/effort prediction quality or Java inference latency.

[CLIP](https://openai.com/index/clip/) learns aligned image/text representations through contrastive supervision. The transferable idea here is matching requests and candidate descriptions/representations. Applying it to model/effort actions is an architectural hypothesis, not a direct use of the CLIP vision model.

[RouterDC](https://proceedings.neurips.cc/paper_files/paper/2024/hash/7a641b8ec86162fc875fb9f6456a542f-Abstract-Conference.html) studies query/model representations and dual contrastive routing. It provides closer routing precedent than the CLIP analogy. Rahu must separately test effort representations, unseen candidates, tool workflows and its quality/cost/latency constraints.

[RouteLLM](https://arxiv.org/abs/2406.18665) studies routing with preference data and reports favorable quality/cost tradeoffs in its evaluation. That supports testing routing, not assuming its savings transfer to a different pool/provider/task distribution. A fixed-route baseline and held-out comparisons remain mandatory.

## Provider contracts

[OpenRouter model catalog](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties) documents model inventory/properties. Rahu snapshots evidence and pricing rather than hardcoding illustrative model IDs from the earlier conversation. Missing fields and endpoint-level differences require conservative validation.

[OpenRouter reasoning controls](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens) documents per-model effort metadata, differing controls and preservation of continuation details. Rahu keeps default separate from none, uses supported efforts when evidenced, and tests tool continuation. Catalog support is not direct observation of effective effort or quality.

[OpenRouter provider selection](https://openrouter.ai/docs/guides/routing/provider-selection) documents `require_parameters` to restrict endpoints that would otherwise ignore unsupported parameters. Rahu enables it for controlled requests and records constraints. Acceptance remains subject to actual provider behavior; contract/live tests must verify important paths.

## Java

[Java 27 announcement](https://inside.java/2026/09/15/jdk-27-available/) confirms GA on 15 September 2026 and identifies structured concurrency as a preview. [Selected-JDK API](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html) defines the current scope/cancellation API. Preview examples from older JDKs may be incompatible. Rahu now permits preview use because the owner explicitly requested it.

## Questions to answer before expensive work

1. Does the deployed decision model understand quality tradeoffs from candidate descriptions, or does it mainly prefer labels/position?
2. How much end-to-end latency does it add on real hardware, cold and warm?
3. Does reasoning-effort selection help after accounting for output allowance and provider variation?
4. Can a small configured pool dominate a broad one in calibration/stability?
5. Which dogfood failures come from routing versus context/tool/loop defects?
6. Is native Java inference worth its tokenizer/export/package cost?
7. Are counterfactual labels sufficient to learn a router without selection bias?

Answer through small falsifiable experiments. Do not train, choose ONNX/DJL, or add orchestration before those decisions have evidence. Recent papers mentioned in the earlier conversation are not used as implementation prerequisites without a separate primary-source review.
