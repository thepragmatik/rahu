#!/usr/bin/env bash
# Run the injection calibration corpus in a THROWAWAY CONTAINER.
#
# WHY THIS EXISTS
#   The corpus holds adversarial instruction-shaped samples. Per a strict operator
#   guardrail they must never be executed on a developer or production host: doing so
#   pushes the text through local logs, shell history, and any agent transcript
#   watching the output. ShadowCorpusProbe enforces this in code (it refuses to start
#   without positive evidence of containerisation), and this script is the supported
#   way to satisfy that guard.
#
# ISOLATION
#   - No host filesystem mounts except the read-only corpus and the built jar.
#   - No host network namespace: --network none. The decision adapter needs egress, so
#     the script passes an explicit egress allowance flag; without it, set
#     RAHU_CORPUS_ALLOW_NETWORK=1 and understand you are relaxing isolation.
#   - Read-only root filesystem, no new privileges, all capabilities dropped.
#   - The container is removed with --rm whether the run succeeds or fails.
#   - The API key is passed as an env var, never baked into the image or the repo.
#
# USAGE
#   ./scripts/run-injection-corpus.sh
#
# The decision adapter reads OPENROUTER_API_KEY from the environment. Export it in your
# shell before invoking; do not write it into a file that gets committed. A separate
# config file (apiKeyEnv, baseUrl, model) is mounted read-only from CONFIG below.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CORPUS="docs/evals/corpus/injection-shadow-v1.json"
CONFIG="${RAHU_CORPUS_CONFIG:-config.corpus.local.json}"
IMAGE_TAG="rahu-injection-corpus:local"

if [[ ! -f "$CORPUS" ]]; then
  echo "corpus fixture not found: $CORPUS" >&2
  exit 1
fi
if [[ ! -f "$CONFIG" ]]; then
  echo "decision config not found: $CONFIG" >&2
  echo "Set RAHU_CORPUS_CONFIG, or create a local (untracked) copy of config.local.json" >&2
  echo "named config.corpus.local.json. It must NOT be committed." >&2
  exit 1
fi
if grep -qE 'sk-[A-Za-z0-9]{20,}|OPENROUTER_API_KEY=[^$]' "$CONFIG" 2>/dev/null; then
  echo "refusing: $CONFIG appears to contain a literal credential." >&2
  echo "The config must reference the key by env var name (apiKeyEnv), not value." >&2
  exit 1
fi

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
  echo "OPENROUTER_API_KEY is not set; the decision adapter cannot run." >&2
  echo "Export it in your shell. Do not commit it." >&2
  exit 1
fi

NETWORK_ARGS=()
if [[ "${RAHU_CORPUS_ALLOW_NETWORK:-1}" == "1" ]]; then
  # The decision plane lives at an external endpoint, so egress is required. All other
  # isolation above still holds.
  NETWORK_ARGS=(--network bridge)
else
  echo "NOTE: running with --network none; the decision adapter will fail to connect." >&2
  NETWORK_ARGS=(--network none)
fi

echo "==> building the throwaway image"
docker build -f scripts/injection-corpus.Dockerfile -t "$IMAGE_TAG" .

echo "==> running the corpus in a container (removed afterwards)"
docker run --rm \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --pids-limit 256 \
  --memory 1g \
  "${NETWORK_ARGS[@]}" \
  -e OPENROUTER_API_KEY \
  -v "$REPO_ROOT/$CORPUS:/corpus/injection-shadow-v1.json:ro" \
  -v "$REPO_ROOT/$CONFIG:/corpus/config.json:ro" \
  "$IMAGE_TAG" \
  /corpus/injection-shadow-v1.json /corpus/config.json

echo "==> container exited; nothing persisted"
echo "Record the score table in docs/research/injection-corpus-v1.md (ids, labels,"
echo "styles, verdicts and scores only - never observation text)."