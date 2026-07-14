# Seed the demo space's OPERATIONAL objects through the REAL control routes (no direct DB writes):
# a spread of incidents (mail-UI priority ladder), transitions/comments, two cases with linked members,
# and one evaluation of the shipped 'incident_burst' case rule so a rule-raised case appears.
#
# Run AFTER the backend is up (see spaces/demo/config/README.md "Run it"). Durable across restarts
# only with -Dobjects.backend=db (the dev launch config sets this).
[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8080',
    [string]$Space = 'demo'
)
$ErrorActionPreference = 'Stop'
$api = "$Base/spaces/$Space"

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
        Write-Warning "$Method $Url -> $($_.Exception.Message)"
        return $null
    }
}

Write-Host '1/3 Incidents (8) with transitions + comments...'
$specs = @(
    @{ t = 'Sequence gap in ORDERS feed';             p = 'CRITICAL'; s = 'CRITICAL' },
    @{ t = 'Reject rate above 2% in daily load';      p = 'CRITICAL'; s = 'CRITICAL' },
    @{ t = 'Duplicate file re-delivered';             p = 'MAJOR';    s = 'WARNING' },
    @{ t = 'Late file arrival from upstream SFTP';    p = 'MAJOR';    s = 'WARNING' },
    @{ t = 'Quantity outliers in WEST region';        p = 'MINOR';    s = 'WARNING' },
    @{ t = 'Backup verification took over 60s';       p = 'MINOR';    s = 'INFO' },
    @{ t = 'Enrichment lag behind ingest';            p = 'LOW';      s = 'INFO' },
    @{ t = 'Marker file left behind after crash';     p = 'LOW';      s = 'INFO' }
)
$incidentIds = @()
$n = 0
foreach ($spec in $specs) {
    $n++
    $body = @{ type = 'INCIDENT'; title = $spec.t
               description = 'Seeded by spaces/demo/data/samples/seed-ops.ps1 - synthetic demo content.'
               severity = $spec.s; priority = $spec.p
               attributes = @{ source = 'orders'; category = 'data-quality' } }
    if ($n % 3 -eq 0) { $body.dueInMinutes = 240 }
    $created = Invoke-Api POST "$api/objects" $body
    if (-not $created) { continue }
    $incidentIds += $created.id
    if ($n % 3 -eq 0) { Invoke-Api POST "$api/objects/$($created.id)/transition" @{ action = 'accept'; actor = 'demo-seeder' } | Out-Null }
    if ($n % 4 -eq 0) { Invoke-Api POST "$api/objects/$($created.id)/resolve" @{ actor = 'demo-seeder' } | Out-Null }
    if ($n % 2 -eq 1) {
        Invoke-Api POST "$api/objects/$($created.id)/comments" @{
            author = 'demo-seeder'; body = 'Matches the deliberately malformed rows in ORDERS_20260703.csv.' } | Out-Null
    }
}

Write-Host '2/3 Cases (2) with linked incident members...'
$caseIds = @()
foreach ($c in @('Orders feed reliability review', 'Data-quality deep dive: quantity fields')) {
    $created = Invoke-Api POST "$api/objects" @{ type = 'CASE'; title = $c
        description = 'Seeded by seed-ops.ps1.'; priority = 'MAJOR' }
    if ($created) { $caseIds += $created.id }
}
for ($k = 0; $k -lt [math]::Min(4, $incidentIds.Count); $k++) {
    if ($caseIds.Count -gt 0) {
        Invoke-Api POST "$api/objects/$($caseIds[$k % $caseIds.Count])/links" @{
            to = $incidentIds[$k]; relationship = 'related'; actor = 'demo-seeder' } | Out-Null
    }
}

Write-Host '3/3 Evaluating the shipped incident_burst case rule (rule-raised case)...'
Invoke-Api POST "$api/cases/rules/incident_burst/evaluate" | Out-Null

$incidents = @(Invoke-Api GET "$api/objects?type=INCIDENT").Count
$cases = @(Invoke-Api GET "$api/objects?type=CASE").Count
Write-Host ''
Write-Host "Seeded demo ops surface: $incidents incidents, $cases cases."
Write-Host "UI: Incidents + Case Manager now have mail-view data; CRITICAL incidents carry the 'hot' tag"
Write-Host "    (shipped tag rule), and the case rule raised a correlated case if 2+ CRITICALs are open."
