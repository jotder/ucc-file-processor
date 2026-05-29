# v2.x Implementation Plan — finalized task sequence

The strategic "what/why" lives in [v2-backlog.md](v2-backlog.md). This is the
"how/in-what-order" — a finalized, sequenced task list. Each milestone is
independently releasable as a minor version on the `2.x` branch.

## Decisions locked (defaults — revisable, but we build on these)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D-a | Enrichment engine home | **Same repo**, new package `com.gamma.enrich` + new entry point | Reuses `DuckDbUtil`, `PartitionWriter`, `PartitionDef`, config loading, audit/lineage |
| D-b | Reference-data source (v1) | **DuckDB-readable files (Parquet/CSV) + DuckLake** | Zero new deps; Postgres dims via DuckDB scanner later |
| D-c | Enrichment v1 scope | **Lineage-driven incremental (event) + scheduled window recompute**, idempotent, Parquet out | Matches the agreed execution model; full re-enrich is just "all partitions" |
| D-d | Embedded HTTP server | **JDK `HttpServer`** (revised from Javalin at M3) | Zero new deps — no Jetty/Kotlin in the fat-JAR; the plan-sanctioned fallback proved more than enough for a ~12-route JSON control plane. Jackson (already a dep) handles JSON. |
| D-e | Status DB engine (last milestone) | **Postgres** | Already in the stack via DuckLake; clean multi-writer; real SQL for API |
| D-f | Release cadence | **One minor release per milestone** on `2.x` (tag + GH release) | Ships value incrementally; each milestone is green & usable |

## Milestones & tasks

Build order is top-to-bottom. Versions are nominal targets.

### M0 — Enrichment core spike  → v2.1.0  ✅ (flagship, de-risked)

Prove the columnar-incremental transform on real Stage-1 output, CLI-first. **Done**
(`2.1.0-SNAPSHOT`): `com.gamma.enrich` — `EnrichmentConfig` (toon loader),
`EnrichmentEngine` (read_parquet/read_csv over partitions → transform → idempotent
`PartitionWriter` write), `EnrichmentProcessor` (CLI, full + `--partitions`
incremental). 9 tests: full/incremental/idempotent recompute, reference join, config
parsing. `PartitionWriter` gained an exclude-columns overload so non-`__src_id` tables
(enrichment output) write cleanly. References use a *map* form (`name → {path,
format}`) so colon-bearing paths parse. **Run-level audit/lineage for enrichment is
deferred to M2** (it becomes a managed concern once orchestrated).

- **T0.1** Design the enrichment `.toon` schema: inputs (partition glob / DuckLake
  table), reference sources, join/aggregate/derive spec, **output grain &
  partitioning**, and the input→output recompute map.
- **T0.2** `EnrichmentConfig` loader — mirror `PipelineConfig` patterns; reuse
  `ToonHelper`, `Identifiers`, a `ConfigValidator`-style check.
- **T0.3** `EnrichmentEngine` core — given changed partitions *or* an explicit window:
  `read_parquet` the relevant partitions → join refs → aggregate/derive → write via
  `PartitionWriter` (idempotent `OVERWRITE_OR_IGNORE`).
- **T0.4** `EnrichmentProcessor` CLI entry point — run for a window / explicit
  partitions; audit + lineage for the enrichment run.
- **T0.5** Tests: aggregation correctness, idempotent re-run, **incremental scoping**
  (only changed partitions read), reference join, output-grain ≠ input-grain.
- **DoD:** `ura enrich <enrich.toon> [--partitions … | --window …]` produces correct
  reports/KPIs from partitioned output; re-runs are idempotent; full suite green.

### M1 — Service / server mode  → v2.2.0  ✅ (platform linchpin)

Always-on host; the event bus + scheduler the enrichment feature needs. **Done** —
new `com.gamma.service`: `SourceService` (registry of config paths, scheduled poll
cycle, recovery report, graceful shutdown, CLI), `BatchEventBus` (in-process pub/sub),
`Scheduler` (interval, non-overlapping), `StatusStore` + `FileStatusStore` (commit-log
backed seam). The ingest layer emits `BatchEvent`s via a `Consumer` handed to
`SourceProcessor.run(cfg, onCommit)` → `MultiSourceProcessor.runAll(.., onCommit)` →
`BatchAuditWriter` publishes on SUCCESS flush (partitions from lineage). 9 tests.

Scope notes for this cut: scheduler is **interval-based** (calendar/cron for windowed
KPIs lands in M2); **recovery** = startup commit-log visibility (batch atomicity +
marker dedup already make interrupted batches safe to reprocess); **global budget** is
run-level (concurrent sources bounded by the existing `MultiSourceProcessor` semaphore;
each source still bounds its own batches via `processing.threads`). The original
spec text is retained below.

- **T1.1** `StatusStore` abstraction + **file-backed** impl wrapping today's audit
  CSVs / manifests / commit log (read APIs: runs, batches, files, lineage, commits).
- **T1.2** Service skeleton: long-running process, pipeline **registry** (load configs
  from a dir), lifecycle (start/stop/pause), graceful shutdown.
- **T1.3** **Scheduler** (cron-like) for scheduled jobs.
- **T1.4** **Batch-commit event bus** — fire on `CommitLog` append /
  `BatchProcessor.commit` with `(pipeline, batchId, partitions[])` from lineage;
  in-process pub/sub.
- **T1.5** **Recovery on startup** via `CommitLog.committedBatchIds()` — detect/resume
  interrupted batches.
- **T1.6** **Global concurrency budget** across sources + backpressure (the E0
  residual) — a shared governor over the per-source semaphores.
- **DoD:** `ura serve <config-dir>` polls + schedules continuously, emits batch-commit
  events, recovers cleanly after a kill; green.

### M2 — Enrichment orchestration  → v2.3.0  ✅ (flagship complete)

Wired the M0 core to the two triggers on the M1 service. **Done** (released as
`v2.3.0`, the cumulative M0+M1+M2 Stage-2 cut):
new `com.gamma.service.EnrichmentService` is the composition point binding the pure
`com.gamma.enrich` engine to the bus (freshness) and scheduler (completeness).
`EnrichmentConfig` gained an optional `triggers` section (`on_pipeline`,
`schedule_seconds`); `SourceService` hosts a registry of `*_enrich.toon` jobs sharing
its bus + scheduler. 7 new tests (5 orchestration + 2 config), full suite 123 green.

- **T2.1** ✅ Event subscriber: on a batch-commit event, the committed partitions
  (`BatchEvent.partitions()`) become the engine's input filter — only the affected
  output is recomputed (freshness). Heavy DuckDB work is handed to a virtual-thread
  executor so it never blocks the ingest (publishing) thread.
- **T2.2** ✅ Scheduled job: `schedule_seconds` registers an interval recompute of the
  **full** window (completeness), whose idempotent overwrite reconciles late data. (The
  completeness rule here is "recompute the whole window"; finer watermark/closed-window
  scoping is a future refinement — calendar/cron scheduling tracked with M4.)
- **T2.3** ✅ Chains: a successful recompute publishes its own `BatchEvent` (pipeline =
  the job's `name`), so a downstream job with `on_pipeline: <that name>` fires
  automatically. A self-loop guard prevents a job triggering on its own output.
- **T2.4** ✅ Tests: event recomputes only the committed partition; schedule recomputes
  the full window; a chain fires B from A's commit; idempotency holds under an
  event-burst + schedule overlap (per-job lock + `OVERWRITE_OR_IGNORE`); end-to-end a
  real Stage-1 commit through `SourceService` drives enrichment to completion.
- **DoD:** ✅ end-to-end — a Stage-1 batch commit triggers incremental enrichment; the
  schedule finalizes windows; numbers converge; suite green.

Scope notes for this cut: the scheduled completeness path does a **full** recompute
(simplest correct, idempotent) rather than a windowed/watermarked scope; the `Scheduler`
remains **interval-based** (calendar/cron deferred). Enrichment **run-level
audit/lineage** (deferred from M0) is still open — enrichment recomputes log but don't
yet write audit/lineage rows; fold into M4 (Observability) or a dedicated follow-up.

### M3 — Control API  → v2.4.0  ✅

One REST surface (humans now; UI/agent in v3). **Done** (`2.4.0-SNAPSHOT`): new
`com.gamma.control.ControlApi` on the JDK `HttpServer` (zero new deps) + Jackson JSON,
bearer-token auth (open `/health` + `/ready`), virtual-thread executor, tiny regex
router. `StatusStore` grew read methods (`batches`/`files`/`lineage`/`quarantine`)
backed by the run-timestamped audit CSVs + quarantine tree; `SourceService` gained the
control surface (`pipelines()`, `pause`/`resume`, `runPipeline`, `configFor`/`pathFor`,
paused-skip in the poll cycle) and a shared `fromArgs(...)` factory. 7 HTTP integration
tests; full suite 130 green. CLI: `com.gamma.control.ControlApi` runs the service + API
together.

- **T3.1** ✅ Embedded server (JDK `HttpServer`); `/health` + `/ready`; bearer-token
  auth on every other route (`Authorization: Bearer` or `X-Api-Token`; open with a
  warning if no token configured).
- **T3.2** ✅ Endpoints: `GET /pipelines` (list + state); `POST
  /pipelines/{name}/{trigger,pause,resume}`; `GET /pipelines/{name}/{commits,batches,
  files,lineage,quarantine}` (via `StatusStore`); `POST /pipelines/{name}/reprocess`
  (wraps `ReprocessCommand`); `POST /trigger` (run all); `POST /validate` (wraps
  `ConfigValidator`). (Pipeline *CRUD* — create/edit configs over HTTP — deferred; the
  service reads configs from disk and edits are picked up each poll cycle.)
- **T3.3** ✅ Tests: real-HTTP integration over a started API — auth enforcement,
  trigger→audit-query round-trip, pause/resume state, validate, reprocess, 404/405.
- **DoD:** ✅ every CLI operation is reachable over REST; authenticated; green.
  Verified end-to-end via the fat-JAR (`-Dcontrol.port`/`-Dcontrol.token`).

### M4 — Observability  → v2.5.0  ✅

**Done** (`2.5.0-SNAPSHOT`): zero-dep metrics (revises T4.1's Micrometer — same lean-JAR
rationale as D-d). New `com.gamma.metrics.MetricRegistry` (process-wide `global()`,
counters/gauges/histograms, Prometheus text exposition) + `com.gamma.service.MetricsService`
(bus subscriber for eager metrics, scrape-time gauge collectors, structured JSON event
log). `ControlApi` serves `GET /metrics` (open). `BatchEvent` gained `durationMs` +
`rejectedCount` and is now emitted for every terminal batch (SUCCESS + FAILED); enrichment
filters SUCCESS. 6 new tests; full suite 136 green; verified via the fat-JAR scrape.

- **T4.1** ✅ Metrics (zero-dep): `ucc_batches_total{pipeline,status}`,
  `ucc_output_rows_total`, `ucc_rejected_files_total`, `ucc_partitions_written_total`,
  `ucc_batch_duration_seconds` (histogram), `ucc_enrichment_recomputes_total{job,trigger}`
  + `ucc_enrichment_duration_seconds`, `ucc_poll_cycles_total`,
  `ucc_source_run_failures_total`, and scrape-time gauges `ucc_active_runs`,
  `ucc_committed_batches`, `ucc_quarantine_files`, `ucc_inbox_oldest_seconds` (lag),
  `ucc_paused`. "In-flight" is captured at run granularity (`ucc_active_runs`).
- **T4.2** ✅ `GET /metrics` on the API host (Prometheus text format, open — scrapers
  don't carry tokens); structured JSON event log via the `ucc.events` logger, one line
  per batch carrying `pipeline`/`batch_id`/`status`/`rows`/`partitions`/`rejected`/`duration_ms`.
- **DoD:** ✅ metrics scrapeable; batch events correlatable by id; key SLOs (throughput,
  latency, error/quarantine rate, lag, recomputes) visible; green.

Scope note: chose a **hand-rolled** registry over Micrometer (zero new deps, consistent
with the M3 HTTP choice); swappable later if dimensional backends (OTLP/StatsD) are
needed. The dedicated enrichment **run-level audit/lineage** deferred since M0 remains
open — surfaced now via metrics + the event log, but not yet persisted as audit rows.

### M5 — Status store in a database  → v2.6.0  (last)

- **T5.1** Postgres-backed `StatusStore` behind the existing seam; schema
  (pipelines/runs/batches/files/lineage/quarantine/commits).
- **T5.2** Config selects file vs DB backing; optional migrate/backfill.
- **T5.3** API + observability query the DB.
- **DoD:** status durable & queryable in Postgres; file-backed still works; green.

## Cross-cutting (applied each milestone)

- Keep the **full suite green** and add tests per task; bump the minor version, tag,
  and cut a GH release at each milestone DoD.
- Preserve the **M..N multiplexer non-goals** — enrichment stays a separate engine.
- Maintain the `@PublicApi` surface; mark new public types (e.g. `EnrichmentConfig`,
  the service entry points) per [api-stability.md](api-stability.md).

## Deferred to v3.0+ (unchanged)

Embedded AI operator agent · Web UI · object storage (S3/GCS/Azure) ·
distributed/multi-node execution.

## Starting point

**M0 / T0.1** — design the enrichment `.toon` schema and the input→output recompute
map. That single artifact unblocks the whole core spike.
