#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run one bundled Inspecto example end-to-end (self-contained, no external services).

.DESCRIPTION
    Resolves the engine JAR, creates the example's output dirs, and runs the example's
    pipeline.toon with the mandatory DuckDB native-access flag. Output lands under the
    example's own out/ directory — nothing else on disk is touched.

    Works both from the source tree (inspecto/examples/) and the release bundle (examples/).
    JAR resolution order: $env:INSPECTO_JAR → ../file-processor.jar (bundle) →
    ../target/file-processor-*.jar (source tree).

.EXAMPLE
    pwsh run-example.ps1 01-ingest/hello-csv
.EXAMPLE
    $env:INSPECTO_JAR="C:\path\file-processor.jar"; pwsh run-example.ps1 02-parsing/tsv-pipe
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Example,
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
$pipeline = Join-Path $dir 'pipeline.toon'
if (-not (Test-Path $pipeline)) { throw "No pipeline.toon under '$dir'. Pass an example path like 01-ingest/hello-csv." }

Push-Location $dir
try {
    if ($Clean -and (Test-Path 'out')) { Remove-Item -Recurse -Force 'out' }
    foreach ($d in 'inbox','database','backup','temp','errors','quarantine','markers','status','logs') {
        New-Item -ItemType Directory -Force -Path (Join-Path 'out' $d) | Out-Null
    }
    # Seed a fresh working inbox from the pristine, committed samples/ (the engine consumes the poll dir).
    if (Test-Path 'samples') { Copy-Item -Recurse -Force 'samples\*' 'out\inbox\' -ErrorAction SilentlyContinue }
    Write-Host "Running '$Example'" -ForegroundColor Cyan
    Write-Host "  jar:    $jar"
    Write-Host "  config: $pipeline`n"
    & java --enable-native-access=ALL-UNNAMED -jar $jar 'pipeline.toon'
    $code = $LASTEXITCODE
    Write-Host "`nExit code: $code"
    if (Test-Path 'out/database') {
        Write-Host "Output (out/database):"
        Get-ChildItem -Recurse 'out/database' -File -ErrorAction SilentlyContinue |
            Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.FullName.Substring($dir.Length+1))" }
    }
    exit $code
}
finally { Pop-Location }
