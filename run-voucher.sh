#!/usr/bin/env bash
# Runs the voucher ETL pipeline.
# Working directory must be the sandbox root (the directory containing this script).
set -euo pipefail
cd "$(dirname "$0")"
jar=$(ls -1 inspecto/target/file-processor-*.jar 2>/dev/null | head -n1)
if [[ -z "${jar:-}" ]]; then
  echo "ERROR: no JAR found matching inspecto/target/file-processor-*.jar" >&2
  echo "       Run 'mvn clean package' first." >&2
  exit 1
fi
java --enable-native-access=ALL-UNNAMED \
     -jar "$jar" \
     inspecto/config/voucher/voucher_unknown_pipeline.toon
