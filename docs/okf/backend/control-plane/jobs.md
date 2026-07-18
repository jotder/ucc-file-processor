---
type: Concept
title: Jobs & Scheduling
description: JobService — cron, event-triggered, and manual jobs, with an off-bus virtual-thread handoff.
resource: inspecto/src/main/java/com/gamma/job/JobService.java
tags: [control-plane, jobs, scheduling, cron, triggers, async-runs]
timestamp: 2026-07-07T00:00:00Z
---

# Jobs & Scheduling

`JobService` (`inspecto/src/main/java/com/gamma/job/JobService.java`) hosts a registry of jobs and a
virtual-thread `workers` executor. Three trigger modes:

* **Cron** — jobs with a `cron` field are armed on the shared `Scheduler`.
* **Event** — jobs with `on_pipeline` subscribe to the `BatchEventBus`; `onBatchEvent` matches a `SUCCESS`
  status + pipeline name, then `submit()`s. This is the **deadlock-safe** path — `submit` hands work to
  `workers` and returns immediately, so the synchronous [event bus](events-metrics.md) never holds
  `ingestLock` across a new run.
* **Manual** — `POST /jobs/{name}/trigger`. The legacy unversioned call stays **synchronous and unchanged**;
  the same route under `/api/v1` is **async** (W5): it returns `202` + `{runId, …}` + a `Location` header,
  the caller polls `GET /jobs/runs/{runId}`, and an `Idempotency-Key` header replays the cached response on
  retry. Pipeline triggers gained the identical async contract in W5b (poll `GET /runs/runs/{runId}`).

`submit()` binds the `space` MDC (for non-default spaces — see [multi-space](multi-space.md)) and runs on a
virtual thread. `runJob()` uses `runner.runExclusiveOrSkip(name, …)` for a non-overlap guarantee (a job
already in flight records `SKIPPED`); different jobs run in parallel. On startup, `catchUpMissedFires()`
replays a single missed cron fire for `catch_up: true` jobs from the durable `jobs_runs.csv` ledger.

`JobType` includes `ENRICH`, `REPORT`, `MAINTENANCE`, and **`PIPELINE`** (authored-Pipeline execution — see
[pipeline live execution](../pipeline-graph/live-execution.md)).

## The Job Framework (P0–P3, shipped 2026-07-09; `feat!` → 5.0)

Design of record (all phases + resolved decisions + TOON config gallery):
[`job-framework-design.md`](../../../archived-documents/plans-archive/job-framework-design.md). The durable model:

* **Job Types as plugins** — `JobTypeProvider`/`JobTypeDescriptor` (+`@JobTypeMeta`) discovered via
  `ServiceLoader`; type ids are **open strings** (not the enum). Jobs implement `run(JobContext)`; the
  context exposes the Run Log, `SignalEmitter`, `ArtifactRecorder`, and host services (data dirs, DuckDB,
  `SecretResolver`, `ViewStore`). Descriptors (`GET /jobs/types/{id}`: config schema + `ParameterDecl`s +
  emitted signal types) drive the UI's generated authoring forms.
* **Parameters** — the `ParameterResolver` resolves each declared parameter first-hit-wins:
  trigger `args` → signal `bind` (`$signal.<field>`) → job config `params:` → deduced `$`-context →
  default; an unresolved `required` parameter fails the Run fast in state **REJECTED** (fail-closed, before
  user code). `$`-context includes `$today`/`$day(-n)`, `$run.*`, `$job.last_success_time` (the natural
  incremental watermark), `$signal.*`, and `$upstream(<job>).artifact(<name>).<attr>`. Three placeholder
  namespaces never conflate: `$name` (run time) · `${param}` in `*_job_template.toon` (authoring time) ·
  `${ENV:KEY}` (config-load-time secret, never logged).
* **Signals** — one ledger (`Signal` envelope: ULID, dotted type, source Ref, correlationId, severity,
  payload) persisted through the `EventLog` seam; the framework emits `job.run.started/completed/failed/
  rejected` for every Run. Triggers v2: `on_signal:` + `when:` guard + `bind:` — Job→Job composition is
  signal chaining (chains visible via `correlationId`); a Signal announces, never decides. Read view
  `GET /signals` (`SignalRoutes` → the static `Signals.query` over the shared `EventStore`, no service
  object): filters `type` (exact or `prefix.*` glob, applied in Java), plus in-store `since`/`until`
  (epoch-milli bounds), `severity` (a min floor mapped onto the event-level ladder), `correlationId` and
  `limit`. Not a duplicate of `/events` — only here do the dotted type / severity / payload decode.
* **Run Log & Run Artifacts** — per-Run structured events, plus artifacts (`dataset`/`file` +
  `ResultSetMeta`) in `job_run_artifacts` beside `DbJobRunStore`; queryable via
  `GET /jobs/{name}/runs/{runId}/artifacts` / `/jobs/{name}/artifacts/latest` and `$upstream(...)`. A
  `file`-kind artifact's bytes download from the sibling `GET /jobs/{name}/runs/{runId}/artifacts/{artifact}/content`
  (attachment, content-type inferred from the filename; 404 when unknown, not a file, or cleaned up). Report
  Jobs record their delivered `out_dir` file as a `report` artifact, so a scheduled report is downloadable.
* **Job Packs** — hot-deployable jars in `-Djobs.packs.dir` (absent ⇒ feature off, fail-closed); watched
  with a settle delay, each pack in its own parent-first `URLClassLoader` with shaded deps.
  `GET /jobs/packs`, `POST /jobs/packs/rescan`. Deferred: in-flight-Run quiesce on pack swap.
* **`sql.template`** — the built-in templated-SQL Job Type and first real artifact producer; its
  parameters are scanned from the SQL itself.

## Maintenance jobs (MNT, shipped 2026-07-12)

System maintenance is **tasks on the `maintenance` job type, never shell scripts or OS cron**. Task library:
`cleanup` (retention knobs `max_count`/`max_size`/`archive_dir`/`min_keep` — the newest N are never retired),
`ledger_prune`, `runlog_prune` (`retention_days` required — deliberate forgetting), `db_maintenance`
(CHECKPOINT/VACUUM over the live stores via host seams), `storage_report`, `scheduler_audit`,
`metadata_validate` (broken refs / duplicates / missing physical data), `file_repository_audit`, `backup`
(timestamped zip + SHA-256 sidecar manifest via `Checksums`) / `backup_verify` (archive hash first,
fail-closed) / `restore` (manifest validation before any write, zip-slip jail, conflict preview; archive-based,
*not* bundle import — it covers the whole config tree). Findings emit `maintenance.*` signals for Alert Rules.

* **Dry run (MNT-1)** — `POST /jobs/{name}/trigger?dryRun=true` (v1 202 body echoes it); `JobContext.dryRun()`;
  tasks with no preview do nothing on a dry run (fail-closed).
* **Nightly chain (MNT-13)** — pure config: each link `on_signal: job.run.completed` +
  `when: "$signal.job == <prev> && $signal.outcome == SUCCESS"` (halt-on-failure by guard); shipped as a
  parameterized Job Template + `spaces/demo` instance.
* **`/health/details` (MNT-15)** — per-subsystem UP/DOWN/NOT_CONFIGURED (`HealthDetails`); overall DOWN iff any
  subsystem DOWN; auth-gated, deliberately not on the public-path allowlist. The bare `/health` probe stays
  public for the connectivity banner.
* **Deferred:** the Archived-Incident sweep (MNT-14) is blocked until the backend Incident workflow gains the
  Identified→Archived lifecycle + an `ObjectStore` delete API. Runbook: `docs/ops/backup-restore-runbook.md`.
