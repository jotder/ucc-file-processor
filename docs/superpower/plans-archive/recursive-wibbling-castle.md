# Handover — Inspecto Data Acquisition (2026-06-15)

## Context
The user asked to **stop before the next feature** (true row-level DB watermarking) and produce a
**user handover** with "all pending and done updated." This is a handoff so work can resume cleanly
(possibly in another session/by another person). Nothing new should be built; the deliverable is an
accurate, persisted snapshot of state + a clear pickup point.

The persisted artifacts (`SESSION_STATUS.local.md`, the task list) are out of date — `SESSION_STATUS.local.md`
still reads `57690c1` / 2026-06-14, before Phase D/E/F, OI, and this whole session. The task tracker shows 45
completed items from prior phases and nothing pending. Both need refreshing.

> Plan-mode note: I can only edit this plan file now. The actions in **"Persistence actions on approval"**
> are what I'll execute once you approve — they are the real "keep pending/done updated."

---

## Repo state (verified, read-only)
- Branch **`4.x`**, HEAD **`782a8ec`**, **+3 ahead of `origin/4.x`** (which is at `cd1423f`). **UNPUSHED.**
- Reactor **green**: core 652 / agent 157 / hosted 4 / connectors 29.
- Working tree: only non-mine dirty file is `.gitignore` (untouched); untracked non-mine: `.claudeignore`,
  `CLAUDE.md`, `docs/INDEX.md`, `graphify-out/`, `test-run.ps1`. `inspecto/pom.xml` never staged (standing rule).
- Toolchain: JDK `C:\.jdks\openjdk-26.0.1`, Maven `C:\maven\apache-maven-3.9.16\bin\mvn.cmd`; verify with
  `JAVA_HOME=… mvn -o clean test` (offline). Connectors consume the core artifact, so a connectors-only run
  needs `-am` or a prior `-pl inspecto install`.

## Done this session (all committed; #1 already pushed, #2–#4 unpushed)
1. **Pushed DB-export** `cd1423f` to `origin/4.x` (session opened with "push").
2. **C4 incremental high-watermark** — `51a3aa4`. `source.incremental.watermark: last_modified`; watermark
   *derived* from the ledger (`AcquisitionLedger.highWatermark` = `MAX(last_modified)` per source);
   `SourceProcessor.watermarkFilter` drops mtime `< watermark` before fetch; needs a content-based
   `duplicate.mode`; metric `inspecto_watermark_skipped_total`. Tests: `AcquisitionLedgerWatermarkTest` + 3 in
   `SourceConfigTest`. (file-level only; **row-level is the deferred item below**.)
3. **Connector hardening** — `b609052`. **FTPS** (`connector: ftps` / `options.tls=explicit|implicit`, `PBSZ 0`+
   `PROT P`, `tls_trust=all`) + **strict SSH host-key pinning** (`HostKeyPolicy` from
   `options.host_key`/`known_hosts`/`strict_host_key`; default = legacy accept-on-connect). No new deps. Tests:
   `FtpsConnectorTest` (embedded FtpServer+TLS, throwaway `*.jks`) + 4 host-key tests in `SftpConnectorTest`.
4. **FTP/FTPS through an SSH bastion** — `782a8ec`. `FtpConnector` honours `tunnel:`; `SshTunnel.addForward`
   (multi-forward) carries the passive data range; passive NAT-workaround → loopback; forced passive;
   `options.passive_ports` for the range. Tests: `FtpTunnelConnectorTest` (MINA bastion → FtpServer) + parser.

Net result: **SFTP, FTP, FTPS, and DB-export can all traverse a bastion**, with content dedup + the file-level
incremental watermark + retry/circuit-breaker/post-actions/parallel/rate-limit from earlier phases.

## Pending / next
1. **Push** `51a3aa4..782a8ec` → `origin/4.x` (3 commits). *Awaiting an explicit "push" — standing rule.*
2. **Row-level DB watermarking** (resumable, backfill-safe) — the interrupted next feature. Intended design below.
3. Standing future (same SPI, non-disruptive): object storage (S3/GCS/Azure/MinIO), NFS/SMB/CIFS, etag/version
   watermark dimensions for file connectors.
4. **Known test limitation** (documented, accepted): the FTP passive-range *forward* can't be integration-tested
   in-process (forwarder vs. server fight for the same loopback port); covered by code review + the parser unit
   test; control-tunnel path + flow are integration-tested.

## Row-level DB watermarking — intended approach (for whoever resumes; not yet built)
Goal: `DbExportConnector` exports only rows newer than the last successful run, resumably and without gaps —
distinct from the file-level C4 watermark (which can't help DB, since the export file's mtime is always "now").
- **Config** (`*_connection.toon` `options`): `watermark_column` (e.g. `updated_at`/`id`) + optional
  `watermark_initial` (lower bound / backfill start). Query carries a `:watermark` bind/token, e.g.
  `SELECT * FROM cdr WHERE updated_at > :watermark ORDER BY updated_at`.
- **Persist** the last max(`watermark_column`) per source in a durable store (reuse the `AcquisitionLedger`
  DuckDB file with a reserved row/table, mirroring how the file watermark is derived — single-writer already).
- **Advance** only *after* the batch commits (exactly-once-ish; resumable across a crash). On start, read the
  stored value (or `watermark_initial`), bind it, export, then store the new max.
- **Backfill-safe / gap-free:** strictly-`>` increment over an **append-only / monotonic** column (prefer an
  ingestion timestamp or sequence over event-time, else late back-dated rows are missed — document this).
- **Open questions to settle when picked up:** exact persistence shape (ledger row vs. small new state store);
  bind-vs-token substitution in the SQL; how this composes with the existing date-templating; types/precision of
  the watermark column. → A fresh, full plan should be written at pickup; this is the brief, not the design.

## How to resume / verify
- Verify: `JAVA_HOME="C:\.jdks\openjdk-26.0.1" "C:\maven\apache-maven-3.9.16\bin\mvn.cmd" -o clean test`
  (expect core 652 / agent 157 / hosted 4 / connectors 29). Connector docs:
  `docs/integrations.md` (SFTP/FTP/FTPS/db + bastion + host-key + FTPS), spec
  `docs/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md` (as-built log).
- After code changes: `graphify update .` (graph is current as of this handover).

## Standing guardrails (keep)
- **Never stage `inspecto/pom.xml`.** **No commit/push without an explicit ask.** Keep core lean (network deps
  isolated in `inspecto-connectors`). Never commit `SESSION_STATUS.local.md`/`*.local.md` or `.vscode/`.
- Secrets are references only (`${ENV:…}`/`${SYS:…}`). Full reactor green before committing.

---

## Persistence actions on approval (the "keep pending/done updated" deliverable)
1. **Rewrite `SESSION_STATUS.local.md`** (gitignored, never committed) to the current state above — replacing the
   stale `57690c1`/2026-06-14 content: repo state, the 4 done items, the pending list, the row-level watermark
   brief, verify steps, guardrails.
2. **Update the task list** (TaskCreate/TaskUpdate): add this session's 4 features as **completed**, and add
   **pending** items — "Push 51a3aa4..782a8ec to origin/4.x" and "Row-level DB watermarking (resumable,
   backfill-safe)". (The 45 prior completed tasks stay as history.)
3. **MEMORY.md** is already current for the 3 unpushed commits (updated as the work landed); add a one-line
   **"Next up: row-level DB watermarking"** pointer under Current state so the next session sees the pickup point.

No code changes. No push (pending an explicit "push").
