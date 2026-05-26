# package.ps1 — Build and bundle file-processor for remote server deployment.
#
# Usage (run from inside file-processor/ or from the sandbox root):
#   powershell -ExecutionPolicy Bypass -File file-processor\package.ps1 [-NoBuild]
#
# Output:
#   file-processor-deploy.zip  (in the sandbox root, alongside inbox/ and database/)
#
# The zip is a self-contained deployment unit.  On the target server:
#   1. Unzip file-processor-deploy.zip  →  file-processor-deploy/
#   2. Create your inbox directories under file-processor-deploy/inbox/<adapter>/
#   3. java -jar file-processor-deploy/file-processor.jar file-processor-deploy/config/<adapter>/<adapter>_pipeline.toon
#      (or use the bundled run.bat / run.sh — they cd to the bundle root automatically)
#
param(
    [switch]$NoBuild   # skip mvn build; use existing JAR in target/
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── locate repo root (works whether called from file-processor/ or sandbox root) ──
$scriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$adjParserDir = if ((Split-Path -Leaf $scriptDir) -eq 'file-processor') { $scriptDir }
               else { Join-Path $scriptDir 'file-processor' }
$sandboxRoot  = Split-Path -Parent $adjParserDir
$jarSrc       = Join-Path $adjParserDir 'target\file-processor-1.0.jar'
$outZip       = Join-Path $sandboxRoot  'file-processor-deploy.zip'
$bundleDir    = Join-Path $sandboxRoot  'file-processor-deploy'

# ── step 1: build ─────────────────────────────────────────────────────────────
if (-not $NoBuild) {
    Write-Host "Building fat JAR..." -ForegroundColor Cyan
    Push-Location $adjParserDir
    & mvn clean package -q
    if ($LASTEXITCODE -ne 0) { throw "mvn build failed" }
    Pop-Location
    Write-Host "Build complete." -ForegroundColor Green
}

if (-not (Test-Path $jarSrc)) {
    throw "JAR not found at $jarSrc.  Run without -NoBuild or build manually first."
}

# ── step 2: create bundle directory ───────────────────────────────────────────
Write-Host "Assembling bundle at $bundleDir ..." -ForegroundColor Cyan
if (Test-Path $bundleDir) {
    Get-ChildItem -Path $bundleDir | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
} else {
    $null = New-Item -ItemType Directory $bundleDir
}

$null = New-Item -ItemType Directory "$bundleDir\config\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\config\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\inbox\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\inbox\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\database\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\database\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\backup\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\backup\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\temp\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\temp\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\errors\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\errors\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\quarantine\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\quarantine\voucher" -Force
$null = New-Item -ItemType Directory "$bundleDir\markers\adjustment" -Force
$null = New-Item -ItemType Directory "$bundleDir\markers\voucher" -Force

# ── step 3: copy JAR (canonical name for deployment) ──────────────────────────
Copy-Item $jarSrc "$bundleDir\file-processor.jar"

# ── step 4: copy configs — rewrite schema_file paths for bundle-root CWD ──────
# Local configs use  "file-processor/config/<adapter>/..."  (relative to sandbox root).
# Deployed configs use  "config/<adapter>/..."          (relative to bundle root).
function Copy-Config([string]$src, [string]$dst) {
    $content = Get-Content $src -Raw
    $content = $content -replace 'file-processor/config/', 'config/'
    Set-Content -Path $dst -Value $content -NoNewline
}

Copy-Config "$adjParserDir\config\adjustment\adjustment_pipeline.toon" `
            "$bundleDir\config\adjustment\adjustment_pipeline.toon"
Copy-Item   "$adjParserDir\config\adjustment\adjustment_schema.toon"   `
            "$bundleDir\config\adjustment\adjustment_schema.toon"
Copy-Item   "$adjParserDir\config\adjustment\adj_gen.toon"             `
            "$bundleDir\config\adjustment\adj_gen.toon"

Copy-Config "$adjParserDir\config\voucher\voucher_pipeline.toon"       `
            "$bundleDir\config\voucher\voucher_pipeline.toon"
Copy-Item   "$adjParserDir\config\voucher\voucher.toon"                `
            "$bundleDir\config\voucher\voucher.toon"
Copy-Item   "$adjParserDir\config\voucher\voucher.toon"                `
            "$bundleDir\config\voucher\voucher_schema.toon"
Copy-Item   "$adjParserDir\config\voucher\voucher537.toon"             `
            "$bundleDir\config\voucher\voucher537.toon"
Copy-Item   "$adjParserDir\config\voucher\voucher116.toon"             `
            "$bundleDir\config\voucher\voucher116.toon"
Copy-Item   "$adjParserDir\config\voucher\voucher76.toon"              `
            "$bundleDir\config\voucher\voucher76.toon"


# ── step 5: bundle run scripts (Linux + Windows) ───────────────────────────────
@'
#!/usr/bin/env bash
# Usage: ./run.sh [adjustment|voucher]
set -euo pipefail
cd "$(dirname "$0")"
ADAPTER="${1:?Usage: run.sh [adjustment|voucher]}"
exec java --enable-native-access=ALL-UNNAMED \
          -jar file-processor.jar \
          "config/${ADAPTER}/${ADAPTER}_pipeline.toon"
'@ | Set-Content -Path "$bundleDir\run.sh" -NoNewline

@'
@echo off
rem Usage: run.bat [adjustment|voucher]
setlocal
cd /d "%~dp0"
if "%1"=="" (
    echo Usage: run.bat [adjustment^|voucher]
    exit /b 1
)
java --enable-native-access=ALL-UNNAMED ^
     -jar file-processor.jar ^
     config\%1\%1_pipeline.toon
'@ | Set-Content -Path "$bundleDir\run.bat" -NoNewline

# ── step 6: bundle ura scripts (pre-ETL utility CLI, Linux + Windows) ─────────
@'
#!/usr/bin/env bash
# URA File Management Suite — utility CLI runner
#
# Usage: ./ura.sh [--dry-run] <command> <pipeline.toon> [args...]
#
# Examples:
#   ./ura.sh help
#   ./ura.sh search           config/adjustment/adjustment_pipeline.toon
#   ./ura.sh copy             config/voucher/voucher_pipeline.toon
#   ./ura.sh --dry-run backup config/adjustment/adjustment_pipeline.toon
#   ./ura.sh prepare-inbox    config/adjustment/adjustment_pipeline.toon
#   ./ura.sh create-schema    adjustment  samples/adj_sample.csv  config/adjustment/adj_gen.toon
set -euo pipefail
cd "$(dirname "$0")"
exec java --enable-native-access=ALL-UNNAMED \
          -cp file-processor.jar \
          com.gamma.util.MainApp "$@"
'@ | Set-Content -Path "$bundleDir\ura.sh" -NoNewline

$uraBatContent = @'
@echo off
rem URA File Management Suite - utility CLI runner
rem Usage: ura.bat [--dry-run] COMMAND pipeline.toon [args...]
rem   Commands: search, copy, copy-tars, extract, backup, prepare-inbox,
rem             create-schema, move-by-date, extract-unknown, extract-move, help
rem   Run 'ura.bat help' for full command reference.
setlocal
cd /d "%~dp0"
java --enable-native-access=ALL-UNNAMED ^
     -cp file-processor.jar ^
     com.gamma.util.MainApp %*
'@
# Write with CRLF line endings so Windows cmd.exe parses the batch file correctly.
[System.IO.File]::WriteAllText(
    "$bundleDir\ura.bat",
    $uraBatContent.Replace("`n", "`r`n"),
    [System.Text.Encoding]::ASCII
)

# ── step 7: copy README ────────────────────────────────────────────────────────
Copy-Item "$adjParserDir\README.md" "$bundleDir\README.md"

# ── step 8: zip ───────────────────────────────────────────────────────────────
if (Test-Path $outZip) { Remove-Item $outZip -Force }
Compress-Archive -Path $bundleDir -DestinationPath $outZip

Write-Host ""
Write-Host "Deployment bundle ready:" -ForegroundColor Green
Write-Host "  $outZip"
Write-Host ""
Write-Host "Deploy to remote server:" -ForegroundColor Cyan
Write-Host "  1. Copy $outZip to the server"
Write-Host "  2. Expand-Archive file-processor-deploy.zip   (PowerShell)"
Write-Host "     or:  unzip file-processor-deploy.zip       (Linux)"
Write-Host "  3. cd file-processor-deploy"
Write-Host "  4. ETL pipeline:"
Write-Host "       run.bat voucher         (Windows)"
Write-Host "       bash run.sh voucher     (Linux)"
Write-Host "  5. Pre-ETL utilities:"
Write-Host "       ura.bat help            (Windows)"
Write-Host "       bash ura.sh help        (Linux)"
Write-Host "       bash ura.sh search  config/adjustment/adjustment_pipeline.toon"
Write-Host "       bash ura.sh backup  config/voucher/voucher_pipeline.toon"
Write-Host ""
Write-Host "Java 24+ required on the target server.  No other dependencies needed."
