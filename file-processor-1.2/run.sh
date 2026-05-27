#!/usr/bin/env bash
# Usage: ./run.sh <adapter>
# Looks up the pipeline file as config/<adapter>/*_pipeline.toon (first match wins),
# so it transparently handles both "<adapter>_pipeline.toon" and variants like
# "<adapter>_unknown_pipeline.toon".
set -euo pipefail
cd "$(dirname "$0")"
ADAPTER="${1:?Usage: run.sh <adapter>   (e.g. adjustment, voucher)}"
PIPELINE=$(ls "config/${ADAPTER}"/*_pipeline.toon 2>/dev/null | head -1)
if [ -z "$PIPELINE" ]; then
    echo "ERROR: no pipeline file found at config/${ADAPTER}/*_pipeline.toon" >&2
    exit 1
fi
echo "[run.sh] Using pipeline: $PIPELINE"
exec java --enable-native-access=ALL-UNNAMED -jar file-processor.jar "$PIPELINE"