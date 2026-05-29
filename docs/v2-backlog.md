# v2.x Product Backlog — a two-stage data platform

The 1.x → 2.0 work made the engine clean and stable. The 2.x arc grows it from a
single ETL tool into a **two-stage, service-operated data platform**:

```
   raw files ──► [ Stage 1: M..N multiplexer ] ──► partitioned Parquet/CSV
                  (ingest, type, partition;          │
                   NO joins — by design)             ▼
                                          [ Stage 2: enrichment engine ] ──► enriched output
                                           (joins, lookups, aggregation —
                                            a SEPARATE engine, by design)
                          ▲                         ▲
                          └──── shared control plane ────┘
                            (service mode · API · observability)
```

The multiplexer's deliberate non-goals (no joins/lookups/aggregation) are
**preserved** — enrichment lives in its own engine that consumes Stage 1's output,
never inside the multiplexer. That separation is the organizing principle of v2.

> Legend: 🔲 not started · 🟡 in progress · ✅ done.

## Scope (current direction)

- **Deferred to next major (v3.0+):** embedded AI agent, **Web UI**, object storage
  (S3/GCS/Azure), distributed/multi-node execution.
- **Already delivered (1.5–1.6):** parallel execution (E0 below).
- **Flagship:** the separate enrichment engine — **sequenced after ingest**, triggered
  by batch-commit events (freshness) + a schedule (completeness). Its *core* is
  buildable standalone; its *orchestration* depends on Service mode.
- **Last in v2:** status store in a database (behind a `StatusStore` seam).

## Enrichment execution model (decided)

Enrichment is downstream of ingest and runs **incrementally on changed partitions**,
via two triggers:

- **Event-driven (on successful batch commit):** low-latency freshness. The committed
  batch's **lineage already lists exactly which partitions it wrote**, so enrichment
  recomputes *only* the affected reports — precise incrementality, no full re-scan.
- **Scheduled (hourly/daily/…):** completeness. Recomputes closed windows so KPIs are
  authoritative and late-arriving data is reconciled.

Both write the same output **idempotently** — `PartitionWriter`'s
`OVERWRITE_OR_IGNORE` makes re-runs and late data safe by construction; the schedule
re-corrects whatever events left partial. `CommitLog` is the natural event source.

Because both triggers need an always-on listener + a scheduler, the **enrichment
feature depends on Service mode** (which gains a batch-commit event hook + scheduler).
The enrichment *core* (changed-partitions → columnar aggregate/join → idempotent
write) is pure and can be spiked CLI-first to de-risk it.

Open design point: the enrichment **output grain** (e.g. hourly/daily KPI, by
dimension) usually differs from the ingest partition grain, so the config maps
"input partitions changed → output reports to recompute" and defines a
completeness/watermark rule for when a scheduled window is final.

## v2 epics

| Epic | Priority | Status | Depends on |
|---|---|---|---|
| **E0** Parallel execution (multi-source + multi-batch) | P0 | ✅ **done** (v1.5.0 / v1.6.0) | — |
| **Enrichment engine — core** (Stage 2 transform) | **P0 — flagship** | 🔲 | Stage 1 output (exists); spike-able CLI-first |
| **Service / server mode** (+ batch-commit event bus + scheduler) | **P0** | 🔲 | E0 primitives |
| **Enrichment — orchestration** (event + scheduled triggers) | **P0 — flagship** | 🔲 | Enrichment core, Service mode |
| **Control API** (interaction) | **P0** | 🔲 | Service mode |
| **Observability** | P1 | 🔲 | Service mode |
| **Status store in a database** | P2 — last | 🔲 | API/service (swaps their backing store) |

### E0 — Parallel execution  ✅ done

"Run multiple data sources in parallel; run multiple batches of one source in
parallel" — **already implemented**:

- **Multi-batch (one source):** `SourceProcessor.run` submits every batch to a
  virtual-thread executor bounded by `Semaphore(processing.threads)` (v1.5.0).
- **Multi-source:** `MultiSourceProcessor` runs sources concurrently, bounded by
  `-Dsources.max`, failure-isolated (v1.6.0).
- **DuckDB inner cap:** `processing.duckdb_threads` (`PRAGMA threads`) so the three
  caps multiply predictably.

**Residual (net-new) → folds into Service mode:** turning these one-shot pools into
an always-on managed concern — a *global* concurrency budget across all running
sources, fair scheduling, and backpressure when the host is saturated.

### Enrichment engine — core  🔲 P0 (flagship)

The columnar transform that does what the multiplexer deliberately won't: joins,
lookups, aggregation — to produce reports/KPIs. Pure and CLI-spike-able.

- **Input:** a set of *changed* partitions (from a batch's lineage) or an explicit
  window; reads Stage 1's partitioned Parquet/CSV (or the DuckLake catalog).
- **DuckDB-powered:** `read_parquet` over the relevant partitions, join against
  reference tables, aggregate/derive, write via the existing partitioned-output
  machinery — **idempotent** (`OVERWRITE_OR_IGNORE`) so re-runs and late data are safe.
- **Config-driven** (`.toon`): inputs, reference sources (dim tables from files /
  Postgres / DuckLake), join keys & types, projections/derivations, **output grain &
  partitioning** (may differ from ingest grain), and the input→output recompute map.
- **Partition-parallel & crash-isolated** — reuses the virtual-thread + semaphore
  model and per-batch DuckDB connection.
- **Reuses:** `DuckDbUtil`, `PartitionWriter`, `PartitionDef`, config loading,
  `ConfigValidator`, audit/lineage (lineage drives precise incremental scoping).
- **De-risk first:** a thin CLI spike (changed partitions → aggregate → idempotent
  write) validates the columnar approach before the orchestration is wired.
- **Size:** M–L (core); orchestration is a separate epic below.

### Enrichment engine — orchestration  🔲 P0 (flagship)

Wires the core to the two triggers (see "Enrichment execution model" above):

- **Event subscriber:** on batch commit, read the batch's lineage → recompute only
  the affected reports (freshness).
- **Scheduled job:** recompute closed windows (hourly/daily) for completeness +
  late-data reconciliation.
- **Depends on Service mode** (the event bus + scheduler). The core can exist before
  this; the *feature* lands here.
- **Size:** M.

### Service / server mode  🔲 P0 (first platform epic)

Turn poll-once-per-invocation into a resilient long-running service hosting **both**
engines — and the host for the API and metrics endpoints, so it precedes them.

- Watch loop / scheduler per pipeline; graceful shutdown; **recovery on startup via
  `CommitLog.committedBatchIds()`** (ledger exists; nothing reads it yet).
- **Batch-commit event bus** — fire an event on each committed batch (from
  `CommitLog` append / `BatchProcessor.commit`) carrying `pipeline, batchId,
  partitions[]`. Enrichment orchestration subscribes to it; the lineage gives the
  changed partitions. This is the linchpin for the event-driven enrichment model.
- **Scheduler** — cron-like windows for scheduled enrichment (hourly/daily) and any
  periodic maintenance.
- Absorbs the E0 residual: a global concurrency budget across sources + backpressure.
- A pipeline may be a Stage-1 ingest, a Stage-2 enrichment, or a chain (enrich fires
  on the upstream's batch-commit event).
- **Build on:** `MultiSourceProcessor` becomes the core loop.
- **Status backing:** **file-backed** to start (existing `_status_`/`_batches_`/
  `_lineage_` CSVs + commit log) behind a `StatusStore` seam, so the DB epic isn't a
  blocker.
- **Size:** M.

### Control API  🔲 P0 (on the service host)

One REST surface for all interaction — CLI and (later) UI/agent use the same
endpoints.

- list/CRUD pipelines & configs (both engines); trigger/pause/resume; query
  runs/batches/files/lineage/quarantine; stream logs; reprocess (wraps
  `ReprocessCommand`); validate config (wraps `ConfigValidator`).
- Reads/writes through the `StatusStore` seam (file-backed now, DB later).
- **Decision:** lightweight embedded server (Javalin / JDK built-in) over Spring.
  AuthN/Z required here.
- **Size:** M–L.

### Observability  🔲 P1

Metrics (throughput, batch-latency histograms, quarantine/error rates, lag,
in-flight batches), structured logs correlated by `run_id`/`batch_id`, optional
traces — covering both engines, exposed on the service host. Builds on per-batch
timings already in audit rows. **Size:** S–M.

### Status store in a database  🔲 P2 (last in v2)

Swap the file-backed `StatusStore` for a DB (`pipelines`, `runs`, `batches`,
`files`, `lineage`, `quarantine`, `commits`). Done **last**: a backing-store swap
behind the seam, not a prerequisite.

- **Build on:** `BatchAuditWriter`/`ManifestStore`/`CommitLog` already emit this data.
- **Decision:** Postgres (recommended — already in the stack via DuckLake) vs DuckDB
  vs SQLite. **Size:** M.

## Recommended sequence

1. **Enrichment core spike** (small, CLI) — changed-partitions → columnar
   aggregate/join → idempotent write. De-risks the flagship transform cheaply, before
   any platform work.
2. **Service / server mode** — process + **scheduler** + **batch-commit event bus** +
   recovery + the E0 global concurrency budget. The linchpin for event-driven
   enrichment.
3. **Enrichment orchestration** — wire the core to the event subscriber (freshness) +
   scheduled job (completeness). Flagship feature lands here.
4. **Control API** → **Observability** → **Status DB** (last, behind the
   `StatusStore` seam).

> Enrichment is **sequenced after ingest at runtime** (event + schedule), not run
> independently. Build-wise its *core* is spiked first to de-risk, but the *feature*
> depends on Service mode (the event bus + scheduler) — so Service mode is the
> prerequisite, not a parallel concern. Service mode also precedes the Control API and
> Observability (it hosts them). E0 is already done.

## Next major (v3.0+) — deferred by design

- **Embedded AI operator agent** — onboard sources (sample→config via
  `SchemaExtractor`), operate, and diagnose through the control API. Lands after the
  API + status DB exist, so it has a real surface and signals. The single biggest
  bet — its own major.
- **Web UI** — dashboard over the control API (pipeline/DAG view, run history,
  lineage explorer, quarantine browser, config editor). Pure consumer of the API; a
  natural v3 once the API and status DB are solid.
- **Object storage (S3/GCS/Azure)** for inbox/output — DuckDB speaks it natively.
- **Distributed / multi-node execution** — against the single-JVM, crash-isolated
  ethos; prefer N instances over disjoint inputs until proven necessary.

## Cross-cutting (threaded through v2)

- **`StatusStore` abstraction** — introduced in Service mode/API (file-backed),
  swapped to DB last. The seam that lets the DB epic be last.
- **AuthN/Z** — required once the Control API exists.
- **Packaging** — fat JAR stays; add a service launcher + container image; the
  enrichment engine is a second entry point (or sub-command).

## Decisions needed

**Flagship track (enrichment):**
1. Home — same repo as a new module/entry point (reuses DuckDB plumbing) vs separate artifact?
2. Reference-data source — files / Postgres / DuckLake?
3. v1 scope — full re-enrich vs incremental; output target (Parquet / DuckLake / warehouse)?

**Platform track (service/API):**
4. Embedded HTTP server — Javalin / Helidon / JDK built-in / Spring?
5. Status DB engine (for the last epic) — Postgres / DuckDB / SQLite?
