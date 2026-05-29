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
| D-d | Embedded HTTP server | **Javalin** (lightweight, embedded Jetty) | Small, JSON-friendly; keeps the fat-JAR ethos. Fallback: JDK `HttpServer` |
| D-e | Status DB engine (last milestone) | **Postgres** | Already in the stack via DuckLake; clean multi-writer; real SQL for API |
| D-f | Release cadence | **One minor release per milestone** on `2.x` (tag + GH release) | Ships value incrementally; each milestone is green & usable |

## Milestones & tasks

Build order is top-to-bottom. Versions are nominal targets.

### M0 — Enrichment core spike  → v2.1.0  (flagship, de-risk first)

Prove the columnar-incremental transform on real Stage-1 output, CLI-first.

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

### M1 — Service / server mode  → v2.2.0  (platform linchpin)

Always-on host; the event bus + scheduler the enrichment feature needs.

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

### M2 — Enrichment orchestration  → v2.3.0  (flagship complete)

Wire the M0 core to the two triggers on the M1 service.

- **T2.1** Event subscriber: on batch-commit event → read lineage → recompute only the
  affected enrichment outputs (freshness).
- **T2.2** Scheduled enrichment job: recompute closed windows (completeness) per config
  cron + **watermark/completeness rule**; reconcile late data.
- **T2.3** Register enrichment pipelines in the service; support **chains** (enrich
  fires on its upstream's commit event).
- **T2.4** Tests: event recomputes only affected reports; schedule recomputes a window;
  idempotency under event+schedule overlap; late-data reconciliation.
- **DoD:** end-to-end — a Stage-1 batch commit triggers incremental enrichment; the
  schedule finalizes windows; numbers converge; green.

### M3 — Control API  → v2.4.0

One REST surface (humans now; UI/agent in v3).

- **T3.1** Embedded Javalin server; health/readiness; AuthN/Z (API token).
- **T3.2** Endpoints: pipelines list/CRUD; trigger/pause/resume; query
  runs/batches/files/lineage/quarantine (via `StatusStore`); reprocess (wrap
  `ReprocessCommand`); validate config (wrap `ConfigValidator`).
- **T3.3** Tests: endpoint integration over a running service.
- **DoD:** every CLI operation is reachable over REST; authenticated; green.

### M4 — Observability  → v2.5.0

- **T4.1** Metrics (Micrometer): throughput, batch-latency histograms, quarantine/error
  rates, lag (oldest unprocessed age), in-flight batches, enrichment recompute counts.
- **T4.2** Prometheus endpoint on the API host; structured JSON logs correlated by
  `run_id`/`batch_id`.
- **DoD:** metrics scrapeable; logs correlatable; key SLOs visible; green.

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
