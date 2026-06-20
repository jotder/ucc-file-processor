#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run one serve-mode Inspecto example: start the engine as a service (Control API),
    seed its inbox, probe the API, then stay running so you can play with it.

.DESCRIPTION
    Unlike run-example.ps1 (one-shot batch), this starts com.gamma.control.ControlApi over
    the example's config dir on a short poll interval, waits for GET /health, seeds a fresh
    out/inbox/ from the pristine samples/, waits a couple of poll cycles, then probes the
    Control API. Generic probes (/pipelines, /events) plus any paths listed in the example's
    probes.txt are printed. By default the server keeps running for exploration (press Enter
    to stop); with -Demo it prints the probes once and stops (non-interactive, self-checking).

    Serve-mode examples use the *_pipeline.toon / *_enrich.toon / *_job.toon naming the engine
    scans for, and the harness runs the engine with CWD = the example dir, so all relative paths
    (schema_file, dirs.poll) resolve exactly as in one-shot mode. Everything is written under the
    example's own out/ directory.

    JAR resolution order: $env:INSPECTO_JAR -> ../file-processor.jar (bundle) ->
    ../target/file-processor-*.jar (source tree).

.EXAMPLE
    pwsh serve-example.ps1 06-serve/sequence-gap            # start & explore
.EXAMPLE
    pwsh serve-example.ps1 06-serve/sequence-gap -Demo      # probe once and stop
.EXAMPLE
    pwsh serve-example.ps1 06-serve/sequence-gap -Port 9090 -PollSeconds 2
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Example,
    [int]$Port = 18080,
    [int]$PollSeconds = 3,
    [int]$WaitSeconds = 0,   # extra wait before probing; 0 => auto (2*poll + 3)
    [switch]$Demo,           # probe once then stop (non-interactive); default: stay running
    [switch]$Clean
)
$ErrorActionPreference = 'Stop'
$examplesRoot = $PSScriptRoot

function Resolve-Jar {
    if ($env:INSPECTO_JAR -and (Test-Path $env:INSPECTO_JAR)) { return (Resolve-Path $env:INSPECTO_JAR).Path }
    $bundle = Join-Path $examplesRoot '..\file-processor.jar'
    if (Test-Path $bundle) { return (Resolve-Path $bundle).Path }
    $tree = Get-ChildItem (Join-Path $examplesRoot '..\target') -Filter 'file-processor-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if ($tree) { return $tree.FullName }
    throw "Engine JAR not found. Set `$env:INSPECTO_JAR, or build it (mvn -o clean package)."
}

$jar = Resolve-Jar
$dir = Join-Path $examplesRoot $Example
if (-not (Test-Path $dir)) { throw "No such example dir: '$dir'. Pass a path like 06-serve/sequence-gap." }
$pipe = Get-ChildItem $dir -Filter '*_pipeline.toon' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pipe) { throw "No *_pipeline.toon under '$dir'. Serve examples use the *_pipeline.toon naming convention." }

$base = "http://localhost:$Port"
$server = $null
Push-Location $dir
try {
    if ($Clean -and (Test-Path 'out')) { Remove-Item -Recurse -Force 'out' }
    foreach ($d in 'inbox','database','backup','temp','errors','quarantine','markers','status','logs','write') {
        New-Item -ItemType Directory -Force -Path (Join-Path 'out' $d) | Out-Null
    }
    $logOut = Join-Path 'out\logs' 'serve.out.log'
    $logErr = Join-Path 'out\logs' 'serve.err.log'
    $jargs = @(
        '--enable-native-access=ALL-UNNAMED',
        "-Dcontrol.port=$Port",
        "-Dservice.poll.seconds=$PollSeconds",
        '-Dassist.write.root=out/write',
        '-cp', $jar,
        'com.gamma.control.ControlApi', '.'
    )
    Write-Host "Starting Inspecto serve mode on $base  (poll ${PollSeconds}s)" -ForegroundColor Cyan
    Write-Host "  jar:    $jar"
    Write-Host "  config: $dir`n"
    $server = Start-Process java -ArgumentList $jargs -RedirectStandardOutput $logOut -RedirectStandardError $logErr -PassThru -NoNewWindow

    # Wait for /health to report UP (or the process to die).
    $up = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if ($server.HasExited) {
            Write-Host "Server exited early (code $($server.ExitCode)). Last log lines:" -ForegroundColor Red
            Get-Content $logErr, $logOut -Tail 30 -ErrorAction SilentlyContinue
            exit 1
        }
        try { if ((Invoke-RestMethod "$base/health" -TimeoutSec 2).status -eq 'UP') { $up = $true; break } } catch {}
    }
    if (-not $up) { Write-Host "Server did not become healthy on $base." -ForegroundColor Red; Get-Content $logErr -Tail 30 -ErrorAction SilentlyContinue; exit 1 }

    Write-Host "Healthy. Seeding out/inbox/ from samples/ ..." -ForegroundColor Green
    if (Test-Path 'samples') { Copy-Item -Recurse -Force 'samples\*' 'out\inbox\' -ErrorAction SilentlyContinue }

    $wait = if ($WaitSeconds -gt 0) { $WaitSeconds } else { ($PollSeconds * 2) + 3 }
    Write-Host "Waiting ${wait}s for the poll loop to ingest...`n"
    Start-Sleep -Seconds $wait

    # Optional second drop: re-present files (changed/duplicate content, same names) so examples can
    # demonstrate the acquisition re-presentation family (checksum/metadata change, dedup, watermark),
    # which is inherently a two-cycle scenario. Engaged only when the example ships a phase2/ dir.
    if (Test-Path 'phase2') {
        Write-Host "Second drop: seeding out/inbox/ from phase2/ ..." -ForegroundColor Green
        Copy-Item -Recurse -Force 'phase2\*' 'out\inbox\' -ErrorAction SilentlyContinue
        Write-Host "Waiting ${wait}s for the poll loop to process the second drop...`n"
        Start-Sleep -Seconds $wait
    }

    function Probe([string]$path) {
        Write-Host "# GET $path" -ForegroundColor Yellow
        try { (Invoke-RestMethod "$base$path" -TimeoutSec 5) | ConvertTo-Json -Depth 6 }
        catch { Write-Host "  (request failed: $_)" -ForegroundColor DarkYellow }
        Write-Host ''
    }
    Probe '/pipelines'
    Probe '/events?limit=20'
    if (Test-Path 'probes.txt') {
        Get-Content 'probes.txt' | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } |
            ForEach-Object { Probe $_.Trim() }
    }

    if ($Demo) {
        Write-Host "Demo complete; stopping server." -ForegroundColor Cyan
    } else {
        Write-Host "--- Server is running at $base ---" -ForegroundColor Cyan
        Write-Host "  Explore:  curl $base/pipelines   |   curl `"$base/events?limit=20`""
        Write-Host "  Drop more files into:  $(Join-Path (Get-Location) 'out\inbox')"
        Write-Host "  Press Enter to stop."
        [void](Read-Host)
    }
}
finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
        Write-Host "Server stopped."
    }
    Pop-Location
}
