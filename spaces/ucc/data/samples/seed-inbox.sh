#!/usr/bin/env bash
# Seed the ucc space voucher inbox from the pristine samples (the poll dir is consumed by the engine)
# and pre-create every directory the voucher pipeline expects (all dirs.* must exist on disk).
# Sample files exercise the multi-schema dispatch: *main* -> 116 cols, *other* -> 76 cols, default -> 537 cols.
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/voucher/unknown voucher/database voucher/backup voucher/temp voucher/errors \
         voucher/quarantine voucher/markers voucher/status voucher/logs; do
  mkdir -p "../$d"
done
cp voucher/* ../inbox/voucher/unknown/
echo "Seeded $(cd ../inbox/voucher/unknown && pwd) - restart the server or wait for the next poll cycle."
