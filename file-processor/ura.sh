#!/usr/bin/env bash
# URA File Management Suite — development CLI runner
#
# Usage (from the file-processor/ directory after 'mvn clean package'):
#   ./ura.sh [--dry-run] <command> <pipeline.toon> [args...]
#
# Examples:
#   ./ura.sh help
#   ./ura.sh search           config/adjustment/adjustment_pipeline.toon
#   ./ura.sh copy             config/voucher/voucher_pipeline.toon
#   ./ura.sh --dry-run backup config/adjustment/adjustment_pipeline.toon
#   ./ura.sh prepare-inbox    config/adjustment/adjustment_pipeline.toon
#   ./ura.sh create-schema    adjustment  samples/adj_sample.csv  config/adjustment/adj_gen.toon
#
# This script targets the fat JAR in target/ — build once with 'mvn clean package'.
# For deployed servers use ura.sh bundled alongside file-processor.jar.
set -euo pipefail
cd "$(dirname "$0")"

JAR="target/file-processor-1.0.jar"
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR not found at $JAR" >&2
    echo "       Run 'mvn clean package' first." >&2
    exit 1
fi

exec java --enable-native-access=ALL-UNNAMED \
          -cp "$JAR" \
          com.gamma.util.MainApp "$@"
