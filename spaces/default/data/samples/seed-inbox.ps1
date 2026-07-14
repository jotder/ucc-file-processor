# Seed the default space inboxes from the pristine samples (the poll dirs are consumed by the engine)
# and pre-create every directory the subscriber + events pipelines expect (all dirs.* must exist on disk).
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/subscriber','subscriber/database','subscriber/backup','subscriber/temp',
               'subscriber/errors','subscriber/quarantine','subscriber/markers','subscriber/status',
               'subscriber/logs',
               'inbox/events','events_etl/database','events_etl/backup','events_etl/temp','events_etl/errors',
               'events_etl/quarantine','events_etl/markers','events_etl/status','events_etl/logs',
               'reports/events_daily','ref') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
Copy-Item -Path (Join-Path $PSScriptRoot 'subscriber/*') -Destination (Join-Path $data 'inbox/subscriber') -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'events/*') -Destination (Join-Path $data 'inbox/events') -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force
Write-Host "Seeded subscriber + events inboxes + ref/ - restart the server or wait for the next poll cycle."
