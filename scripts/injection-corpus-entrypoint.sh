#!/usr/bin/env bash
# Container entrypoint for the injection calibration corpus.
#
# Runs as PID 1 inside the throwaway image built by injection-corpus.Dockerfile. Scores
# the corpus in SHADOW mode via ShadowCorpusProbe and prints only ids, labels, styles,
# verdicts and scores - never observation text.
#
# Args: <corpus-path> <config-path>
#
# The probe independently refuses to run without positive evidence of containerisation,
# so this wrapper cannot be used to bypass that guard on a host.

set -euo pipefail

CORPUS="${1:-/corpus/injection-shadow-v1.json}"
CONFIG="${2:-/corpus/config.json}"

if [[ ! -r "$CORPUS" ]]; then
  echo "corpus not readable in container: $CORPUS" >&2
  exit 1
fi
if [[ ! -r "$CONFIG" ]]; then
  echo "config not readable in container: $CONFIG" >&2
  exit 1
fi

# Build the classpath from the module target directories copied into the image plus the
# resolved Maven dependencies. exec:java needs the plugin available in the runtime image.
CP="/corpus/classes:/corpus/core-classes:/corpus/systemone-classes:/corpus/openrouter-classes"
DEPS="$(mvn -q -B -o dependency:build-classpath -Dmdep.outputFile=/dev/stdout \
  -DincludeScope=runtime 2>/dev/null || true)"
if [[ -n "$DEPS" ]]; then
  CP="$CP:$DEPS"
fi

exec java -cp "$CP" rahu.cli.live.ShadowCorpusProbe "$CORPUS" "$CONFIG"