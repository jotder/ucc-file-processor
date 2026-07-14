# Seed the demo space inbox from the pristine samples (the poll dir is consumed by the engine)
# and pre-create every directory the orders pipeline expects (all dirs.* must exist on disk).
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/orders','orders/database','orders/backup','orders/temp','orders/errors',
               'orders/quarantine','orders/markers','orders/status','orders/logs',
               'reports/orders_daily','ref') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
Copy-Item -Path (Join-Path $PSScriptRoot 'orders/*') -Destination (Join-Path $data 'inbox/orders') -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'ref/*') -Destination (Join-Path $data 'ref') -Force
Write-Host "Seeded $(Join-Path $data 'inbox/orders') + ref/ - restart the server or wait for the next poll cycle."
