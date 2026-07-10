#!/usr/bin/env bash
# Seed the demo space inbox from the pristine samples (the poll dir is consumed by the engine)
# and pre-create every directory the orders pipeline expects (all dirs.* must exist on disk).
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/orders orders/database orders/backup orders/temp orders/errors \
         orders/quarantine orders/markers orders/status orders/logs reports/orders_daily; do
  mkdir -p "../$d"
done
cp orders/* ../inbox/orders/
echo "Seeded $(cd ../inbox/orders && pwd) - restart the server or wait for the next poll cycle."
