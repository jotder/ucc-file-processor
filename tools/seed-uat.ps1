# seed-uat.ps1 — build a UAT space with realistic data volume, entirely through the platform.
#
# Two phases (docs/ops/uat-seeding.md is the runbook):
#   -Phase files  (default) OFFLINE: clone spaces/demo/config into a fresh gitignored spaces/uat
#                 (every spaces/demo path inside the configs rewritten to spaces/uat), pre-create the
#                 data axes, and generate -Days of realistic ORDERS_yyyyMMdd.csv into the inbox —
#                 including one deliberately MISSING day (fires the SEQUENCE_GAP alert) and one file
#                 with malformed rows (populates rejects/quarantine). Deterministic (fixed RNG seed).
#   -Phase drive  ONLINE (backend restarted after 'files' so the boot scan adopts the space): pushes
#                 the generated files through the REAL engine and seeds operational objects via the
#                 REAL routes — ingestion runs, a storage_report job (Jobs CRUD), the nightly
#                 maintenance chain (backups + verify + reports history), tags + a tag rule, and a
#                 spread of incidents/cases with transitions, comments, SLA deadlines and links.
#   -Phase all    files, then wait for you to restart the backend? No — 'all' is refused on purpose:
#                 the space is only discovered at boot, so a restart must sit between the phases.
#
# No direct database writes anywhere — everything flows through pipelines, jobs and control routes
# (the System Maintenance module's own WON'T list forbids out-of-band DB manipulation, and seeding
# through the engine is what makes the audit/lineage/run-history surfaces worth testing).
#
# Durable incidents/cases across restarts need the object store on its DB backend:
#   -Dobjects.backend=db   (the dev launch config sets this; the default is in-memory).

[CmdletBinding()]
param(
    [ValidateSet('files', 'drive')]
    [string]$Phase = 'files',
    [int]$Days = 60,
    [int]$MinRows = 150,
    [int]$MaxRows = 500,
    [string]$Base = 'http://localhost:8080',
    [string]$Space = 'uat',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$spaceDir = Join-Path $repo "spaces/$Space"
$api = "$Base/spaces/$Space"

# ── helpers ─────────────────────────────────────────────────────────────────────

function Invoke-Api {
    # Resilient wrapper: seeding must not die on one 4xx — warn and carry on.
    param([string]$Method, [string]$Url, $Body = $null)
    try {
        if ($null -ne $Body) {
            return Invoke-RestMethod -Method $Method -Uri $Url -ContentType 'application/json' `
                -Body ($Body | ConvertTo-Json -Depth 6)
        }
        return Invoke-RestMethod -Method $Method -Uri $Url
    } catch {
        Write-Warning "$Method $Url failed: $($_.Exception.Message)"
        return $null
    }
}

# ── phase: files ────────────────────────────────────────────────────────────────

function New-UatSpaceFiles {
    if (Test-Path $spaceDir) {
        if (-not $Force) { throw "spaces/$Space already exists — re-run with -Force to rebuild it from scratch." }
        Write-Host "Removing existing spaces/$Space (generated content, -Force given)..."
        Remove-Item -Recurse -Force $spaceDir
    }

    Write-Host "Cloning spaces/demo/config -> spaces/$Space/config (paths rewritten)..."
    New-Item -ItemType Directory -Force -Path $spaceDir | Out-Null
    Copy-Item -Recurse -Path (Join-Path $repo 'spaces/demo/config') -Destination (Join-Path $spaceDir 'config')
    # Rewrite every literal spaces/demo path inside the cloned configs — a clone still pointing at
    # spaces/demo would silently read/write the demo space's data.
    Get-ChildItem -Recurse -File (Join-Path $spaceDir 'config') | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName)
        if ($text.Contains('spaces/demo')) {
            [IO.File]::WriteAllText($_.FullName, $text.Replace('spaces/demo', "spaces/$Space"))
        }
    }

    @"
display_name: UAT
description: Generated user-acceptance-testing space — spaces/demo config cloned at volume by tools/seed-uat.ps1. Gitignored; safe to delete and re-seed at any time.
created_at: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))
"@ | Set-Content -NoNewline (Join-Path $spaceDir 'space.toon')

    # Pre-create every directory the orders pipeline expects (mirrors demo's seed-inbox.ps1).
    foreach ($d in 'inbox/orders', 'orders/database', 'orders/backup', 'orders/temp', 'orders/errors',
                   'orders/quarantine', 'orders/markers', 'orders/status', 'orders/logs',
                   'reports/orders_daily', 'backups', 'reports') {
        New-Item -ItemType Directory -Force -Path (Join-Path $spaceDir "data/$d") | Out-Null
    }

    Write-Host "Generating $Days day(s) of orders ($MinRows-$MaxRows rows/day, deterministic)..."
    Get-Random -SetSeed 20260712 | Out-Null
    $inbox = Join-Path $spaceDir 'data/inbox/orders'
    $regions  = @('NORTH', 'south', 'EAST', 'West', 'CENTRAL')     # mixed case exercises the UPPER() mapping
    $products = @{ WIDGET = 9.99; GADGET = 24.5; GIZMO = 14.25; DOODAD = 4.75
                   SPROCKET = 39.0; FLANGE = 89.9; BRACKET = 7.2; COUPLER = 55.1 }
    $names = @($products.Keys)
    $orderId = 100000
    $totalRows = 0
    $gapIndex = [int]($Days / 2)          # one silent day mid-range → SEQUENCE_GAP alert
    $dirtyIndex = [int]($Days / 3)        # one file carries malformed rows → rejects/quarantine
    $start = (Get-Date).Date.AddDays(-$Days)

    for ($i = 0; $i -lt $Days; $i++) {
        if ($i -eq $gapIndex) { continue }
        $day = $start.AddDays($i)
        $rows = Get-Random -Minimum $MinRows -Maximum ($MaxRows + 1)
        if ($day.DayOfWeek -in 'Saturday', 'Sunday') { $rows = [int]($rows * 0.4) }   # weekend dip
        $rows = [int]($rows * (1 + 0.5 * $i / $Days))                                  # gentle growth trend
        $sb = [System.Text.StringBuilder]::new()
        [void]$sb.AppendLine('ORDER_ID,ORDER_DATE,REGION,PRODUCT,QUANTITY,UNIT_PRICE,STATUS')
        for ($r = 0; $r -lt $rows; $r++) {
            $orderId++
            $product = $names[(Get-Random -Maximum $names.Count)]
            $price = [math]::Round($products[$product] * (Get-Random -Minimum 90 -Maximum 111) / 100.0, 2)
            $qty = [math]::Max(1, [int](Get-Random -Minimum 1 -Maximum 8) * (Get-Random -Minimum 1 -Maximum 4) - 2)
            $roll = Get-Random -Maximum 100
            $status = if ($roll -lt 72) { 'SHIPPED' } elseif ($roll -lt 90) { 'NEW' } else { 'CANCELLED' }
            $region = $regions[(Get-Random -Maximum $regions.Count)]
            [void]$sb.AppendLine(('{0},{1},{2},{3},{4},{5},{6}' -f
                $orderId, $day.ToString('yyyy-MM-dd'), $region, $product, $qty, $price, $status))
        }
        if ($i -eq $dirtyIndex) {   # malformed rows: non-numeric quantity, impossible date
            [void]$sb.AppendLine(('{0},{1},NORTH,WIDGET,N/A,9.99,NEW' -f (++$orderId), $day.ToString('yyyy-MM-dd')))
            [void]$sb.AppendLine(('{0},2026-13-40,EAST,GIZMO,2,14.25,SHIPPED' -f (++$orderId)))
            [void]$sb.AppendLine(('{0},{1},south,GADGET,three,24.50,NEW' -f (++$orderId), $day.ToString('yyyy-MM-dd')))
        }
        [IO.File]::WriteAllText((Join-Path $inbox ('ORDERS_{0}.csv' -f $day.ToString('yyyyMMdd'))), $sb.ToString())
        $totalRows += $rows
    }
    # Manifest lets the drive phase ASSERT ingest fidelity (row count drift = double-ingest bug).
    @{ days = $Days; cleanRows = $totalRows; dirtyRows = 3 } | ConvertTo-Json |
        Set-Content (Join-Path $spaceDir 'data/SEED_MANIFEST.json')

    Write-Host ''
    Write-Host "Done: spaces/$Space with $($Days - 1) daily files, ~$totalRows rows (1 gap day, 3 dirty rows)."
    Write-Host 'NEXT: restart the backend (the boot scan adopts the new space), then run:'
    Write-Host "      pwsh tools/seed-uat.ps1 -Phase drive"
}

# ── phase: drive ────────────────────────────────────────────────────────────────

function Invoke-UatDrive {
    try { Invoke-RestMethod -Uri "$Base/health" | Out-Null }
    catch { throw "Backend not reachable at $Base — start it first (launch config 'inspector-backend')." }
    $spaces = Invoke-Api GET "$Base/spaces"
    if (-not ($spaces | Where-Object { $_.id -eq $Space })) {
        throw "Space '$Space' not registered — run -Phase files first, then RESTART the backend (spaces are discovered at boot)."
    }

    Write-Host '1/6 Ingesting the generated inbox through the orders pipeline...'
    # NEVER trigger while another pass may be running: the boot poll cycle already ingests the inbox,
    # and a concurrent manual trigger double-ingests files whose completion markers haven't landed
    # yet (measured: ~70% row inflation). Poll only; nudge with a trigger ONLY after a real stall.
    $inbox = Join-Path $spaceDir 'data/inbox/orders'
    $lastLeft = -1; $stalled = 0
    for ($i = 0; $i -lt 200; $i++) {
        $left = @(Get-ChildItem -ErrorAction SilentlyContinue $inbox).Count
        if ($left -eq 0) { break }
        if ($left -eq $lastLeft) { $stalled++ } else { $stalled = 0; $lastLeft = $left }
        if ($stalled -ge 5) { Invoke-Api POST "$api/runs/orders/trigger" | Out-Null; $stalled = -5 }
        Start-Sleep -Seconds 3
    }
    $left = @(Get-ChildItem -ErrorAction SilentlyContinue $inbox).Count
    Write-Host "    inbox files remaining: $left"

    Write-Host '2/6 Registering + running a storage_report job (Jobs CRUD)...'
    Invoke-Api POST "$api/jobs" @{ name = 'storage_report'; type = 'maintenance'; task = 'storage_report'
                                   cron = '0 6 * * *'; dir = "spaces/$Space"; warn_bytes = '1073741824' } | Out-Null
    Invoke-Api POST "$api/jobs/storage_report/trigger" | Out-Null

    Write-Host '3/6 Firing the nightly maintenance chain twice (backups/verify/report history) + orders_summary...'
    foreach ($j in @('runlog_retention', 'orders_summary', 'runlog_retention')) {
        Invoke-Api POST "$api/jobs/$j/trigger" | Out-Null
        Start-Sleep -Seconds 3
    }

    Write-Host '4/6 Tags + one auto tag rule...'
    foreach ($t in @(
        @{ name = 'hot';         color = 'red' },    @{ name = 'orders-feed'; color = 'blue' },
        @{ name = 'data-quality'; color = 'amber' }, @{ name = 'sla';         color = 'purple' },
        @{ name = 'follow-up';   color = 'green' })) {
        Invoke-Api POST "$api/tags" $t | Out-Null
    }
    Invoke-Api POST "$api/tags/rules" @{ name = 'critical-is-hot'; tag = 'hot'; severity = 'CRITICAL' } | Out-Null

    Write-Host '5/6 Incidents (36) + cases (10) with transitions, comments, SLAs, links...'
    Get-Random -SetSeed 20260713 | Out-Null
    $sev = @('CRITICAL', 'WARNING', 'WARNING', 'INFO', 'INFO', 'INFO')
    $pri = @('P1', 'P2', 'P2', 'P3', 'P3', 'P4')
    $people = @('asha', 'bruno', 'chen', 'dara', $null)
    $titles = @(
        'Sequence gap in ORDERS feed', 'Reject rate above 2% in daily load', 'Duplicate file re-delivered',
        'Quantity outliers in {0} region', 'Late file arrival from upstream SFTP', 'Schema drift: unexpected trailing column',
        'CANCELLED ratio spike for {1}', 'Zero-byte file quarantined', 'Backup verification took over 60s',
        'Unit price regression for {1}', 'Enrichment lag behind ingest', 'Marker file left behind after crash')
    $regions = @('NORTH', 'SOUTH', 'EAST', 'WEST', 'CENTRAL')
    $products = @('WIDGET', 'GADGET', 'GIZMO', 'SPROCKET', 'FLANGE')
    $incidentIds = @()
    for ($n = 1; $n -le 36; $n++) {
        $title = ($titles[(Get-Random -Maximum $titles.Count)] -f
                  $regions[(Get-Random -Maximum $regions.Count)], $products[(Get-Random -Maximum $products.Count)])
        $body = @{ type = 'INCIDENT'; title = "$title (#$n)"
                   description = 'Seeded for UAT by tools/seed-uat.ps1 — realistic shape, synthetic content.'
                   severity = $sev[(Get-Random -Maximum $sev.Count)]; priority = $pri[(Get-Random -Maximum $pri.Count)]
                   assignee = $people[(Get-Random -Maximum $people.Count)]
                   attributes = @{ source = 'orders'; region = $regions[(Get-Random -Maximum $regions.Count)] } }
        if ($n % 5 -eq 0) { $body.dueInMinutes = 240 * ($n % 3 + 1) }   # some SLA deadlines
        $created = Invoke-Api POST "$api/objects" $body
        if (-not $created) { continue }
        $incidentIds += $created.id
        # Incident mail lifecycle (GLOSSARY §9): IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED.
        if ($n % 3 -eq 0) { Invoke-Api POST "$api/objects/$($created.id)/transition" @{ action = 'accept'; actor = 'uat-seeder' } | Out-Null }
        if ($n % 4 -eq 0) { Invoke-Api POST "$api/objects/$($created.id)/resolve" @{ actor = 'uat-seeder' } | Out-Null }
        if ($n % 6 -eq 0) { Invoke-Api POST "$api/objects/$($created.id)/transition" @{ action = 'archive'; actor = 'uat-seeder' } | Out-Null }
        if ($n % 3 -eq 1) {
            Invoke-Api POST "$api/objects/$($created.id)/comments" @{
                author = 'uat-seeder'; body = 'Investigated: matches the synthetic dirty-rows batch. Watching the next load.' } | Out-Null
        }
    }
    $caseIds = @()
    foreach ($c in @('July orders reconciliation', 'Upstream SFTP reliability review', 'Data-quality deep dive: quantity fields',
                     'Recurring duplicate deliveries', 'SLA breach postmortem', 'Regional pricing anomalies',
                     'Quarter-end volume readiness', 'Feed onboarding checklist gaps', 'Enrichment latency budget',
                     'Backup/restore drill follow-ups')) {
        $created = Invoke-Api POST "$api/objects" @{ type = 'CASE'; title = $c
            description = 'Seeded for UAT by tools/seed-uat.ps1.'; priority = 'P2'
            assignee = $people[(Get-Random -Maximum $people.Count)] }
        if ($created) {
            $caseIds += $created.id
            if ($caseIds.Count % 2 -eq 0) {   # CASE lifecycle: OPEN → INVESTIGATING → …
                Invoke-Api POST "$api/objects/$($created.id)/transition" @{ action = 'investigate'; actor = 'uat-seeder' } | Out-Null
            }
        }
    }
    for ($k = 0; $k -lt [math]::Min(6, $incidentIds.Count); $k++) {   # link incidents into the first cases
        if ($caseIds.Count -gt 0) {
            Invoke-Api POST "$api/objects/$($caseIds[$k % $caseIds.Count])/links" @{
                to = $incidentIds[$k]; relationship = 'related'; actor = 'uat-seeder' } | Out-Null
        }
    }

    Write-Host '6/6 Summary...'
    # NOTE: assign-then-.Count, never `IRM | Measure-Object` — Invoke-RestMethod emits a parsed JSON
    # array as ONE pipeline object, so a direct pipe counts 1 regardless of the array's length.
    $incidents = (Invoke-Api GET "$api/objects?type=INCIDENT").Count
    $cases = (Invoke-Api GET "$api/objects?type=CASE").Count
    $orderCount = Invoke-Api POST "$api/bi/query" @{ dataset = 'orders_dataset'; measures = @(@{ agg = 'count' }) }
    $ingested = [long]$orderCount.rows[0].count
    $backups = @(Get-ChildItem -ErrorAction SilentlyContinue (Join-Path $spaceDir 'data/backups') -Filter '*.zip').Count
    Write-Host ''
    Write-Host "Seeded space '$Space':"
    Write-Host "  orders rows ingested   : $ingested"
    $manifestFile = Join-Path $spaceDir 'data/SEED_MANIFEST.json'
    if (Test-Path $manifestFile) {   # ingest-fidelity assertion: drift beyond the dirty rows = a bug
        $manifest = Get-Content $manifestFile | ConvertFrom-Json
        if ([math]::Abs($ingested - $manifest.cleanRows) -gt $manifest.dirtyRows) {
            Write-Warning ("row-count drift: generated {0} clean rows but the dataset holds {1} — investigate double-ingest before using this space for UAT." `
                -f $manifest.cleanRows, $ingested)
        } else {
            Write-Host "  ingest fidelity        : OK (generated $($manifest.cleanRows) clean rows)"
        }
    }
    Write-Host "  incidents              : $incidents"
    Write-Host "  cases                  : $cases"
    Write-Host "  backup archives        : $backups"
    Write-Host "UI: switch the space to '$Space' — Operations, Incidents/Case Manager, System Maintenance,"
    Write-Host '    Studio dashboards and Catalog all have data now.'
}

switch ($Phase) {
    'files' { New-UatSpaceFiles }
    'drive' { Invoke-UatDrive }
}
