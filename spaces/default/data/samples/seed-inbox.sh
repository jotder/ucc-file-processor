#!/usr/bin/env bash
# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the subscriber + events pipelines expect (all dirs.* must exist on disk).
set -euo pipefail
cd "$(dirname "$0")"
for d in inbox/subscriber subscriber/database subscriber/backup subscriber/temp subscriber/errors \
         subscriber/quarantine subscriber/markers subscriber/status subscriber/logs \
         inbox/events events_etl/database events_etl/backup events_etl/temp events_etl/errors \
         events_etl/quarantine events_etl/markers events_etl/status events_etl/logs \
         reports/events_daily ref; do
  mkdir -p "../$d"
done
cp subscriber/* ../inbox/subscriber/
cp events/* ../inbox/events/
cp ref/* ../ref/
echo "Seeded subscriber + events inboxes + ref/ - restart the server or wait for the next poll cycle."
