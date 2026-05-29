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
- **Flagship:** the separate enrichment / join engine — runs as an independent track.
- **Last in v2:** status store in a database (behind a `StatusStore` seam).

## v2 epics

| Epic | Priority | Status | Depends on |
|---|---|---|---|
| **E0** Parallel execution (multi-source + multi-batch) | P0 | ✅ **done** (v1.5.0 / v1.6.0) | — |
| **Enrichment / join engine** (Stage 2) | **P0 — flagship** | 🔲 | Stage 1 output (exists); independent track |
| **Service / server mode** | **P0** | 🔲 | E0 primitives |
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

### Enrichment / join engine  🔲 P0 (flagship — independent track)

A **separate** engine that does what the multiplexer deliberately won't: joins,
lookups, aggregation. Reads Stage 1's partitioned Parquet/CSV (or the DuckLake
catalog), enriches against reference/dimension data, writes enriched output.

- **DuckDB-powered:** `read_parquet` over the Hive partition tree, join against
  reference tables, compute derived/aggregated columns, write via the existing
  partitioned-output machinery.
- **Config-driven** in the same `.toon` style: inputs (partition globs / DuckLake
  tables), reference sources (dim tables from files / Postgres / DuckLake), join
  keys & types, projections/derivations, output partitioning.
- **Partition-parallel & crash-isolated** — reuses the virtual-thread + semaphore
  model and per-batch DuckDB connection.
- **Reuses:** `DuckDbUtil`, `PartitionWriter`, `PartitionDef`, config loading,
  `ConfigValidator`, audit/lineage.
- **v1 boundaries:** batch (not streaming); reference data bounded to the
  per-partition working set; incremental (new partitions only) as a fast-follow.
- **Why an independent track:** it only consumes Stage-1 output, so it needs none of
  the platform work — ship it CLI-first (like Stage 1 matured) in parallel with the
  service/API epics, rather than gating the flagship behind the whole platform.
- **Size:** L.

### Service / server mode  🔲 P0 (first platform epic)

Turn poll-once-per-invocation into a resilient long-running service hosting **both**
engines — and the host for the API and metrics endpoints, so it precedes them.

- Watch loop / scheduler per pipeline; graceful shutdown; **recovery on startup via
  `CommitLog.committedBatchIds()`** (ledger exists; nothing reads it yet).
- Absorbs the E0 residual: a global concurrency budget across sources + backpressure.
- A pipeline may be a Stage-1 ingest, a Stage-2 enrichment, or a chain (enrich fires
  when upstream partitions land).
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

- **Platform track:** Service mode → Control API → Observability → Status DB (last).
  *Each is hosted by the service; the API/metrics need it first; the DB swap is a
  late, low-risk change behind the `StatusStore` seam.*
- **Flagship track (parallel):** Enrichment engine, CLI-first, independent of the
  platform — then surfaced through the API/service once both exist.

> This adjusts the proposed E1→E2→E3 order in two ways: **Service mode before Control
> API** (the API is hosted by the server) and observability after the host exists;
> and **Enrichment as a parallel track** so the flagship value isn't gated behind the
> whole platform. E0 is recognized as already done.

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
