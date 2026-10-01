# Configuration examples

These files describe proposed JSON v1 contracts. They cannot be run until the implementation session builds Rahu and its fixtures. See [configuration spec](../docs/specs/configuration.md) for validation and defaults.

| File | Purpose |
|---|---|
| [offline.json](offline.json) | Synthetic adapters, no keys/network, safe demonstration |
| [live-local-systemone.json](live-local-systemone.json) | Independent local decision endpoint and explicit OpenRouter generation pool |

Offline aliases `demo-fast` and `demo-quality` are fictional fixture identities, not real provider models. Implement built-in fake profiles with the stated allowed efforts, text/tools support, ample context and synthetic cost. The demo tests plumbing and must never be advertised as routing-quality evidence.

For live configuration supply `RAHU_FAST_MODEL`, `RAHU_QUALITY_MODEL`, `RAHU_DECISION_MODEL`, and `OPENROUTER_API_KEY`. Select exact real generation IDs from a verified catalog, adjust efforts and candidate references to those supported, and confirm the local decision server/profile. Environment interpolation resolves IDs only; it does not verify capability. Missing/unsupported values must produce validation errors. Local serving shown here is loopback and unauthenticated; add `apiKeyEnv` if the server requires a key.

Live defaults are shadow and metadata capture. `quality@medium` is a configured label/reference, not a guarantee of superior quality. Establish baseline/fallback strengths with evidence. Summary routing uses the same pool initially. The live example may fail validation until the selected models support its efforts/tools/context; do not silently repair it by broadening the pool.

Resolved credentials must never be saved or traced. `.rahu/` and local config overrides are ignored by git.
