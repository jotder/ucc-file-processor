# System Maintenance Module — MoSCoW & Phasing Plan

**Status:** DRAFT — awaiting sign-off · **Created:** 2026-07-12
**Source input:** generic "System Maintenance Module" MoSCoW (product-supplied), adapted to Inspecto.
**Companion docs:** `docs/job-framework-design.md` (the substrate), `docs/GLOSSARY.md` (binding vocabulary).

---

## 1. Philosophy (kept from the generic module, restated in Inspecto terms)

Maintenance activities are **Jobs** on the existing Job Framework — never shell scripts, OS cron,
or hard-coded cleanup in services. Every maintenance activity must be configurable (TOON,
metadata-driven policy), schedulable (platform cron), versioned (ComponentStore/config files),
auditable (Run Log + Run Artifacts), idempotent, previewable (dry run), recoverable, transportable
(bundle/Job Pack), and observable (signals + `inspecto_job_runs`).

This maps 1:1 onto what already shipped: the module is mostly **new tasks on the existing
`maintenance` job type**, plus one small framework addition (dry run), plus a backup job.
We deliberately do NOT build new engines.

## 2. As-built baseline (reuse, don't rebuild)

| Capability | Where | State |
|---|---|---|
| Job SPI, descriptors, authoring UI | `Job.java`, `JobTypeProvider.java`, `JobTypeDescriptor.java`, descriptor-driven UI | ✅ complete |
| `maintenance` job type with task library | `MaintenanceJob.java:43` — `cleanup` (age/glob file delete), `ledger_prune`, `db_maintenance` (DuckDB CHECKPOINT/VACUUM on acquisition ledger), `compact`, `materialize`, `heartbeat` | ✅ exists — extend with new tasks |
| Cron scheduling + eager validation | `Scheduler.java`, `CronExpression.java`; parse-at-load in `JobConfig.java:138` | ✅ |
| Composition (on-signal triggers, `when` guard, `$upstream`) | `JobConfig.java:40`, `WhenGuard.java` | ✅ — nightly chained pipeline is config, not code |
| Run Log / audit | `RunLogStore.java` (JSONL under `<auditDir>/runlog/`), optional DuckDB projection `inspecto_job_runs` (`DbJobRunStore.java`) | ✅ — audit comes free |
| Run Artifacts | `RunArtifactStore.java` (JSONL, `$upstream` consumer) | ✅ — carries before/after stats |
| Component export/preview/import | `BundleRoutes.java` — dependency-ordered upsert, drift/conflict classification, `ContentHash` SHA-256 | ✅ for 8 component kinds — the restore-preview primitive |
| Component version history | `ComponentStore` `.history/` keep-last-N + restore routes | ✅ |
| Space storage contract | `SpaceLayoutContract.java` — `config/ data/ audit/ duckdb/ flows/ views/` | ✅ — defines what a backup/report walks |
| Rejected-file quarantine | `QuarantineManager.java` | ✅ — a cleanup target |
| Job templates | `JobTemplate.java` (`*_job_template.toon`) | ✅ — ship maintenance profiles as templates |

**Confirmed gaps (the actual work):** no dry-run concept in the Job Framework; no retention/purge
of Run Log, Run Artifacts, or `inspecto_job_runs`; no space/DB backup-restore (bundle covers 8
metadata kinds only — not connection/pipeline/job/views, not data, not DuckDB); no cross-component
integrity validation (orphans / broken refs / duplicates — `ConfigSafetyValidator` is numeric-bounds
only); `/health` is a liveness stub (`ControlApi.java:353`); no storage/disk-usage reporting;
agent sessions are in-memory (`InspectoIntelligenceAgent.java:41`) — nothing to retain yet.

## 3. Adapted MoSCoW

Vocabulary per `docs/GLOSSARY.md`: *Pipeline* (not flow), *Dataset*, *Incident* (lifecycle
Identified/Diagnosing/Resolved/Archived), *Alert Rule / Decision Rule / Expectation* (never bare
"rule"), *Source*, *Space*.

### MUST

| # | Item | Inspecto mapping |
|---|---|---|
| MNT-1 | **Dry run, framework-level** | `dryRun` flag on `JobContext` + `POST /jobs/{id}/run?dryRun=true`; every destructive maintenance task honors it: reports affected objects + estimated reclaim, mutates nothing. One small framework change, prerequisite for everything destructive ("Safe by Default"). |
| MNT-2 | **Retention cleanup as metadata-driven tasks** | Extend `MaintenanceJob`: policy = `retention_days` / `max_count` / `max_size` / `archive_instead_of_delete` / dry-run. Targets: Run Log + Run Artifacts + `inspecto_job_runs` (`runlog_prune`, new), signals ledger (`ledger_prune`, exists), temp/export/uploaded files (`cleanup`, exists — add max-count/max-size knobs), quarantined/rejected files, report/query caches where they exist on disk. |
| MNT-3 | **Storage usage reporting** | New `storage_report` task: walk the `SpaceLayoutContract` axes + DuckDB file sizes; largest consumers; emit Run Artifact (queryable) + signal on threshold breach (low-disk warning → existing Alert Rule channels, INC-3). Each run appends a sample — this is the raw series for later trend analysis. |
| MNT-4 | **Scheduler hygiene** | New `scheduler_audit` task: disabled jobs, duplicate definitions, orphan triggers (`onSignal` with no producer, `onPipeline` naming a missing pipeline). Cron validation already eager at load — reuse, don't re-implement. |
| MNT-5 | **Config/metadata backup + verify** | `backup` task: archive `<space>/config/` (+ optionally `duckdb/` after CHECKPOINT) to timestamped zip with SHA-256 manifest (reuse `ContentHash`); `backup_verify` task: checksum + archive-open check. Backup dir governed by an MNT-2 retention policy. Scheduled + manual (both free via Job Framework). |
| MNT-6 | **Restore with preview + conflict detection** | Config restore = existing `BundleRoutes` preview/import path fed from a backup archive; space-level restore-into-new-environment = documented procedure: new space + unpack archive (restore preview = bundle preview). Rollback = the backup taken immediately before restore. |
| MNT-7 | **Metadata integrity validation** | New `metadata_validate` task: orphan components, broken references (widget→dataset/query, dashboard→widget, dataset→connection, job→pipeline/signal producer), duplicate definitions, invalid config. Read-only; findings → Run Artifact + signal → Alert Rule. |
| MNT-8 | **Audit of every maintenance run** | Already free: Run Log records executor/trigger/duration/outcome; tasks must write affected-objects + before/after stats into Run Artifacts. Enforced as a task-authoring rule, not new infra. |
| MNT-9 | **DuckDB maintenance behind the task abstraction** | Extend existing `db_maintenance` beyond the acquisition ledger to all per-space stores (`jobs_report.duckdb`, `provenance.duckdb`): CHECKPOINT, growth stats. Postgres variants (DAT-6 stores) behind the same task id, dialect-aware. |

### SHOULD

| # | Item | Inspecto mapping |
|---|---|---|
| MNT-10 | Backup catalog | Manifest entries land in a Dataset (`maintenance_backups`) → searchable in existing BI/data-table surfaces; no bespoke repository. |
| MNT-11 | Maintenance dashboard | A pane over data that MUST items already produce: backup status, storage utilization, failed runs (`inspecto_job_runs`), retention violations. UI-only once Phase 1–2 data exists. |
| MNT-12 | File repository maintenance | Tasks: orphan data files (no owning Dataset), missing physical files, partial-upload/temp cleanup, checksum mismatch. |
| MNT-13 | Composed nightly maintenance pipeline | Pure config: cleanup → db_maintenance → backup → backup_verify → report, chained via on-signal composition; shipped as a Job Template + `spaces/demo` example. |
| MNT-14 | Archive manager (archive-instead-of-delete) | `archive_instead_of_delete` policy flag moves to an `archive/` axis instead of deleting; applies to Archived Incidents, old run history, generated reports. |
| MNT-15 | Health monitoring depth | `/health/details`: per-subsystem checks — DuckDB open/query, scheduler tick freshness, write-root, agent service reachability. Health *score* stays COULD. |
| MNT-16 | Config validation pre-deployment | Run MNT-7 `metadata_validate` as a bundle-import pre-check (hook into `BundleRoutes` preview). |

### COULD (deferred, in priority order)

Growth-trend analysis + archive recommendations (needs MNT-3 sample history first) · space-to-space
comparison (the "environment comparison" analogue — Inspecto has Spaces, not DEV/TEST/UAT/PROD
tiers; builds on bundle preview drift classification) · backup encryption + compression tuning ·
incremental/differential backup · maintenance profile templates (Dev/Prod presets as Job Templates)
· health score · predictive maintenance · AI recommendations (AGT-5 P1+ territory) · self-healing ·
backup deduplication · agent-session retention (blocked: sessions not persisted yet — file as a
dependency on AGT-5, not this module).

**Demotions from the generic MUST list, with rationale:** incremental/differential/point-in-time
backup → COULD (Inspecto is a file-based per-space appliance; full config+DuckDB snapshot is cheap,
correct, and restorable — incremental adds catalog complexity for little gain at current data
sizes). Backup encryption → COULD (offline/on-prem posture; filesystem-level encryption is the
operator's call). Resume-interrupted-backup → dropped (snapshots are small; re-run is the resume).
GIS tiles / report / widget / query / AI caches → covered generically by MNT-2 where they exist on
disk; no per-cache bespoke logic.

### WON'T (this release) — unchanged from the generic list, all already platform policy

Shell scripts or OS cron as operational mechanism · direct DB manipulation outside maintenance
jobs · hard-coded cleanup in services · manual filesystem deletion of managed artifacts ·
maintenance without audit · backup/restore bypassing dependency validation · restarts as default
remedy.

## 4. Phases

Sequenced by dependency weight: Phase 1 is almost entirely new tasks on an existing job type
(low-hanging, no new infra); Phase 2 adds the one new artifact family (backups) plus read-only
validators; Phase 3 is UI + composition + depth, all consuming Phase 1–2 output.

### Phase 1 — Safety + Retention + Observation (low-hanging, framework-consumer only) — ✅ SHIPPED 2026-07-12

| Step | Verify | Status |
|---|---|---|
| 1. MNT-1 dry-run flag on `JobContext` + run-route param; `cleanup`/`ledger_prune` honor it | test: dry run deletes nothing, reports counts; real run matches the dry-run estimate | ✅ `JobContext.dryRun()` default + `RunContext`/`Firing` carry + `POST /jobs/{name}/trigger?dryRun=true` (v1 202 body echoes `dryRun`); manual fires only; tasks with no preview do nothing on a dry run (fail-closed). Ledger SPI gained `countPrunable` for the estimate. |
| 2. MNT-2 `runlog_prune` task (Run Log JSONL + artifacts JSONL + `inspecto_job_runs` rows) | test: entries older than `retention_days` gone, newer intact; dry run intact | ✅ `retention_days` required (deliberate forgetting), optional `max_count` file cap; `DbJobRunStore.prune/countPrunable` |
| 3. MNT-2 policy knobs (`max_count`/`max_size`/`archive_instead_of_delete`) on existing `cleanup` | unit tests per knob | ✅ limits combine as OR; archiving requires explicit `archive_dir` (relative structure preserved, archive never re-walked); quarantine/temp/export dirs are plain `dir:` targets |
| 4. MNT-3 `storage_report` task (sizes, largest consumers, threshold signal) | artifact row per axis; breach emits signal consumable by an Alert Rule | ✅ per-axis Run Artifacts, top-N consumers in the Run Log, `maintenance.storage.threshold` WARNING over `warn_bytes` |
| 5. MNT-4 `scheduler_audit` task | fixtures: disabled/duplicate/orphan-trigger jobs each detected | ✅ disabled / duplicate names / identical specs / orphan `on_pipeline` (host-wired pipeline names via `JobService.knownPipelines`, skips when unwired — never guesses) / undeclared `on_signal` producers; findings → Run Log + one `maintenance.scheduler.findings` signal |
| 6. Descriptor updates → tasks appear in the jobs authoring UI | forms render from descriptors | ✅ maintenance descriptor lists all tasks + new params (`glob`/`max_count`/`max_size`/`archive_*`/`warn_bytes`/`source`) and declares the two `maintenance.*` signal emits |

Exit met: reactor green (see `SESSION_STATUS.local.md` for the baseline); every task runnable
manually + on cron + dry-run; `spaces/demo` gained `jobs/runlog_retention_job.toon` (cataloged in
`spaces/demo/config/README.md`). Deliberate scope notes: dry run is manual-trigger-only (cron/event/
signal fires are always real); `max_count` does not apply to `inspecto_job_runs` rows (retention
governs those); agent-session retention still blocked on AGT-5 persistence.

### Phase 2 — Backup/Restore + Integrity — ✅ SHIPPED 2026-07-12

| Step | Verify | Status |
|---|---|---|
| 1. MNT-5 `backup` task (timestamped zip + SHA-256 manifest) | archive restorable; manifest hashes verifiable | ✅ `BackupTask.backup`: zip + inner manifest + **sidecar** `<archive>.manifest.json` (per-entry SHA-256 via `Checksums` + archive hash); dry-run previews count/bytes; `dir`/`backup_dir`/`prefix` params. DuckDB inclusion = run `db_maintenance` (CHECKPOINT) first, then back up the space root — runbook documents the order |
| 2. MNT-5 `backup_verify` + MNT-10 catalog Dataset | corrupt a byte → verify fails + signal; catalog queryable | ✅ archive hash first (fail-closed), then every entry hash; failure → FAILED Run + `maintenance.backup.verify_failed` CRITICAL. Catalog: one single-row Parquet per backup into `<dataDir>/maintenance_backups/` (glob-union rows, the materialize idiom) + idempotent `maintenance_backups` dataset component |
| 3. MNT-6 restore + document restore-into-new-space | e2e round trip | ✅ `restore` task: manifest+hash validation before any write, zip-slip jail, conflict preview on dry run, blocks on conflicts unless `overwrite: true`, post-extraction re-hash. Round-trip + conflict + overwrite covered in tests. Runbook: `docs/ops/backup-restore-runbook.md` (incl. new-space procedure + rollback). NOTE: restore is archive-based, not routed through bundle import — bundle covers 8 component kinds only; the zip covers the whole config tree |
| 4. MNT-7 `metadata_validate` task | fixtures per finding class; clean space zero | ✅ `MetadataValidateTask`: broken refs (widget→dataset/query, dashboard tile→widget — grounded in the real registry shapes), duplicate definitions (content-identical apart from name), missing physical data (dataset `physicalRef` vs data root). Findings → Run Log + `maintenance.metadata.findings`. Ref checks for other kinds deferred until their shapes are declared |
| 5. MNT-9 `db_maintenance` all stores | CHECKPOINT beyond the ledger | ✅ `DbJobRunStore.maintenance()` + `DbProvenanceStore.maintenance()` (CHECKPOINT/VACUUM best-effort over the live single-writer connections), invoked via the host seams |
| 6. MNT-2 backup-dir retention | never the last N | ✅ `min_keep` knob on `cleanup` (MNT-2c): the newest N files are never retired, whatever retention/max limits say |

Exit met: demo ships a nightly `config_backup` job chained to `backup_verify` via
`on_signal: maintenance.backup.completed` (a first taste of the Phase-3 composed pipeline);
integrity + verify failures are alertable signals.

### Phase 3 — Composition + Surface + Depth — ✅ SHIPPED 2026-07-12 (MNT-14 deferred, see below)

| Step | Verify | Status |
|---|---|---|
| 1. MNT-13 nightly composed maintenance pipeline | chain fires end-to-end via signals; failure mid-chain halts | ✅ demo chain `runlog_retention` (cron head) → `db_maintenance` → `config_backup` → `backup_verify` → `maintenance_report`, each link `on_signal: job.run.completed` + `when: "$signal.job == <prev> && $signal.outcome == SUCCESS"` (halt-on-failure is the guard; a thrown failure additionally emits `job.run.failed` for Alert Rules). `chained_backup_job_template.toon` = the parameterized chain-step Job Template (`after`/`dir`/`backup_dir`/`prefix`); `config_backup` is its instance. HTTP test proves cascade + halt |
| 2. MNT-11 Maintenance Overview pane | UI DoD; real endpoints | ✅ `modules/admin/maintenance/` + new top-level **System Maintenance** nav group (§5 IA — sibling of Operations): health subsystems (`/health/details`), maintenance job fleet + last backup (from `/jobs` + `/jobs/{name}/artifacts/latest`), failed maintenance runs (`/jobs/runs`, degrades to a note when the projection is off), storage axes (latest `storage_report` artifacts). Mock: `health.handler` (details only — the bare `/health` probe deliberately stays real for the connectivity banner) + artifacts/latest in `jobs.handler` |
| 3. MNT-15 `/health/details` | subsystems togglable to DOWN in test | ✅ `HealthDetails` (control): configStore/dataStore/pipelines/scheduler/jobRunsProjection, each UP/DOWN/NOT_CONFIGURED + detail; overall DOWN iff any subsystem DOWN (unconfigured ≠ failure); auth-gated, NOT on the public-path allowlist (exposes paths). Real-HTTP tests incl. a forced configStore DOWN |
| 4. MNT-14 Archived-Incident sweep | archived items restorable | ⛔ **DEFERRED — blocked**: the Identified/Diagnosing/Resolved/Archived lifecycle is Angular-mock-only; the backend `Workflow` still runs OPEN→…→CLOSED and `ObjectStore` has no delete API (GLOSSARY §13 marks the backend rename pending; gaps doc: `superpower/incidents-mail-ui-design.md` §7). Revisit when the incidents backend workflow ships |
| 5. MNT-12 file-repository audit | fixtures per failure class | ✅ `file_repository_audit` task: unregistered stores (only when a registry is configured — never guessed) + stale partial/temp files (`*.tmp`/`*.compacting`/`.compact-journal` outside the `min_age_days` quiet window); findings → Run Log + `maintenance.filerepo.findings` |
| 6. MNT-16 integrity pre-check on bundle import | broken bundle 422s | ✅ shared rules extracted to `com.gamma.pipeline.ComponentIntegrity`; `BundleRoutes.import` Gate 3 blocks (422, before any write) only findings the import would INTRODUCE — computed over (registry ∪ incoming) minus pre-existing findings, so bundles that resolve themselves pass and old broken refs never block |

Exit met (minus deferred MNT-14): routine maintenance is authorable, schedulable, chainable, and
observable from the UI; COULD items (trends, comparison, profiles) now have their data sources.

## 5. Navigation & IA — Operations vs System Maintenance (two menus)

**Organizing principle.** *Operations* is the work the platform does **for the business**;
*System Maintenance* is the work the platform does **on itself**. Litmus test for placing any
activity: if its outcome matters to a data consumer (a run landed, an incident opened, an alert
fired) → Operations; if its outcome matters only to the platform's own health, tidiness, or
recoverability (a backup verified, storage reclaimed, an orphan component found) → System
Maintenance. Audience and cadence differ the same way: Operations = on-shift operator, daily,
incident-driven; Maintenance = platform administrator, scheduled, policy-driven.

**Menu placement.** New top-level collapsable group `system-maintenance-group` titled **"System
Maintenance"**, sibling of Operations in the platform nav
(`inspecto-ui/src/app/mock-api/common/navigation/data.ts` — Business / Operations / Platform /
System Maintenance / Settings). Platform-nav change, NOT a Menu Builder artifact (the builder
edits custom menus only — menu-builder-plan §5a invariant). Whole group is capability-gateable
later (rbac-groundwork), which is another reason not to bury it inside Platform.

**Operations group — unchanged.** Overview, Processing Status, Events, Audit log, Diagnoses,
Alerts, Incidents, Case Manager stay as-is. Diagnoses remains data/processing diagnosis; platform
self-diagnosis goes to the new group.

**System Maintenance group — items appear with the phase that produces their data (no empty panes):**

| Item | Backs onto | Phase |
|---|---|---|
| Retention & Cleanup | MNT-2 policies per artifact class, dry-run preview, last outcome | 1 |
| Storage | MNT-3 usage by space axis, largest consumers (trends later) | 1 |
| Scheduler Hygiene | MNT-4 audit findings (disabled/duplicate/orphan-trigger) | 1 |
| Backup & Restore | MNT-5/6/10 catalog Dataset, run/verify, restore preview | 2 |
| Integrity | MNT-7/12 metadata + file-repository findings | 2 |
| Overview | MNT-11 (backup status, storage, retention violations, failed runs) | 3 |
| Health | MNT-15 `/health/details` subsystem view | 3 |
| Maintenance Runs | `inspecto_job_runs` filtered to maintenance jobs | 1 |

("Overview", not "Dashboard" — Dashboard is a Studio/BI concept per GLOSSARY; Operations already
set the "Overview" precedent.)

**Three mechanics keep the two menus coherent on one engine:**

1. **One job engine, two filtered views.** Maintenance jobs run on the same scheduler and Run Log
   as business jobs. Tag them (maintenance task/type ids are already distinct) and filter:
   Operations→Runs and Workbench→Runs show business workloads by default; System
   Maintenance→Maintenance Runs shows the maintenance slice. Same store, no duplicate pane code —
   parameterized views.
2. **Authoring stays in Workbench.** Creating/editing ANY job — maintenance included — happens in
   Workbench → Jobs via the descriptor-driven forms. The System Maintenance menu is an
   *observation and action* surface (run now, dry-run, restore, acknowledge finding) that links
   into Workbench for authoring. One concept, one place.
3. **Same object, two verbs is normal.** Rejected/quarantined files: *replaying/remediating* them
   is Operations; *cleaning them per policy* is a Maintenance retention target. The object appears
   in both menus because the activities differ, not because the IA is confused.

## 6. Open questions (product)

1. Backup scope default: config-only vs config+DuckDB? (Data `data/` axis can be huge — proposal: config+duckdb default, data opt-in per policy.)
2. Retention defaults per artifact class (Run Log 90d? signals 30d? backups keep-last-14?) — need operator input; ship conservative defaults, all overridable in TOON.
3. Does MNT-14 archived material need its own retention tier (archive N days, then delete), or is archive terminal for this release?

---

*Maintenance rule: when a phase ships, update §4 status here first, then `docs/BACKLOG.md`.*
