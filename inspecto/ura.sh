#!/usr/bin/env bash
# URA File Management Suite — development CLI runner
#
# Usage (from the inspecto/ directory after 'mvn clean package'):
#   ./ura.sh [--dry-run] <command> <pipeline.toon> [args...]
#
# Examples:
#   ./ura.sh help
#   ./ura.sh search           spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh copy             spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh --dry-run backup spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh prepare-inbox    spaces/ucc/config/voucher/voucher_pipeline.toon
#   ./ura.sh create-schema    adjustment  samples/adj_sample.csv  config/adjustment/adj_gen.toon
#
# This script targets the fat JAR in target/ — build once with 'mvn clean package'.
# For deployed servers use ura.sh bundled alongside file-processor.jar.
set -euo pipefail
cd "$(dirname "$0")"

JAR=$(ls target/file-processor-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    echo "ERROR: no JAR found matching target/file-processor-*.jar" >&2
    echo "       Run 'mvn clean package' first." >&2
    exit 1
fi

exec java --enable-native-access=ALL-UNNAMED \
          -cp "$JAR" \
          com.gamma.inspector.MainApp "$@"
