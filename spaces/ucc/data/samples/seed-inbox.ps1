# Seed the ucc space voucher inbox from the pristine samples (the poll dir is consumed by the engine)
# and pre-create every directory the voucher pipeline expects (all dirs.* must exist on disk).
# Sample files exercise the multi-schema dispatch: *main* -> 116 cols, *other* -> 76 cols, default -> 537 cols.
$ErrorActionPreference = 'Stop'
$data = Split-Path -Parent $PSScriptRoot
foreach ($d in 'inbox/voucher/unknown','voucher/database','voucher/backup','voucher/temp',
               'voucher/errors','voucher/quarantine','voucher/markers','voucher/status','voucher/logs') {
  New-Item -ItemType Directory -Force -Path (Join-Path $data $d) | Out-Null
}
Copy-Item -Path (Join-Path $PSScriptRoot 'voucher/*') -Destination (Join-Path $data 'inbox/voucher/unknown') -Force
Write-Host "Seeded $(Join-Path $data 'inbox/voucher/unknown') - restart the server or wait for the next poll cycle."
