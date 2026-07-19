# package.ps1 — Build and bundle file-processor for remote server deployment.
#
# Usage (run from inside inspecto/ or from the sandbox root):
#   powershell -ExecutionPolicy Bypass -File inspecto\package.ps1 [-NoBuild]
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
    [switch]$NoBuild,   # skip mvn build; use existing JAR in target/
    [switch]$NoUi,      # skip the Angular UI build/bundle (inspecto-ui/ is optional)
    [switch]$NoRuntime  # skip embedding a trimmed Java runtime (target server must then provide Java 24+)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── locate repo root (works whether called from inspecto/ or sandbox root) ──
$scriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$adjParserDir = if ((Split-Path -Leaf $scriptDir) -eq 'inspecto') { $scriptDir }
               else { Join-Path $scriptDir 'inspecto' }
$sandboxRoot  = Split-Path -Parent $adjParserDir
$targetDir    = Join-Path $adjParserDir 'target'
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

# ── step 1b: build the operator UI (optional; guarded so a checkout without inspecto-ui/ still bundles) ──
# The Angular SPA (Inspecto console) lives in the monorepo's top-level inspecto-ui/ (sibling of inspecto/).
# Its toolchain (Node/pnpm) is intentionally NOT part of the Maven reactor — invoked here only for the bundle.
$uiDir    = Join-Path $sandboxRoot 'inspecto-ui'
$uiDistRoot = Join-Path $uiDir 'dist'
$uiBuilt  = $false
if (-not $NoUi -and (Test-Path (Join-Path $uiDir 'package.json'))) {
    Write-Host "Building operator UI (inspecto-ui/)..." -ForegroundColor Cyan
    Push-Location $uiDir
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed in inspecto-ui/" }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw "ng build failed in inspecto-ui/" }
        $uiBuilt = $true
        Write-Host "UI build complete." -ForegroundColor Green
    } finally { Pop-Location }
} elseif (-not (Test-Path (Join-Path $uiDir 'package.json'))) {
    Write-Host "  (no inspecto-ui/ project found — bundling API only; UI hosting will be inactive)" -ForegroundColor Yellow
}

# Discover the shaded JAR by pattern so we don't pin to a specific version number.
$jarSrc = Get-ChildItem -Path $targetDir -Filter 'file-processor-*.jar' -ErrorAction SilentlyContinue |
          Select-Object -First 1 -ExpandProperty FullName
if (-not $jarSrc -or -not (Test-Path $jarSrc)) {
    throw "JAR not found matching $targetDir\file-processor-*.jar.  Run without -NoBuild or build manually first."
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

# ── step 3b: copy the built UI dist → bundle/ui (served by ControlApi via -Dui.dir=./ui) ──
# Angular emits to ui/dist/<app>[/browser]; locate the folder that actually holds index.html.
if ($uiBuilt -and (Test-Path $uiDistRoot)) {
    $indexHtml = Get-ChildItem -Path $uiDistRoot -Filter 'index.html' -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1
    if ($indexHtml) {
        $uiOut = "$bundleDir\ui"
        $null = New-Item -ItemType Directory $uiOut -Force
        Copy-Item -Path (Join-Path $indexHtml.DirectoryName '*') -Destination $uiOut -Recurse -Force
        Write-Host "Bundled UI from $($indexHtml.DirectoryName) → $uiOut" -ForegroundColor Green
    } else {
        Write-Host "  (UI build produced no index.html under $uiDistRoot — skipping UI bundle)" -ForegroundColor Yellow
    }
}

# ── step 4: copy configs — rewrite schema_file paths for bundle-root CWD ──────
# Local configs use  "inspecto/config/<adapter>/..."  (relative to sandbox root).
# Deployed configs use  "config/<adapter>/..."          (relative to bundle root).
function Copy-Config([string]$src, [string]$dst) {
    $content = Get-Content $src -Raw
    $content = $content -replace 'inspecto/config/', 'config/'
    Set-Content -Path $dst -Value $content -NoNewline
}
# adjustment_schema.toon / adj_gen.toon are generated per-source (`ura create-schema`) and may be
# absent on a clean checkout. Copy them when present rather than aborting the whole bundle under
# $ErrorActionPreference='Stop'.
function Copy-IfPresent([string]$src, [string]$dst) {
    if (Test-Path $src) { Copy-Item $src $dst }
    else { Write-Host "  (skipping missing optional config: $src)" -ForegroundColor Yellow }
}

Copy-Config    "$adjParserDir\config\adjustment\adjustment_pipeline.toon" `
               "$bundleDir\config\adjustment\adjustment_pipeline.toon"
Copy-IfPresent "$adjParserDir\config\adjustment\adjustment_schema.toon"   `
               "$bundleDir\config\adjustment\adjustment_schema.toon"
Copy-IfPresent "$adjParserDir\config\adjustment\adj_gen.toon"             `
               "$bundleDir\config\adjustment\adj_gen.toon"

Copy-Config    "$adjParserDir\config\voucher\voucher_pipeline.toon" `
               "$bundleDir\config\voucher\voucher_pipeline.toon"
Copy-Item      "$adjParserDir\config\voucher\voucher_76.toon"       `
               "$bundleDir\config\voucher\voucher_76.toon"
Copy-Item      "$adjParserDir\config\voucher\voucher_116.toon"      `
               "$bundleDir\config\voucher\voucher_116.toon"
Copy-Item      "$adjParserDir\config\voucher\voucher_537.toon"      `
               "$bundleDir\config\voucher\voucher_537.toon"
Copy-IfPresent "$adjParserDir\config\voucher\voucher.grammar.toon"  `
               "$bundleDir\config\voucher\voucher.grammar.toon"


# ── step 5: bundle run scripts (Linux + Windows) ───────────────────────────────
@'
#!/usr/bin/env bash
# Usage: ./run.sh <adapter>
# Looks up the pipeline file as config/<adapter>/*_pipeline.toon (first match wins),
# so it transparently handles both "<adapter>_pipeline.toon" and variants like
# "<adapter>_unknown_pipeline.toon".
set -euo pipefail
cd "$(dirname "$0")"
ADAPTER="${1:?Usage: run.sh <adapter>   (e.g. adjustment, voucher)}"
PIPELINE=$(ls "config/${ADAPTER}"/*_pipeline.toon 2>/dev/null | head -1)
if [ -z "$PIPELINE" ]; then
    echo "ERROR: no pipeline file found at config/${ADAPTER}/*_pipeline.toon" >&2
    exit 1
fi
echo "[run.sh] Using pipeline: $PIPELINE"
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
exec "$JAVA" --enable-native-access=ALL-UNNAMED \
          -jar file-processor.jar \
          "$PIPELINE"
'@ | Set-Content -Path "$bundleDir\run.sh" -NoNewline

$runBatContent = @'
@echo off
rem Usage: run.bat ADAPTER         (e.g. adjustment, voucher)
rem Looks up the pipeline file as config\ADAPTER\*_pipeline.toon (first match wins),
rem so it handles both "ADAPTER_pipeline.toon" and variants like
rem "ADAPTER_unknown_pipeline.toon".
setlocal
cd /d "%~dp0"
if "%1"=="" (
    echo Usage: run.bat ADAPTER   [e.g. adjustment, voucher]
    exit /b 1
)
set "PIPELINE="
for %%F in (config\%1\*_pipeline.toon) do (
    if not defined PIPELINE set "PIPELINE=%%F"
)
if not defined PIPELINE (
    echo ERROR: no pipeline file found at config\%1\*_pipeline.toon
    exit /b 1
)
echo [run.bat] Using pipeline: %PIPELINE%
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
"%JAVA%" --enable-native-access=ALL-UNNAMED ^
     -jar file-processor.jar ^
     "%PIPELINE%"
'@
# Write with CRLF line endings + ASCII so Windows cmd.exe parses correctly.
[System.IO.File]::WriteAllText(
    "$bundleDir\run.bat",
    $runBatContent.Replace("`n", "`r`n"),
    [System.Text.Encoding]::ASCII
)

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
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
exec "$JAVA" --enable-native-access=ALL-UNNAMED \
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
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
"%JAVA%" --enable-native-access=ALL-UNNAMED ^
     -cp file-processor.jar ^
     com.gamma.util.MainApp %*
'@
# Write with CRLF line endings so Windows cmd.exe parses the batch file correctly.
[System.IO.File]::WriteAllText(
    "$bundleDir\ura.bat",
    $uraBatContent.Replace("`n", "`r`n"),
    [System.Text.Encoding]::ASCII
)

# ── step 6b: bundle serve scripts (run the control plane + operator UI) ─────────
# Unlike run.sh (one-shot ETL), serve.sh launches the long-running ControlApi service with the
# HTTP control plane + operator UI. It serves the bundled SPA from ./ui via -Dui.dir. Tokens are
# read from the environment so secrets stay out of the bundle: CONTROL_TOKEN (required to use the
# control plane) and ASSIST_TOKEN (optional, enables the assist/catalog read routes).
@'
#!/usr/bin/env bash
# Usage: CONTROL_TOKEN=... [ASSIST_TOKEN=...] [PORT=8080] ./serve.sh [config-dir-or-pipeline ...]
# Starts the control plane + operator UI. With no args it serves every pipeline under config/.
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-8080}"
ARGS=("$@"); if [ ${#ARGS[@]} -eq 0 ]; then ARGS=("config"); fi
JAVA_OPTS=(--enable-native-access=ALL-UNNAMED "-Dcontrol.port=${PORT}")
[ -d ui ] && JAVA_OPTS+=("-Dui.dir=./ui")
[ -n "${CONTROL_TOKEN:-}" ] && JAVA_OPTS+=("-Dcontrol.token=${CONTROL_TOKEN}")
[ -n "${ASSIST_TOKEN:-}" ]  && JAVA_OPTS+=("-Dassist.read.token=${ASSIST_TOKEN}")
[ -n "${CORS_ORIGIN:-}" ]   && JAVA_OPTS+=("-Dcontrol.cors=${CORS_ORIGIN}")
JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"
echo "[serve.sh] ControlApi on :${PORT}  (UI: $([ -d ui ] && echo ./ui || echo none))"
exec "$JAVA" "${JAVA_OPTS[@]}" -cp file-processor.jar com.gamma.control.ControlApi "${ARGS[@]}"
'@ | Set-Content -Path "$bundleDir\serve.sh" -NoNewline

$serveBatContent = @'
@echo off
rem Usage: set CONTROL_TOKEN=... && serve.bat [config-dir-or-pipeline ...]
rem Optional env: ASSIST_TOKEN, PORT (default 8080), CORS_ORIGIN.
rem Starts the control plane + operator UI (serves bundled .\ui via -Dui.dir).
setlocal
cd /d "%~dp0"
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%*"
if "%ARGS%"=="" set "ARGS=config"
set "OPTS=--enable-native-access=ALL-UNNAMED -Dcontrol.port=%PORT%"
if exist ui set "OPTS=%OPTS% -Dui.dir=./ui"
if not "%CONTROL_TOKEN%"=="" set "OPTS=%OPTS% -Dcontrol.token=%CONTROL_TOKEN%"
if not "%ASSIST_TOKEN%"=="" set "OPTS=%OPTS% -Dassist.read.token=%ASSIST_TOKEN%"
if not "%CORS_ORIGIN%"=="" set "OPTS=%OPTS% -Dcontrol.cors=%CORS_ORIGIN%"
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
echo [serve.bat] ControlApi on :%PORT%
"%JAVA%" %OPTS% -cp file-processor.jar com.gamma.control.ControlApi %ARGS%
'@
[System.IO.File]::WriteAllText(
    "$bundleDir\serve.bat",
    $serveBatContent.Replace("`n", "`r`n"),
    [System.Text.Encoding]::ASCII
)

# ── step 6c: embed a trimmed Java runtime (jlink) so the bundle is self-contained ──
# Produces bundle/runtime/ — the run/serve/ura scripts auto-prefer it over system java.
# NOTE: jlink output is OS-specific. Built from the Windows GraalVM JDK, this image runs on
# Windows only; the bundled *.sh launchers fall back to system java on Linux.
if (-not $NoRuntime) {
    # Module set = jdeps core for file-processor.jar (java.base, java.compiler, java.desktop,
    # java.naming, java.scripting, java.sql, jdk.httpserver) + runtime-only safety modules that
    # jdeps cannot see in a fat JAR: jdk.crypto.ec (TLS/JDBC ciphers), jdk.unsupported
    # (sun.misc.Unsafe), java.net.http (HttpClient), jdk.zipfs (.zip via NIO), java.management (JMX).
    $runtimeModules = 'java.base,java.compiler,java.desktop,java.naming,java.scripting,java.sql,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.net.http,jdk.zipfs,java.management'

    # Locate a jlink: prefer the repo's GraalVM cache, then JAVA_HOME, then PATH.
    $jlink = Get-ChildItem -Path (Join-Path $sandboxRoot '.graalvm-cache') -Filter 'jlink.exe' -Recurse -ErrorAction SilentlyContinue |
             Select-Object -First 1 -ExpandProperty FullName
    if (-not $jlink -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jlink.exe")) { $jlink = "$env:JAVA_HOME\bin\jlink.exe" }
    if (-not $jlink) { $jlink = (Get-Command jlink.exe -ErrorAction SilentlyContinue).Source }
    if (-not $jlink) { throw "jlink.exe not found (looked in .graalvm-cache, JAVA_HOME, PATH). Re-run with -NoRuntime to skip embedding a JVM." }

    Write-Host "Embedding trimmed Java runtime via $jlink ..." -ForegroundColor Cyan
    $runtimeOut = Join-Path $bundleDir 'runtime'
    if (Test-Path $runtimeOut) { Remove-Item $runtimeOut -Recurse -Force }
    & $jlink --add-modules $runtimeModules --strip-debug --no-header-files --no-man-pages --compress=zip-9 --output $runtimeOut
    if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
    $rtSize = [math]::Round(((Get-ChildItem $runtimeOut -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "Embedded runtime ready: $runtimeOut (${rtSize} MB, Windows-only)" -ForegroundColor Green
} else {
    Write-Host "  (-NoRuntime: skipping embedded JVM; target server must provide Java 24+)" -ForegroundColor Yellow
}

# ── step 7: copy README + docs tree ─────────────────────────────────────────────
# In the repo the README lives in inspecto/ and links to ../docs/. In the
# bundle the README sits at the root, so rewrite ../docs/ → docs/ and ship the
# docs tree alongside it so the links resolve.
$readme = Get-Content "$adjParserDir\README.md" -Raw
$readme = $readme -replace '\.\./docs/', 'docs/'
Set-Content -Path "$bundleDir\README.md" -Value $readme -NoNewline
$docsSrc = Join-Path $sandboxRoot 'docs'
if (Test-Path $docsSrc) {
    # Copy file-by-file (not one Copy-Item -Recurse) so a single locked/inaccessible
    # file (e.g. held by another process/AV) can't silently truncate the rest of the
    # tree under $ErrorActionPreference = 'Stop' — a whole-tree recursive copy was
    # observed to abort at the first such file and skip every remaining item.
    $docsOut = "$bundleDir\docs"
    Get-ChildItem -Path $docsSrc -Recurse -File | ForEach-Object {
        $srcFile = $_
        $destPath = Join-Path $docsOut $srcFile.FullName.Substring($docsSrc.Length + 1)
        try {
            $destDir = Split-Path $destPath -Parent
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            Copy-Item $srcFile.FullName -Destination $destPath -Force -ErrorAction Stop
        } catch {
            Write-Warning "docs copy: skipped '$($srcFile.FullName)' ($($_.Exception.Message))"
        }
    }
}

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
Write-Host "  4. ETL pipeline (one-shot):"
Write-Host "       run.bat voucher         (Windows)"
Write-Host "       bash run.sh voucher     (Linux)"
Write-Host "  4b. Control plane + operator UI (long-running service):"
Write-Host "       set CONTROL_TOKEN=secret && serve.bat        (Windows)"
Write-Host "       CONTROL_TOKEN=secret bash serve.sh           (Linux)"
Write-Host "       then open http://localhost:8080/  (UI served from ./ui)"
Write-Host "  5. Pre-ETL utilities:"
Write-Host "       ura.bat help            (Windows)"
Write-Host "       bash ura.sh help        (Linux)"
Write-Host "       bash ura.sh search  config/adjustment/adjustment_pipeline.toon"
Write-Host "       bash ura.sh backup  config/voucher/voucher_pipeline.toon"
Write-Host ""
if (-not $NoRuntime) {
    Write-Host "Embedded Java runtime included (bundle\runtime\) — no JVM needed on the Windows target." -ForegroundColor Green
    Write-Host "The run/serve/ura launchers auto-prefer it; on Linux they fall back to system Java 24+."
} else {
    Write-Host "Java 24+ required on the target server.  No other dependencies needed."
}
