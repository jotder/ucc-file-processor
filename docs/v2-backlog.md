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
                       (service mode · API · UI · observability)
```

The multiplexer's deliberate non-goals (no joins/lookups/aggregation) are
**preserved** — enrichment lives in its own engine that consumes Stage 1's output,
never inside the multiplexer. That separation is the organizing principle of v2.

> Proposal to start the conversation — reorder freely. Legend: 🔲 not started ·
> 🟡 in progress · ✅ done.

## Scope changes from the first draft (per your direction)

- **Moved OUT to the next major (v3.0+):** embedded AI agent, object storage
  (S3/GCS/Azure), distributed/multi-node execution.
- **NEW, high priority:** a separate **enrichment / join engine** (E7) over the
  partitioned output.
- **Demoted to last in v2:** status store in a database (E1).

## v2 epics

| # | Epic | Priority | Depends on |
|---|---|---|---|
| **E7** | Enrichment / join engine (Stage 2) | **P0 — flagship** | Stage 1 output (exists) |
| **E2** | Service / server mode | **P0** | — (file-backed status to start) |
| **E3** | Control API (user interaction) | **P0** | E2 |
| **E4** | Observability | P1 | E2 |
| **E5** | Web UI | P1 | E3 |
| **E1** | Status store in a database | **P2 — last in v2** | E2/E3 (swaps their backing store) |

### E7 — Enrichment / join engine  🔲 P0 (flagship)

A **separate** engine that does what the multiplexer deliberately won't: joins,
lookups, and aggregation. It reads Stage 1's partitioned Parquet/CSV (or the
DuckLake catalog) as input, enriches against reference/dimension data, and writes
enriched output.

- **DuckDB-powered** (already in the stack): `read_parquet` over the Hive partition
  tree, join against reference tables, compute derived/aggregated columns, write via
  the same partitioned-output machinery.
- **Config-driven** in the same `.toon` style: declare inputs (partition globs /
  DuckLake tables), reference sources (dim tables from files / Postgres / DuckLake),
  join keys & types, projections/derivations, and output partitioning.
- **Partition-parallel & crash-isolated** — reuse the virtual-thread + semaphore
  model and per-batch DuckDB connection so it scales and recovers like Stage 1.
- **Reuses:** `DuckDbUtil`, `PartitionWriter`, `PartitionDef`, config loading,
  `ConfigValidator`, audit/lineage — most plumbing already exists.
- **Initial boundaries (keep it sane):** batch (not streaming); reference data
  bounded enough to fit the per-partition working set; incremental mode (enrich only
  new partitions) as a fast-follow.
- **Decisions needed:** same-repo module vs separate artifact; reference-data source
  (files / Postgres / DuckLake); full re-enrich vs incremental; output target
  (Parquet / DuckLake / warehouse).
- **Size:** L. **Why flagship:** delivers the joined/enriched datasets that are the
  actual analytical product, and it's independent enough to ship CLI-first (exactly
  how Stage 1 matured) before the control plane wraps it.

### E2 — Service / server mode  🔲 P0

Turn poll-once-per-invocation into a resilient long-running service that hosts
**both** engines.

- Watch loop / scheduler per pipeline; graceful shutdown; **recovery on startup via
  `CommitLog.committedBatchIds()`** (the ledger exists; nothing reads it yet).
- A pipeline can be a Stage-1 ingest, a Stage-2 enrichment, or a chain (enrich runs
  when its upstream partitions land).
- **Build on:** `MultiSourceProcessor` becomes the service's core loop.
- **Status backing:** starts **file-backed** (the existing `_status_`/`_batches_`/
  `_lineage_` CSVs + commit log) so E1 isn't a blocker; E1 swaps in a DB later behind
  a `StatusStore` abstraction.
- **Size:** M.

### E3 — Control API  🔲 P0

One REST surface for all interaction — CLI and UI use the same endpoints.

- list/CRUD pipelines & configs (both engines); trigger/pause/resume; query
  runs/batches/files/lineage/quarantine; stream logs; reprocess (wraps
  `ReprocessCommand`); validate config (wraps `ConfigValidator`).
- Reads/writes through the `StatusStore` abstraction (file-backed now, DB after E1).
- **Decision:** lightweight embedded server (Javalin / JDK built-in) over Spring, to
  keep the small-footprint fat-JAR ethos. AuthN/Z required here.
- **Size:** M–L.

### E4 — Observability  🔲 P1

Metrics (throughput, batch-latency histograms, quarantine/error rates, lag),
structured logs correlated by `run_id`/`batch_id`, optional traces. Covers both
engines. Build on the per-batch timings already in audit rows. **Size:** S–M.

### E5 — Web UI  🔲 P1

Dashboard over E3: pipeline/DAG view (Stage 1 → Stage 2), run history, lineage
explorer, quarantine browser with "why did this fail?", config editor with live
validation, trigger/reprocess controls. **Decision:** SPA vs server-rendered.
**Size:** L; pure consumer of E3, so it can lag.

### E1 — Status store in a database  🔲 P2 (last in v2)

Swap the file-backed `StatusStore` for a DB (`pipelines`, `runs`, `batches`,
`files`, `lineage`, `quarantine`, `commits`). Done **last** in v2: the API/UI/metrics
run on the file-backed store first, and E1 is a backing-store swap behind the
abstraction rather than a prerequisite.

- **Build on:** `BatchAuditWriter`/`ManifestStore`/`CommitLog` already emit exactly
  this data.
- **Decision:** Postgres (recommended — already in the stack via DuckLake, clean
  multi-writer) vs DuckDB vs SQLite.
- **Size:** M.

## Suggested sequence

- **Phase A — Flagship:** E7 enrichment engine (CLI-first). *Outcome: joined/enriched
  datasets, the analytical product.*
- **Phase B — Platform:** E2 service mode + E3 control API (file-backed status),
  hosting both engines. *Outcome: an always-on, controllable 2-stage platform.*
- **Phase C — Surfaces:** E4 observability + E5 UI.
- **Phase D — Hardening:** E1 status DB (swap the backing store).

Rationale: E7 is independent and delivers value immediately (like Stage 1 did
CLI-first); the control plane then wraps both engines; the DB migration is a
late, low-risk swap behind an abstraction.

## Next major (v3.0+) — deferred by design

- **Embedded AI operator agent** — onboard sources (sample→config via
  `SchemaExtractor`), operate, and diagnose through the control API. Lands *after*
  the API + status DB exist, so it has a real surface to act on and signals to reason
  over. The single biggest bet — worth its own major.
- **Object storage (S3/GCS/Azure)** for inbox/output — DuckDB speaks it natively;
  high real-world value once deployment target firms up.
- **Distributed / multi-node execution** — still against the single-JVM,
  crash-isolated ethos; prefer N instances over disjoint inputs until proven
  necessary.

## Cross-cutting (threaded through v2)

- **`StatusStore` abstraction** — introduced in E2/E3 (file-backed), swapped to DB in
  E1. The seam that lets E1 be last.
- **AuthN/Z** — required once E3/E5 exist.
- **Packaging** — fat JAR stays; add a service launcher + container image; the
  enrichment engine is a second entry point (or a sub-command).

## Decisions needed before Phase A (E7)

1. **Enrichment engine home** — same repo as a new module/entry point (reuses all the
   DuckDB plumbing) vs a separate artifact?
2. **Reference-data source** — dimension/lookup tables from files, Postgres, or
   DuckLake?
3. **Enrichment scope v1** — full re-enrich vs incremental (new partitions only); and
   the output target (Parquet / DuckLake / warehouse load)?
