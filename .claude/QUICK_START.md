# Quick Start Commands

Inspecto (`ucc-file-processor`) — Java 26 / Maven reactor (`file-processor-parent`).
Toolchain: JDK `C:\.jdks\openjdk-26.0.1`, Maven `C:\maven\apache-maven-3.9.16\bin\mvn.cmd`.

---

## Build & Test

```powershell
# Authoritative verify — full reactor, offline
mvn -o clean test

# Build fat JAR only (skip tests)
mvn -o clean package -q          # → inspecto/target/file-processor-*.jar

# Build deployment bundle (JAR + UI + configs + run scripts + embedded JVM → file-processor-deploy.zip)
# NOTE: run package.ps1 under pwsh 7 (UTF-8) — the script is BOM-less UTF-8; Windows PowerShell 5.1 garbles it.
pwsh -File inspecto\package.ps1                # full (embeds trimmed Windows JVM via jlink → bundle\runtime\)
pwsh -File inspecto\package.ps1 -NoBuild       # reuse target/ JAR
pwsh -File inspecto\package.ps1 -NoUi          # skip Angular UI
pwsh -File inspecto\package.ps1 -NoRuntime     # skip embedded JVM (target server must provide Java 24+)
```

JVM flag required at every launch: `--enable-native-access=ALL-UNNAMED` (DuckDB native access).

---

## Run

```powershell
# One-shot ETL pipeline
java --enable-native-access=ALL-UNNAMED -jar inspecto\target\file-processor-*.jar `
     inspecto\config\voucher\voucher_unknown_pipeline.toon
# convenience wrappers (sandbox root):  bash run-voucher.sh  /  bash run-adjustment.sh

# Long-running control plane + operator UI (ControlApi, default :8080)
$env:CONTROL_TOKEN="secret"; .\file-processor-deploy\serve.bat   # then http://localhost:8080/

# Pre-ETL utility CLI (com.gamma.util.MainApp)
#   commands: search, copy, copy-tars, extract, backup, prepare-inbox,
#             create-schema, move-by-date, extract-unknown, extract-move, help
java --enable-native-access=ALL-UNNAMED -cp inspecto\target\file-processor-*.jar `
     com.gamma.util.MainApp help
```

---

## UI (Angular SPA — inspecto-ui/, NOT in the Maven reactor)

```powershell
cd inspecto-ui
npm ci
npm start        # dev serve on :4204
npm run build    # dist/ (bundled into deploy zip's ./ui by package.ps1)
```

---

## Graph (graphify)

```bash
graphify query "<question>"     # scoped subgraph — prefer over grep/GRAPH_REPORT
graphify explain "<concept>"
graphify path "<A>" "<B>"
graphify update .               # refresh after code changes (AST-only, no API cost)
```

---

## Claude Code (team usage)

This checkout is a **shared team sandbox** (shift work, one account). All Claude Code setup lives
in repo `.claude/` — skills, agents, hooks, settings. Nothing project-related goes in the user profile.

- **Session-per-shift:** start each shift with a fresh session (context rehydrates from
  `SESSION_STATUS.local.md` + `.claude/sessions/snapshot.md`, auto-written on every stop); end
  your shift with `/handoff`, then close the session. Avoid marathon sessions — compaction
  loses file state and degrades quality.
- **Model routing:** prefer `/model opusplan` (Opus plans, Sonnet executes) for build work; top
  models + "think hard" for architecture/design only. Delegate searches to `Explore` /
  `backend-explorer` and builds to `verify-runner` — they run on cheaper models and keep logs
  out of the main context.
- **Macro words → skills:** `GAUNTLET` (build-verify) · `SMOKE` (/smoke) · `ENDPOINT` (/endpoint) ·
  `HANDOFF` (/handoff) · SHIP-IT = release-workflow skill.

---

**Last Updated**: 2026-07-02
