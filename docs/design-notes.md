# Design Notes

Architectural decisions, deferred work, and the reasoning behind them. Not user-facing — read this before you make a structural change to the framework.

**Companion docs:** [`performance.md`](performance.md) (benchmark results, bottleneck analysis) · [`test-coverage.md`](test-coverage.md) (JaCoCo coverage breakdown and gaps).

---

## Scope: M..N multiplexer (read this before adding features)

The engine is an **M..N multiplexer**: M input files demultiplexed and routed
into N partitioned outputs, with stateless per-record transformations only. This
is a deliberate scope boundary, not a missing-features list. Full rationale is in
[Architecture → Design Philosophy & Scope](architecture.md#design-philosophy--scope);
the short version for contributors:

**In scope:** type coercion, column selection/rename, partition-key derivation,
`CONCAT_DT` / `FILENAME_DATE` composition. All per-record, all vectorizable.

**Out of scope — do not add to the engine:** joins against reference/external
data, cross-record aggregation (`GROUP BY` rollups, windowing, dedup), any state
held across records or batches. These would force shared state, serialize the
embarrassingly-parallel batch model, and break the clean routing. If a use case
needs them, the answer is the **downstream** query layer (DuckLake / pg_duckdb
over the Parquet output), not the multiplexer. When someone proposes a "quick
lookup" or "just join this one table" feature, point them here first.

## Stability tiers (informal SemVer policy)

Until we adopt a formal `@PublicApi` annotation + JPMS module-info, treat symbols by package as:

| Tier | Packages | Compatibility policy |
|---|---|---|
| **Public** (plugin authors depend on these) | `com.gamma.etl.FileIngester`, `com.gamma.etl.PipelineConfig`, `com.gamma.etl.IngestResult`, `com.gamma.etl.PartitionDef` | Breaking changes require a major version bump |
| **Framework-internal** | Everything else in `com.gamma.etl` and `com.gamma.inspector` | May change between minor versions; document in changelog |
| **CLI** | `com.gamma.util.MainApp` (entry point + command surface) | Stable command names; flag additions allowed |
| **Utility internals** | Other `com.gamma.util.*` | May change at any time |

If you depend on a framework-internal symbol from outside the project, pin to an exact patch version.

---

## Deferred design items

The following came out of the v1.3.2 review pass. They are real improvements but require either a breaking change or a focused design exercise; the team should approach each one deliberately rather than slipping it into a maintenance patch.

### D1 — Unified concurrency primitive — ✅ DONE (v1.5.0), batch level

**Resolved (v1.5.0).** `SourceProcessor` now uses a virtual-thread-per-task
executor (`Executors.newVirtualThreadPerTaskExecutor()`) bounded by a
`Semaphore(cfg.threads)`. This took **path 2** from the options below: `cfg.threads`
is repurposed as a permit count — a batch blocked on I/O parks its carrier cheaply,
but at most `cfg.threads` batches do heavy work at once. A blocked batch no longer
pins a platform thread, and the cap still protects a shared box from I/O pressure.

Companion knob: `processing.duckdb_threads` applies `PRAGMA threads=N` per worker
connection via `DuckDbUtil.applyWorkerThreads`, so the *inner* DuckDB parallelism is
also controllable. `ConfigValidator` warns when `threads × duckdb_threads` exceeds
the core count (oversubscription). Default `duckdb_threads=0` leaves DuckDB's default
(no behaviour change unless set).

The pre-ETL utilities still use `VirtualThreadRunner` + `Phaser` (uncapped); that's
fine — they're operator-supervised staging tools, not the high-throughput path.

**Multi-source orchestrator — ✅ DONE (v1.6.0).** `MultiSourceProcessor` runs
several sources concurrently in one JVM on a virtual-thread executor bounded by
`-Dsources.max`, composing `SourceProcessor.run(cfg)` per source with full failure
isolation. The three concurrency caps (`sources.max × threads × duckdb_threads`)
multiply — documented in [Operations → Multiple sources in one process](operations.md#multiple-sources-in-one-process).
This completes the M..N runtime model end to end.

### D2 — Commit-log abstraction

**Current state.** Batch durability is signalled by the *combination* of (a) outputs present on disk under partition paths, (b) manifest written in `dirs.status_dir/manifests/`, (c) member files moved to `dirs.backup`, (d) marker files created in `dirs.markers`. The v1.3.2 commit ordering fix in `BatchProcessor.commit` makes a mid-step crash idempotent on rerun (markers go last) — but there's still no single "this batch finished" line operators can grep.

**Why deferred.** A proper commit log needs design choices: append-only or per-batch file? Synced to disk or buffered? Read on every poll to skip "in-progress" batches, or only on operator-triggered recovery? Each choice has different operational implications.

**When to do this.** When the first real production incident makes "did batch X finish or not" a question worth answering definitively. Until then the current 4-signal redundancy is operationally sufficient.

### D4 — DuckDB type leak in `CsvIngester`

`CsvIngester` casts `(DuckDBConnection) conn` at one site to reach the appender API (much faster than `INSERT ... VALUES`). This is a deliberate engine-coupling for performance — without the appender, ingesting a 5 GB CSV is 10× slower. If the project ever wants to swap engines, hide the appender behind an `RawTableWriter` interface; for now, accept the coupling.

### D6 — `PipelineConfig` nested-record refactor

**Current state.** `PipelineConfig` exposes 30+ flat public final fields. Logical groupings (`Identity`, `Dirs`, `CsvSettings`, etc.) would be more readable, but every existing plugin and CLI command reads `cfg.delimiter`, `cfg.databaseDir`, etc., directly. Splitting into nested records is a breaking change.

**When to do this.** v2.0. Pair it with the `@PublicApi` annotation rollout so the surface area we commit to is explicit.

### M3 — Formal SemVer policy + `@PublicApi`

See "Stability tiers" above for the interim informal policy. A formal `@PublicApi` annotation plus a `module-info.java` that exports only the public packages would let consumers know what they can depend on. Defer until D6 lands (so the surface to mark is the cleaned-up nested-record version).

### M4 — Split the README into a `docs/` tree — ✅ DONE (v1.6.1)

The ~1,570-line README was split into a lean overview + quickstart + doc index,
with topic docs under `docs/`: `architecture.md` (design philosophy, architecture,
directory layout, two-step process), `configuration.md` (three-tier config, config
by source format, multi-schema, type mapping), `plugins.md` (FileIngester +
TypedRecordIngester + author workflow), `operations.md` (utility suite, batch
processing & concurrency, multi-source, output, audit, deployment, onboarding),
`integrations.md` (DuckLake + warehouse query layer), `troubleshooting.md`.
Content was moved byte-for-byte; cross-links were repointed.

---

## Cross-cutting conventions

- **SLF4J everywhere in the framework.** ETL code (`com.gamma.etl.*`, `com.gamma.inspector.*`) uses SLF4J at INFO/WARN/ERROR. Pre-ETL utility commands (`com.gamma.util.*` CLI tools) keep `System.out.println` because their output is for the human invoker, not log scrapers. `LogSetup` still tees stdout to a per-run file for diagnostics.

- **DuckDB connection per batch.** Each `BatchProcessor.process` opens its own temp DuckDB file via `DuckDbUtil.tempDbFile` and closes it in `finally`. No pooling, no sharing. Worth it for crash isolation. Each connection's internal parallelism is capped by `DuckDbUtil.applyWorkerThreads(conn, cfg.duckdbThreads)` immediately after open — set `processing.duckdb_threads` so `threads × duckdb_threads ≈ cores`, else concurrent batches oversubscribe (the outer `Semaphore(cfg.threads)` only bounds batch count, not DuckDB's per-connection threads).

- **Two CSV ingest engines.** `DuckDbCsvIngester` (native `read_csv`, vectorized, 4–5× faster) and `CsvIngester` (Java line-by-line, lenient with ragged rows) are interchangeable behind the same `ingest(...)` signature. `BatchProcessor.processCsv` picks via `DuckDbCsvIngester.usesDuckDb(cfg)`. The Java path is the fallback for messy configs (`skip_tail_columns` etc.) the native reader can't faithfully reproduce — see `docs/performance.md` for the semantic-difference analysis. Don't delete the Java path; it's load-bearing for the messy-file sources.

- **`raw_<KEY>_f<srcId>` table naming.** Hard-coded convention used by `BatchProcessor.processPlugin` to union member tables. Plugin authors must obey it; documented in `FileIngester` Javadoc.

- **Markers go last in `commit()`.** See `BatchProcessor.commit` for the ordering rationale comment. Any future commit-step change must preserve "markers signal durability" — if you create a marker for a file whose backup hasn't moved, that file is stranded.
