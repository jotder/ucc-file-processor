# Design Notes

Architectural decisions, deferred work, and the reasoning behind them. Not user-facing — read this before you make a structural change to the framework.

**Companion docs:** [`performance.md`](performance.md) (benchmark results, bottleneck analysis) · [`test-coverage.md`](test-coverage.md) (JaCoCo coverage breakdown and gaps).

---

## Scope: M..N multiplexer (read this before adding features)

> **v3.0 scope note.** This boundary governs **Stage-1** (the `com.gamma.etl` /
> `com.gamma.inspector` multiplexer). The platform's **Stage-2 enrichment engine**
> (`com.gamma.enrich`, 2.x) is the sanctioned home for joins/aggregation — so the rule below
> is "don't add these *to the Stage-1 engine*," not "the platform never does them." When a
> cross-record need arises, the answer is now **Stage-2**, not a foreign query tool. See
> [v3-architecture.md](v3-architecture.md).

The Stage-1 engine is an **M..N multiplexer**: M input files demultiplexed and routed
into N partitioned outputs, with stateless per-record transformations only. This
is a deliberate scope boundary, not a missing-features list. Full rationale is in
[Architecture → Design Philosophy & Scope](architecture.md#design-philosophy--scope);
the short version for contributors:

**In scope (Stage-1):** type coercion, column selection/rename, partition-key derivation,
`CONCAT_DT` / `FILENAME_DATE` composition. All per-record, all vectorizable.

**Out of scope for Stage-1 — do not add to the multiplexer engine:** joins against
reference/external data, cross-record aggregation (`GROUP BY` rollups, windowing, dedup),
any state held across records or batches. These would force shared state, serialize the
embarrassingly-parallel batch model, and break the clean routing. The home for them is
**Stage-2 enrichment** (`com.gamma.enrich`) — or, outside the platform, the **downstream**
query layer (DuckLake / pg_duckdb over the Parquet output). When someone proposes a "quick
lookup" or "just join this one table" feature *in Stage-1*, point them here first.

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
`Semaphore(cfg.processing().threads())`. This took **path 2** from the options below:
`processing.threads` is repurposed as a permit count — a batch blocked on I/O parks
its carrier cheaply, but at most that many batches do heavy work at once. A blocked batch no longer
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

### D2 — Commit-log abstraction — ✅ DONE (2.0.0)

`CommitLog` is a durable, append-only, **fsync'd** ledger — one persistent file per
pipeline (`<status_dir>/<pipeline>_commits.log`, not run-timestamped) with one line
per batch: `committed_at,batch_id,pipeline,status,member_count,output_count,output_rows,output_bytes`.
It is the single grep-able source of truth for "did this batch finish".

**Design choices taken:** append-only single file (not per-batch); `FileChannel.force(true)`
after every record (the per-run `_batches_<ts>.csv` is buffered and can lose its tail
on crash — the commit log cannot); written by `BatchAuditWriter.flush` *after* the
three audit CSVs, so a commit-log line implies the audit rows exist. `CommitLog.committedBatchIds()`
reads back SUCCESS ids for recovery/"what already finished" queries (not yet wired
into the poll loop — recovery stays operator-triggered for now). Path exposed as
`cfg.dirs().commitLogPath()`; disabled (null) when status is disabled.

### D4 — DuckDB type leak in `CsvIngester`

`CsvIngester` casts `(DuckDBConnection) conn` at one site to reach the appender API (much faster than `INSERT ... VALUES`). This is a deliberate engine-coupling for performance — without the appender, ingesting a 5 GB CSV is 10× slower. If the project ever wants to swap engines, hide the appender behind an `RawTableWriter` interface; for now, accept the coupling.

### D6 — `PipelineConfig` nested-record refactor — ✅ DONE (2.0.0)

`PipelineConfig`'s ~30 flat fields are now six nested records reached via accessors:
`identity()`, `dirs()`, `processing()`, `csv()`, `output()`, `schemas()`. Consumers
moved from `cfg.databaseDir` to `cfg.dirs().database()`, etc. The migration was
mechanical (compiler-guided — every old field access became a compile error) and
landed in one green step. This is the one breaking API change of 2.0; pipeline
`.toon` configs and on-disk output are unchanged. M3 (`@PublicApi`) marks this
final surface.

### M3 — Formal SemVer policy + `@PublicApi` — ✅ DONE (2.0.0)

`com.gamma.api.PublicApi` (CLASS-retained marker) now annotates the stable public
surface: `FileIngester` + `Segment`, `IngestResult`, `PipelineConfig` + its six
nested records, and the `SourceProcessor` / `MultiSourceProcessor` embedding entry
points. Everything unmarked is internal. The full policy, marked surface, and the
config/output contracts that carry forward are in
[api-stability.md](api-stability.md).

**`module-info.java` deliberately skipped.** JPMS over a fat shaded JAR with many
automatic-module deps (DuckDB, univocity, JToon, Jackson, …) is high-risk for
little gain; the annotation + policy doc is the sufficient, lower-risk mechanism.
Rationale recorded in api-stability.md ("Why no module-info").

### M4 — Split the README into a `docs/` tree — ✅ DONE (v1.6.1)

The ~1,570-line README was split into a lean overview + quickstart + doc index,
with topic docs under `docs/`: `architecture.md` (design philosophy, architecture,
directory layout, two-step process), `configuration.md` (three-tier config, config
by source format, multi-schema, type mapping), `plugins.md` (FileIngester +
TypedRecordIngester + author workflow), `operations.md` (utility suite, batch
processing & concurrency, multi-source, output, audit, deployment, onboarding),
`integrations.md` (DuckLake + warehouse query layer), `troubleshooting.md`.
Content was moved byte-for-byte; cross-links were repointed.

### D7 — Engine modularity pass (behavior-injection seams) — ✅ DONE (v3.9.0)

A targeted, **behavior-preserving** refactor of the Stage-1 engine: same public API,
same `.toon` contract, byte-identical emitted SQL and on-disk output, every step green
(452 → 466 tests). The goal was to replace inline type/format branching with injectable
behavior so the engine code stays thin and new variants are closed-set edits rather than
edits to growing methods. Four seams landed:

- **`OutputFormat` (enum-as-strategy).** `PartitionWriter` no longer does
  `"PARQUET".equals(outputFormat)`; each format constant owns its extension, COPY
  `FORMAT` token, and compression applicability. `resolve(String)` reproduces the
  historical rule exactly (PARQUET only for the canonical token, else CSV). A new format
  is one enum constant + a `resolve` mapping.
- **`TransformCompiler` (functional injection).** Per-column SQL generation moved out of
  `DataTransformer.materialize` into a pure compiler with a `transformType → ColumnRule`
  function registry (`CONCAT_DT`, `FILENAME_DATE`, `DIRECT`/fallback). `materialize` is now
  a thin SELECT-assembler. The deliberate data-column-vs-partition asymmetry (data columns
  wrap DATE/TIMESTAMP in `CAST(... AS VARCHAR)`; partition columns route through
  `SqlBuilder.buildCastExpr`) is preserved and pinned by byte-exact tests
  (`TransformCompilerTest`).
- **`BatchIngestStrategy` (Strategy).** The former `BatchProcessor.processCsv` /
  `processPlugin` god-methods became `CsvBatchStrategy` / `PluginBatchStrategy`, each
  returning a typed `IngestOutcome` (status, survivors, outputs, lineage, per-member audit,
  totals). `BatchProcessor` is now a thin coordinator: pick strategy → run → shared
  `commit` → shared `writeAudit`. The two audit-assembly variants collapsed into one
  (the CSV/plugin difference is just `IngestOutcome.schemaLabel`). Shared `dropTable`/`msg`
  live as static methods on the interface.
- **`TypedRecordIngester` hot-loop hoist.** The nested `raw→fields` cast that computed the
  declared field count was recomputed per input line; it's now resolved once per segment
  into `declaredByKey`/`fieldsByKey` before the parse loop.

**Why behavior-preserving over a teardown.** The codebase is shipped and guardrailed
(backward-compat, lean-core, `.toon` stability). The bigger restructure that was *not* done
— replacing the `Map<String,Object>` config core with a typed model — would break the
`.toon`/embedding contract for marginal internal gain; it stays out of scope. No new
runtime dependency was added. `OutputFormat`/`TransformCompiler` and the strategy types are
all framework-internal (see [api-stability.md](api-stability.md)).

---

### D8 — Large-file handling: scratch location, streaming, auto-chunking — ✅ DONE (3.10.0)

**Problem.** A ~1TB single CSV failed with `No space left on device`. Three compounding causes,
all code-grounded: (1) the per-batch DuckDB temp database was created via
`File.createTempFile()` → `java.io.tmpdir` (system `/tmp`, often small/`tmpfs`), and DuckDB's
spill followed it — the configured, data-volume `dirs.temp` was **ignored** by the ETL path
(only the tar utilities used it); (2) a single oversized file is a batch-of-one with no
intra-file chunking; (3) the CSV path materialised the data **2–3×** (`raw_f0` → `raw_input` →
`transformed`) before the partitioned COPY. Additive, behavior-preserving fix in three layers:

- **A — Relocate & cap engine scratch.** New optional `processing.duckdb` block
  (`memory_limit`, `temp_directory`, `max_temp_directory_size`) → `PipelineConfig.DuckDbSettings`.
  `DuckDbUtil` gained `tempDbFile(prefix, dir)` and `applyDuckDbSettings(...)`; both batch
  strategies now create the temp DB under the resolved **scratch dir** (explicit
  `temp_directory`, else `dirs.temp`, else the old `/tmp` fallback) and `SET temp_directory` so
  the spill follows. The temp DB no longer defaults to `/tmp` — the one behavior change, and the
  intended bugfix. All settings absent ⇒ DuckDB defaults (backward-compatible).
- **B — Single-pass streaming for single-member native batches.** `raw_input` is now a lazy
  `VIEW` over `read_csv` (via `DuckDbCsvIngester.createRawInputView`); the one
  `CREATE TABLE transformed AS …` pulls data through in a single streaming pass — peak scratch
  drops from ~2× to one transformed table, the redundant copy is gone. Output and lineage come
  from the **same** `PartitionWriter`/`LineageCollector` over the same `transformed` table, so
  results are identical (the existing suite, mostly single-file batches, stayed green). Multi-member
  and Java-engine paths are unchanged.
- **C — Auto-chunking (`processing.chunking`).** `FileChunker` streams an oversized file into
  bounded, self-contained chunks (header replicated, `.gz` decompressed, one chunk on disk at a
  time); each chunk runs through the streaming pass writing `<base>_cNNNNN_out.*`, with counts /
  outputs / lineage aggregated. The **original file** stays the audit/marker/backup unit, so
  commit ordering and idempotency are untouched. Peak scratch is bounded to ~one chunk regardless
  of file size.

DuckDB's native `read_csv` is itself multi-threaded, so even an un-chunked large file uses all
cores once scratch is off `/tmp`; chunking is about **bounding scratch** (and crash-isolating a
huge file), not adding CPU parallelism. New deps: none. New tests: `FileChunkerTest`,
`ChunkedStreamingTest`, `DuckDbSettingsTest`; new spec fields in `ConfigSpecs.pipeline()`.
Follow-up (not done): jail `processing.duckdb.temp_directory` in `ConfigSafetyValidator`
(operator-trust today, defaults to the already-jailed `dirs.temp`); optional cross-chunk
parallelism (sequential today, each chunk already multi-threaded internally).

---

### D9 — Streaming SPI for very large *custom* files (plugin ingesters) — ✅ DONE (3.10.0)

**Problem.** D8 bounded scratch for the **CSV** path, but the plugin path
([plugins.md](plugins.md)) was left worse off for a TB-scale custom file (binary / proprietary /
ASN.1). `FileIngester.ingest(file, conn, srcId, cfg)` is **whole-file by construction** — it must
populate complete `raw_<KEY>_f<srcId>` DuckDB tables for the *entire* file before returning. Three
code-grounded consequences: (1) the reference `TypedRecordIngester` accumulates **every row in a
heap `List<String[]>`** before bulk-insert → OOMs the JVM before scratch is even touched; (2)
`PluginBatchStrategy` then does a union copy (`raw_<KEY>_f<srcId>` → `raw_<KEY>` →
`transformed_<KEY>`) = up to **3× materialisation**, with no chunked branch (Phase C touched only
`CsvBatchStrategy`); (3) the CSV auto-chunker **cannot** help — `FileChunker` splits on newlines,
but an opaque binary/ASN.1 record boundary is knowable only by the decoder. The chunking
responsibility must **invert**: only the ingester knows where records end.

**Decision.** Add an **additive streaming SPI** (not a breaking change to `FileIngester`):

- `com.gamma.etl.StreamingFileIngester` (`@PublicApi` 3.10.0) — `ingest(file, RecordSink, srcId, cfg)`.
  The ingester decodes and **emits records** rather than building tables.
- `com.gamma.etl.RecordSink` (`@PublicApi` 3.10.0) — framework callback: `define` / `emit` /
  `reject` / `junk`.
- `DuckDbRecordSink` (internal) — the bridge. Two levels of bounding: **append batching** (heap →
  DuckDB every 10k rows, so heap never holds more than one batch) and **generation flushing** (once
  a segment's raw table hits a row budget, that generation is transformed → written
  (`<stem>_gNNNNN_out.*`) → lineage-counted → dropped, so peak scratch ≈ one generation). Uses the
  *same* `DataTransformer.materialize` / `PartitionWriter.write` / `LineageCollector.collect` as the
  classic paths, over the same `transformed_<KEY>` shape, carrying `__src_id` as a trailing raw
  column → output is identical, only the scheduling differs.
- `StreamingPluginBatchStrategy` (internal) — per-member streaming, no cross-member union (each
  member writes its own generations, same trade-off as the CSV chunker). `BatchProcessor` routes to
  it when the configured ingester class `instanceof StreamingFileIngester` (streaming wins if a
  class implements both).

**Semantics preserved.** Decode error / `IOException` → `QUARANTINED_UNREADABLE`; zero emitted rows
→ `QUARANTINED_MISMATCH`; both mirror the classic path. A framework-side flush failure is wrapped in
`SinkFlushException` (a `RuntimeException`) so it fails the **batch** rather than mis-quarantining the
input as unreadable — the one semantic the classic path couldn't express (its transform ran *after*
ingest). New deps: **none**. New tests: `StreamingPluginBatchStrategyTest` (multi-generation row
conservation, BatchProcessor routing, both quarantine paths).

**Why a constant generation budget (5,000,000 rows), not config.** Kept the `@PublicApi`
`Chunking`/`DuckDbSettings` records stable and avoided new `.toon` surface this cycle. The budget is
a `StreamingPluginBatchStrategy` constructor param (tests inject a tiny value); exposing it as
`processing.streaming.flush_records` is a clean, additive follow-up if ops needs to tune the
scratch-vs-output-fragmentation trade-off. Other follow-ups (not done): cross-member / cross-generation
parallelism (sequential today); applying the same streaming treatment to Stage-2 enrichment (still
whole-batch).

---

## Cross-cutting conventions

- **SLF4J everywhere in the framework.** ETL code (`com.gamma.etl.*`, `com.gamma.inspector.*`) uses SLF4J at INFO/WARN/ERROR. Pre-ETL utility commands (`com.gamma.util.*` CLI tools) keep `System.out.println` because their output is for the human invoker, not log scrapers. `LogSetup` still tees stdout to a per-run file for diagnostics.

- **DuckDB connection per batch.** Each batch's `BatchIngestStrategy` (`CsvBatchStrategy` / `PluginBatchStrategy` / `StreamingPluginBatchStrategy`) opens its own temp DuckDB file via `DuckDbUtil.tempDbFile` and closes it in `finally`. No pooling, no sharing. Worth it for crash isolation. Each connection's internal parallelism is capped by `DuckDbUtil.applyWorkerThreads(conn, cfg.processing().duckdbThreads())` immediately after open — set `processing.duckdb_threads` so `threads × duckdb_threads ≈ cores`, else concurrent batches oversubscribe (the outer `Semaphore(cfg.processing().threads())` only bounds batch count, not DuckDB's per-connection threads).

- **Two CSV ingest engines.** `DuckDbCsvIngester` (native `read_csv`, vectorized, 4–5× faster) and `CsvIngester` (Java line-by-line, lenient with ragged rows) are interchangeable behind the same `ingest(...)` signature. `CsvBatchStrategy` picks via `DuckDbCsvIngester.usesDuckDb(cfg)`. The Java path is the fallback for messy configs (`skip_tail_columns` etc.) the native reader can't faithfully reproduce — see `docs/performance.md` for the semantic-difference analysis. Don't delete the Java path; it's load-bearing for the messy-file sources.

- **`raw_<KEY>_f<srcId>` table naming.** Hard-coded convention used by `PluginBatchStrategy` to union member tables; plugin authors must obey it (documented in `FileIngester` Javadoc). The **streaming** path (`StreamingFileIngester` → `DuckDbRecordSink`) owns table creation itself, so streaming authors never name tables — they only `emit`.

- **Markers go last in `commit()`.** See `BatchProcessor.commit` for the ordering rationale comment. Any future commit-step change must preserve "markers signal durability" — if you create a marker for a file whose backup hasn't moved, that file is stranded.
