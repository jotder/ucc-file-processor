#!/usr/bin/env pwsh
#requires -Version 5.1
<#
.SYNOPSIS
    Install / upgrade and verify the graphify knowledge-graph CLI for this repo.

.DESCRIPTION
    "graphify" here is the Python *graphifyy* package (PyPI) — NOT the unrelated npm
    package of the same name. This script makes the `graphify` CLI available on the
    CURRENT USER's PATH (no admin required) and refreshes the repo's knowledge graph
    in graphify-out/.

    Run it ONCE per user working in this directory. graphify-out/ (the graph) and
    .claude/ (the hooks/settings that enforce the query-first workflow) are gitignored
    but shared on-disk in this sandbox, so they already apply to everyone here — only
    the CLI itself is per-user (each OS user has their own Python / PATH).

    Safe to run repeatedly. Tolerates being offline if graphify is already installed.

.PARAMETER NoUpdate
    Skip refreshing graphify-out/ (just install + verify the CLI).

.PARAMETER Rebuild
    Force a deterministic full re-extract (graphify update . --force). Use after a
    refactor that deleted code so the graph shrinks correctly.

.EXAMPLE
    pwsh scripts/setup-graphify.ps1
.EXAMPLE
    pwsh scripts/setup-graphify.ps1 -NoUpdate
#>
[CmdletBinding()]
param(
    [switch]$NoUpdate,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

function Test-Cmd([string]$name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }
function Write-Section([string]$t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# Repo root = parent of this script's directory.
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot
Write-Host "Repo: $RepoRoot"

# ---------------------------------------------------------------------------
Write-Section "1/4  Install or upgrade graphify (PyPI package: graphifyy)"
$alreadyOnPath = Test-Cmd graphify
if ($alreadyOnPath) {
    Write-Host "Found graphify on PATH: $((Get-Command graphify).Source)"
}

$installed = $false
try {
    if (Test-Cmd uv) {
        Write-Host "Installer: uv tool"
        uv tool install --upgrade graphifyy
        $installed = $true
    }
    elseif (Test-Cmd pipx) {
        Write-Host "Installer: pipx"
        pipx install graphifyy 2>$null | Out-Null
        pipx upgrade graphifyy 2>$null | Out-Null
        $installed = $true
    }
    elseif (Test-Cmd pip) {
        Write-Host "Installer: pip"
        pip install --upgrade graphifyy
        $installed = $true
    }
    elseif (Test-Cmd python) {
        Write-Host "Installer: python -m pip"
        python -m pip install --upgrade graphifyy
        $installed = $true
    }
    else {
        throw "No Python installer found (need uv, pipx, or pip). Install Python 3.10+ first: https://www.python.org/downloads/"
    }
}
catch {
    if ($alreadyOnPath) {
        Write-Warning "Install/upgrade step failed (offline?). Continuing with the existing graphify."
        Write-Warning $_.Exception.Message
    }
    else {
        throw
    }
}

# ---------------------------------------------------------------------------
Write-Section "2/4  Verify the CLI is on PATH"
if (-not (Test-Cmd graphify)) {
    Write-Warning "graphify was installed but is NOT on your PATH."
    if (Test-Cmd python) {
        $scriptsDir = (python -c "import sysconfig; print(sysconfig.get_path('scripts'))" 2>$null)
        $userScriptsDir = (python -c "import sysconfig,os; print(sysconfig.get_path('scripts', scheme='nt_user') if os.name=='nt' else '')" 2>$null)
        Write-Host "Add one of these directories to your PATH, then re-run this script:"
        if ($scriptsDir)     { Write-Host "  $scriptsDir" }
        if ($userScriptsDir) { Write-Host "  $userScriptsDir" }
    }
    throw "graphify not on PATH — add the Scripts directory above and re-run."
}
$ver = (graphify --version 2>&1 | Select-Object -First 1)
Write-Host "OK: $ver"
graphify --help | Out-Null   # smoke-test (needs no graph)

# Sync the /graphify skill + CLAUDE.md registration into this user's agent platform.
# Idempotent and non-destructive ("already registered (no change)"); keeps the skill
# in lockstep with the CLI version after an upgrade.
try { graphify install | Out-Null; Write-Host "Skill synced into your agent platform (~/.claude)." }
catch { Write-Warning "Skill sync (graphify install) skipped: $($_.Exception.Message)" }

# ---------------------------------------------------------------------------
Write-Section "3/4  Knowledge graph (graphify-out/)"
$graphJson = Join-Path $RepoRoot 'graphify-out/graph.json'
if (Test-Path $graphJson) {
    if ($NoUpdate) {
        Write-Host "graph.json present; -NoUpdate set — skipping refresh."
    }
    elseif ($Rebuild) {
        Write-Host "Forcing a full deterministic re-extract..."
        graphify update . --force
    }
    else {
        Write-Host "Refreshing the graph from current code (deterministic, no LLM)..."
        graphify update .
    }
}
else {
    Write-Host "No graphify-out/graph.json yet — a first build needs one LLM pass."
    Write-Host "In Claude Code, run the skill once:   /graphify ."
    Write-Host "After that, 'graphify update .' (or this script) keeps it fresh with no LLM cost."
}

# ---------------------------------------------------------------------------
Write-Section "4/4  Ready"
Write-Host "graphify is set up for this user. Orient before grepping/reading source:"
Write-Host '  graphify query "how does the flow executor commit branches"'
Write-Host '  graphify explain "ConfigRegistry"'
Write-Host '  graphify path "FlowExecutor" "DuckDB"'
Write-Host ""
Write-Host "Keep the graph fresh after code changes:  graphify update ."
Write-Host "Wire the /graphify skill into your own agent platform (optional):  graphify install --platform claude"
