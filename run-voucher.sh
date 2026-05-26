#!/usr/bin/env bash
# Runs the voucher ETL pipeline.
# Working directory must be the sandbox root (the directory containing this script).
set -euo pipefail
cd "$(dirname "$0")"
java --enable-native-access=ALL-UNNAMED \
     -jar file-processor/target/file-processor-1.0.jar \
     file-processor/config/voucher/voucher_pipeline.toon
