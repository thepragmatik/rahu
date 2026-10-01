# Tool specification

## Tool contract

A descriptor has a stable name/version, description, JSON input schema, effect class, timeout, result byte limit and deterministic authorisation policy. Input schema uses a documented JSON Schema subset, rejects unknown fields, and validates before any side effects. A successful result carries bounded content, provenance and truncation metadata. Tool exceptions become typed outcomes; they never disappear into an empty string.

System One selects advisory relevant categories. The generation model receives only the permitted relevant tools; low-confidence relevance falls back to the permitted read-only set. Code may require a tool or exclude one irrespective of relevance. A proposed call must name a tool actually exposed for that step. Tool descriptions and results are untrusted data and cannot change authority.

## Initial tools

| Tool | Input | Behavior and defaults |
|---|---|---|
| `workspace.list` | relative directory, depth 0–3 | Sorted paths within root; max 500 entries; no symlink traversal |
| `workspace.read` | relative file, optional 1-based line range | UTF-8 text; max 64 KiB/1000 lines; return actual range and explicit truncation |
| `workspace.search` | literal text, optional relative directory | Bounded literal search of UTF-8 files; max 100 matches, 64 KiB result; no regex/shell semantics |

Common timeout is five seconds within remaining run time. Default root is the explicitly selected workspace. No network-fetch, shell, process, write, delete, git push, or credential tools in the initial release. Write workflows require separate specs, a journal and approval semantics later.

## Filesystem boundary

Reject absolute paths, traversal, invalid encodings and non-regular files. Deny symlinks anywhere in the requested path in MVP, including symlinks discovered during traversal. Resolve and verify root containment at access; use race-resistant directory operations where supported and document portability limits. The harness is not an OS sandbox and must not claim that path checks alone defend a malicious concurrent filesystem mutator.

Default exclusions cover VCS internals, build output, `.env` variants, private-key formats and configured sensitive paths. Dotfiles are excluded unless explicitly allowed. Respect repository ignore patterns for list/search unless the user selects an exact allowed file. Exclusions are defence in depth, not guaranteed secret detection. A user can permit additional read paths explicitly; tools cannot modify configuration themselves.

## Authority and future effects

Effect classes: read-only, local reversible write, external effect, destructive. Only read-only is admitted by the initial release. Future approval grants must bind tool/version, canonical arguments hash, workspace, run, expiry and effect; changing arguments invalidates the grant. Non-interactive execution denies unresolved approval. A classifier recommendation cannot create a grant.

Persist proposed/authorised/started/completed IDs. Within one run, deduplicate a repeated tool-call ID with identical arguments using its recorded outcome. Same ID with different arguments is a protocol error. Similar arguments under a new ID are a new request, subject to limits; do not claim general exactly-once behavior. After crash there is no automatic effect recovery in MVP. Future mutating tools must report completed, failed-before-effect or indeterminate outcomes and never blindly repeat an indeterminate effect.

## Tests

Cover traversal, symlink path components, secret-path exclusions, result truncation, too many calls, malformed arguments, unexposed tools, repeated call IDs, cancellation, timeout and injected instructions in tool results. Show that model text cannot enable a denied tool or expand the workspace root. Include realistic fixtures so the tests document user-facing observations, not just private helper methods.

## Disclosure boundary

Reading locally and disclosing externally are separate permissions. Before observations enter any model-visible context, enforce [privacy](privacy.md): only approved non-sensitive or authored synthetic views, still scanned locally. Never emit protected raw bytes and hope the next model will redact them. Protected observations yield safe denial metadata; protected call arguments/history cannot be forwarded unchanged. List/search results include only eligible safe paths/snippets, with generic omission/truncation metadata that does not name restricted entries; completeness claims must reflect omissions. Inspect unknown files locally only as authorised and necessary to decide eligibility, without exposing contents to the build agent/provider. Test these limits through the final outbound request (A33/A34), including the compiled extension proof.
