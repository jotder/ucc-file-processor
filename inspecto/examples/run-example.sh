#!/usr/bin/env bash
# Run one bundled Inspecto example end-to-end (self-contained, no external services).
#
# Output lands under the example's own out/ dir; nothing else on disk is touched.
# Works from the source tree (inspecto/examples/) and the release bundle (examples/).
# JAR resolution: $INSPECTO_JAR -> ../file-processor.jar (bundle) -> ../target/file-processor-*.jar (tree).
#
# Usage:  bash run-example.sh 01-ingest/hello-csv [--clean]
set -euo pipefail

EXAMPLES_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EX="${1:-}"; CLEAN="${2:-}"
[ -n "$EX" ] || { echo "Usage: bash run-example.sh <category/name> [--clean]" >&2; exit 2; }

resolve_jar() {
  if [ -n "${INSPECTO_JAR:-}" ] && [ -f "$INSPECTO_JAR" ]; then echo "$INSPECTO_JAR"; return; fi
  if [ -f "$EXAMPLES_ROOT/../file-processor.jar" ]; then echo "$EXAMPLES_ROOT/../file-processor.jar"; return; fi
  local j; j="$(ls "$EXAMPLES_ROOT"/../target/file-processor-*.jar 2>/dev/null | grep -vE 'sources|javadoc' | head -1 || true)"
  if [ -n "$j" ]; then echo "$j"; return; fi
  echo "Engine JAR not found. Set \$INSPECTO_JAR or build it (mvn -o clean package)." >&2; exit 1
}

JAR="$(resolve_jar)"
DIR="$EXAMPLES_ROOT/$EX"
[ -f "$DIR/pipeline.toon" ] || { echo "No pipeline.toon under '$DIR'." >&2; exit 1; }

cd "$DIR"
[ "$CLEAN" = "--clean" ] && rm -rf out
for d in inbox database backup temp errors quarantine markers status logs; do mkdir -p "out/$d"; done
# Seed a fresh working inbox from the pristine, committed samples/ (the engine consumes the poll dir).
[ -d samples ] && cp -r samples/. out/inbox/ 2>/dev/null || true
echo "Running '$EX'"
echo "  jar:    $JAR"
echo "  config: $DIR/pipeline.toon"
echo
java --enable-native-access=ALL-UNNAMED -jar "$JAR" pipeline.toon
code=$?
echo
echo "Exit code: $code"
[ -d out/database ] && { echo "Output (out/database):"; find out/database -type f 2>/dev/null | head -20 | sed 's/^/  /'; }
exit $code
