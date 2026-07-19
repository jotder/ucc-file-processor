# Architecture & Design
> *Moved from `docs/architecture.md` (docs consolidation, 2026-07-16).*

> Part of the [Inspecto](../../../../inspecto/README.md) documentation. See the [docs index](../../../INDEX.md).

## Design Philosophy & Scope

> **Scope note (updated 2026-07-07).** This page describes **Stage-1**: the M..N multiplexer ingest
> engine (`com.gamma.etl` + `com.gamma.inspector`) — still the heart of the data plane, and still
> accurate below. Around it the platform has since grown, in order: the **Stage-2 enrichment engine**
> (`com.gamma.enrich`, 2.x) which deliberately *does* the joins/aggregations listed below as Stage-1
> non-goals; the **service + control plane** (`SourceService` + `ControlApi` — Jobs, Signals/events,
> Metrics, audit, and since 4.8 the versioned **`/api/v1`** contract); **authored Pipelines**
> (`com.gamma.pipeline` — DAGs of Steps run as `type: pipeline` Jobs); **multi-space tenancy**
> (`spaces/<id>/…`); the **Component metamodel** + derived registry; **editions** as build flavors
> (Personal/Standard/Enterprise; `inspecto-security` for Standard); and the optional **assist agent**
> (vendored kernel + eoiagent model transport). The whole-platform map lives in the
> [OKF knowledge bundle](../../index.md); the package-level **layer map** (dependency layering, SPI
> surface, event/config/storage/threading models) in [`architecture-layers.md`](../architecture-layers.md);
> the authored-Pipeline design in [`pipeline-graph-design.md`](../pipeline-graph/pipeline-graph-design.md). The non-goals below are **still correct for
> Stage-1** — they are what keep each batch embarrassingly parallel and crash-isolated.

This is a deliberately small ETL engine built around one idea: an **M..N
multiplexer**. A batch of **M** input files is demultiplexed and routed into
**N** partitioned output files — explicitly *not* one-to-one. Understanding this
framing explains every design choice that follows, and the non-goals are as
important as the goals.

### The M..N multiplexer

Records flow from many inputs to many outputs; the input file count and the
output file count are decoupled. Two complementary mechanisms do the routing:

- **Partition fan-out (the "N").** Every surviving record is routed to an output
  file by its partition key — typically `year/month/day` derived from a date
  column, optionally prefixed by other columns (e.g. `event_type`). A batch of
  M files spanning 30 days produces ~30 partition files per segment, regardless
  of how the input was split across files.
- **Segment demultiplexing (plugin path).** A single input file can carry
  multiple record types (e.g. CALL + SMS interleaved in one CDR file). The
  [plugin ingester](plugins.md#plugin-ingester) splits these into independent typed
  streams, each with its own schema and its own partitioned output tree. One
  input → many typed outputs.

Combined: **M input files → unioned per record type → N partitioned outputs.**
The batch is the unit of work; partition key and segment type are the routing
keys.

### Minor, per-record transformations only

Transformations are **stateless and applied to each record independently** —
the kind of work that maps cleanly onto DuckDB's vectorized SQL:

- Type coercion — VARCHAR → `DATE` / `TIMESTAMP` / `DOUBLE` / `INTEGER`
- Column selection and renaming (raw `selector` index → target column)
- Partition-key derivation (`YEAR()` / `MONTH()` / `DAY()` from a date)
- Lightweight composition — `CONCAT_DT` (date + time → timestamp),
  `FILENAME_DATE` (extract a date embedded in the filename)

That's the whole transformation vocabulary. It's intentionally narrow.

### Deliberate non-goals (Stage-1)

The **Stage-1 ingest engine does not** — by design, not by omission (Stage-2 enrichment
does these; see the scope note above):

- **Join against external / reference data.** No dimension lookups, no
  enrichment from a second source, no foreign-key resolution.
- **Aggregate across records.** No `GROUP BY` rollups, no windowing, no dedup
  across rows. (The only cross-record operation is the lineage count matrix,
  which is audit metadata, not output data.)
- **Hold state across records or batches.** Each record is transformed in
  isolation; each batch is independent.

### Why the non-goals matter

These constraints are what make the engine fast, parallel, and crash-isolated.
Because no batch needs shared reference state, every batch is an embarrassingly
parallel, single-pass unit with its own DuckDB connection (see
[Batch Processing](../build-run/operations-reference.md#batch-processing)). Adding joins or lookups would force
shared state, serialize the batch model, and break the clean M..N routing. If
you need heavy joins or enrichment, do it **downstream** — query the
Hive-partitioned Parquet output through the
[warehouse query layer](../integrations.md#warehouse-query-layer--dbeaver-via-pg_duckdb) or a
DuckLake catalog, where a real SQL engine can join across the whole dataset. The
multiplexer's job ends at partition-and-write.

Since the 2.x line, that downstream answer is **first-class and in-platform**: the
**Stage-2 enrichment engine** (`com.gamma.enrich`) reads the Hive-partitioned output as
views and runs exactly these joins/aggregations as its own `transform` SQL, idempotently and
incrementally (event- or schedule-driven), under the same service/control plane. So "do it
downstream" no longer means "leave the platform" — Stage-1 stays a clean multiplexer, and
Stage-2 owns the cross-record work. See [v3-architecture.md](../../../archived-documents/v3-architecture.md) for the
two-stage map.

### Format-specific configuration

Because different source formats need different handling, configuration is
organized **per format** rather than forced into one shape — and that
divergence is intended. Delimited text uses `csv_settings`; binary/proprietary
formats use a plugin `ingester` plus `ingester_config`. See
[Configuration by source format](../config/configuration.md#configuration-by-source-format) for the map.

---

## Architecture

```
┌──────────── PRE-ETL: staging (MainApp utility commands) ──────────────────────┐
│                                                                               │
│  base.dirs/  ──search────►  available_files.csv      (file manifest audit)    │
│  base.dirs/  ──copy──────►  dirs.poll/<date>/        (CSVs arranged by date)  │
│  base.dirs/  ──copy-tars──► dirs.poll/               (tar.gz staged flat)     │
│  dirs.poll/  ──extract───►  dirs.poll/<date>/        (tars unpacked, arranged)│
│  available files(csv|csv.gz) ─backup──► dirs.backup  (originals archived)     │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 1: Bootstrap (once per source) ──────────────────────────┐
│                                                                               │
│   <source>_gen.toon ───┐                                                      │
│                        ├──►  create-schema ──► <source>_schema.toon           │
│   sample.csv  ─────────┘                  └──► <source>_pipeline.toon         │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 2: ETL Processing (continuous) ──────────────────────────┐
│                                                                               │
│   <source>_pipeline.toon ─┐                                                   │
│   <source>_schema.toon ───┼──► SourceProcessor ├──►  Parquet / CSV            │
│   inbox/<date>/*.csv.gz ──┘                    └──►  DuckLake (optional)      │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 3: Analytics Query Layer (optional) ─────────────────────┐
│                                                                               │
│   warehouse_setup.sql ──► pg_duckdb extension ──► PostgreSQL :5432            │
│                                    │                      ▲                   │
│   database/**/*.parquet ───────────┘                      │                   │
│                                                           │                   │
│   DBeaver / any PG client ────────────────────────────────┘                   │
│   (standard PostgreSQL driver; DuckDB transparent to users)                   │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

The **Pre ETL utilities** (`MainApp`) handle the movement and unpacking of raw deliveries into the inbox layout that `SourceProcessor` expects.

**SchemaExtractor** (`create-schema` command) is a one-time bootstrap tool — it reads a generation profile and a sample file and produces the schema and pipeline configs.

**SourceProcessor** is the runtime engine — it reads the generated configs, polls the inbox directory, and processes every file it finds.

### Package Structure

```
com.gamma
  inspector/
    SourceProcessor          — single-source ETL runner; virtual-thread + semaphore batch fan-out
    MultiSourceProcessor     — runs many sources concurrently in one JVM (outer M..N orchestrator)
    BatchProcessor           — thin per-batch coordinator: selects a BatchIngestStrategy, then drives the shared commit → audit tail
    BatchIngestStrategy      — ingest+transform+write seam (delimited-text vs plugin); returns a typed IngestOutcome (+ shared dropTable/msg helpers)
    CsvBatchStrategy         — built-in delimited-text path → transform → write → lineage. Native (read_csv) batches stream with
                               NO raw_f/raw_input table copies: single member via one read_csv VIEW, many members via
                               per-member views UNION ALL-ed into one transform (materialised once). Files over
                               processing.chunking.max_file_bytes are streamed in bounded chunks (FileChunker). The Java
                               parse engine keeps the per-file temp table → raw_input(__src_id) staging path.
    FileChunker              — streams an oversized file into bounded, header-replicating chunks (one on disk at a time)
    StreamingPluginBatchStrategy — the plugin path (StreamingFileIngester): per batch, picks union mode (many small
                               files → emit into per-member tables → union per segment → one transform/write/lineage) or
                               generation mode (huge file → DuckDbRecordSink flushes bounded generations) by file size
    DuckDbRecordSink         — framework RecordSink impl: Appender-loads emitted rows to DuckDB; generation-flushes on a
                               row budget, or (union mode) leaves raw tables for the strategy to union
    IngestOutcome            — record: status, survivors, outputs, lineage, per-member audit, totals (commit/audit input)
    MemberAudit              — record: per-input-file audit row accumulated during ingest
    ReprocessCommand         — `ura reprocess <batch_id>`: delete outputs/markers, restore members, re-run
  etl/
    PipelineConfig           — immutable config object; static factory loads and validates .toon
    SchemaSelector           — two-pass schema dispatch (file-pattern fast path + column-count probe)
    CsvIngester              — Java line-by-line CSV/CSV.GZ parser → DuckDB staging table (fallback engine)
    DuckDbCsvIngester        — native vectorized read_csv ingest (4-5× faster; default for clean configs)
    DataTransformer          — assembles the transform SELECT (CREATE TABLE AS); delegates per-column SQL to TransformCompiler
    TransformCompiler        — pure per-column SQL compiler; transformType → expression function registry (functional injection)
    PartitionWriter          — COPY … PARTITION_BY + two-step atomic file reveal; format via the OutputFormat enum-strategy
    OutputFormat             — enum-as-strategy: extension + COPY token + compression applicability per output format
    MarkerManager            — .processed sentinel files for idempotent ingest; retention-based cleanup
    QuarantineManager        — moves zero-valid-row and unreadable files to quarantine/
    DuckLakeRegistrar        — optional: registers written Parquet files into a DuckLake catalog
    BatchAuditWriter         — thread-safe append to the per-run status, batches, and lineage CSVs
    BatchPlanner             — groups polled files into batches respecting max_files / max_bytes limits
    ManifestStore            — writes and reads per-batch JSON manifests under status_dir/manifests/
    LineageCollector         — tracks input-to-output row counts for the lineage CSV; dynamic partition paths
    IngestResult             — record: parsedRows, errorRows, junkCandidateRows
    PartitionOutput          — record: output paths and sizes produced by one batch
    StreamingFileIngester    — the plugin SPI for custom parsers; ingest() emits records into a RecordSink
    RecordSink               — framework callback the ingester emits into (define/emit/reject/junk)
    PartitionDef             — record + enum for explicit partitions[] declarations; backward-compat fromSchema()
  ingester/
    TypedRecordIngester      — reference StreamingFileIngester for type-tagged text records; multi-segment dispatch
  util/
    ToonHelper               — load/validate .toon files; require/opt section helpers; parseBaseDirs
    TarUtil                  — isTar/isCsv/isGzipped predicates; extractTar; peekTar; deleteTree
    VirtualThreadRunner      — Phaser + VirtualThreadPerTaskExecutor fan-out helper
    SqlBuilder               — typed DuckDB SELECT expressions (COALESCE/TRY_STRPTIME chains)
    LogSetup                 — TeeOutputStream; per-run timestamped log file wired to stdout/stderr
    DuckDbUtil               — JDBC URL builder; temp DB file lifecycle; datetime formatter
    FileOrganizer            — search base_dirs for manifest files; optionally copy to poll dir
    FileBackup               — move found_path originals to dirs.backup after a search/copy run
    TarArranger              — copy-tars (collect archives) + extract (unpack + arrange by date)
    TarInboxPreparer         — toon-native alias for the extract workflow
    TarExtractor             — recursive walk for 'unknown/' tar archives; sentinel-based skip
    IntegratedProcessor      — extract + move for CBS CDR adjustment archives
    SchemaExtractor          — DuckDB-powered type inference; generates _schema.toon + _pipeline.toon
    MainApp                  — CLI dispatcher for all pre-ETL utility commands
    ParquetSummarizer        — count rows and bytes across a database/ Parquet tree
    PartitionSummarizer      — partition-level statistics for one or more tables
    FileMoverByDate          — date-partition files from a flat directory into year/month/day tree
```

**Design principle:** `SourceProcessor` is a pure orchestrator — it creates the thread pool and drives the per-batch lifecycle, but contains zero business logic itself.  Every concern is owned by a focused single-responsibility class: batch planning (`BatchPlanner`), parsing (`CsvIngester`), transformation (`DataTransformer`), deduplication (`MarkerManager`), quarantine (`QuarantineManager`), registration (`DuckLakeRegistrar`), and auditing (`BatchAuditWriter`, `ManifestStore`, `LineageCollector`).  All shared low-level helpers live in `com.gamma.util` and are reused by both the ETL and the pre-ETL utilities.

**Behavior-injection seams.** Variant behavior is injected into the engine rather than branched inline, so the orchestration code stays thin and a new variant is a closed-set edit:

- **`BatchIngestStrategy`** (`CsvBatchStrategy` / `StreamingPluginBatchStrategy`) — the per-batch ingest+transform+write path. `BatchProcessor.process` selects one by config (the built-in delimited-text path when no `ingester`, else the streaming plugin engine) and consumes its typed `IngestOutcome`; the shared commit → audit tail is path-agnostic. The plugin engine then self-selects union vs generation mode per batch by file size.
- **`StreamingFileIngester`** (SPI, by FQCN) — custom parsers emit records into a `RecordSink`; the reference `TypedRecordIngester` splits one input into many typed segment streams.
- **`TransformCompiler`** — a `transformType → ColumnRule` function registry; `DataTransformer` assembles the SELECT and delegates each column expression.
- **`OutputFormat`** — enum-as-strategy owning each format's extension, COPY token, and compression rule, used by `PartitionWriter`.
- **`StatusStore`** (`FileStatusStore` / `DbStatusStore`) and the **`BatchEventBus`** (`Consumer<BatchEvent>` observers) — pluggable audit backend and commit-event fan-out.

These keep the data path lean while making formats, ingest paths, transforms, and audit sinks independently extensible and testable. See [design-notes → D7](../../../archived-documents/design-notes.md#d7--engine-modularity-pass-behavior-injection-seams--done-v390).

---

## Directory Layout

Since multi-space (4.x), everything an installation owns lives under a **Space** directory
(`-Dspaces.root`, default `./spaces`); single-space installs use the `default` Space. Legacy flat
layouts are migrated once via `com.gamma.service.SpaceMigrator` — there is no flat fallback.

```
sandbox-root/                       ← working directory for local runs (the JVM CWD)
  packs/                            ← host-wide Job Packs (job-framework §12; -Djobs.packs.dir default)
  spaces/                           ← -Dspaces.root; one directory per Space
    _shared/                        ← THE EXCHANGE (cross-Space sharing) — reserved, not a Space
    <space-id>/
      space.toon                    ← Space manifest
      config/<data_source>/         ← *_gen.toon, *_schema.toon, *_pipeline.toon (+ grammars, jobs, alerts)
        share-grants/               ← owner-side Share Grants (cross-Space dataset/widget sharing)
      data/…                        ← the per-pipeline dirs.* trees: inbox, partitioned Parquet
                                      output (Tables), backup, errors, quarantine, markers,
                                      status, temp scratch, logs
      audit/                        ← batch/file audit ledgers
      duckdb/                       ← per-Space DuckDB state
      flows/                        ← authored Pipeline definitions (storage dir name is historical)
      views/                        ← ViewDefinitions (T32-C)
  warehouse_setup.sql               ← pg_duckdb warehouse schema, views (run once on server)
```

**Layout contract (§L0).** The four persistence axes — `config/` (authored definitions), `data/` (the
data plane), `audit/` (ledgers) and `duckdb/` (embedded-DB state) — must never mix. This is checked at
Space boot by `com.gamma.service.SpaceLayoutContract` and is **advisory**: every departure emits a
`LAYOUT_CONTRACT_VIOLATION` WARN event, never a boot failure (matching the warning-and-skipping posture
of `SpaceManager.discover`). Every new subsystem must declare its home here before landing:

| Subsystem | Home | Axis |
|---|---|---|
| Job run logs | `audit/` | audit |
| Job Run Artifacts DB | `duckdb/` | duckdb |
| Job Packs (host-wide) | `<install-root>/packs/` | (install scope) |
| Share Grants (owner-side) | `config/share-grants/` | config |
| The Exchange (cross-Space) | `spaces/_shared/` | (install scope, reserved) |
| ViewDefinitions | `views/` | config-adjacent |

Contract rules enforced by the check: canonical subdirs (`data/ audit/ duckdb/`) must exist; no
embedded-DB file (`*.duckdb`/`*.db`/`*.wal`) may sit outside `duckdb/`; no unrecognised top-level entry
may appear at the Space root. `_shared/` under `-Dspaces.root` is **not** a Space (discovery admits only
dirs with a `config/` subtree, so it is skipped) — see
[storage-layout-and-sharing-plan](../../../archived-documents/plans-archive/storage-layout-and-sharing-plan.md) §2.

⚠️ **All `dirs.*` paths in pipeline configs resolve against the JVM working directory, not the Space
root** — write them bundle-root-relative (`spaces/<id>/config/…`, `spaces/<id>/data/…`). Only the
Space *discovery* layer (`-Dspaces.root`, `SpaceRoot`) is Space-relative; `SpaceMigrator` cannot
rewrite absolute or author-relative paths for the same reason.

---

## Two-Step Process

### Step 1 — Schema bootstrapping (run once)

```
create-schema  <source_name>  <sample_file.csv>  <gen_config.toon>
```

Reads the generation profile, auto-infers column types from the sample, and writes:
- `<source>_schema.toon` — field definitions and mapping rules (the ETL source of truth)
- `<source>_pipeline.toon` — runtime directories, threading, output format

These generated files are committed to version control and edited as needed. **SchemaExtractor is never involved in production processing.**

### Step 2 — ETL processing (continuous)

```
SourceProcessor  <pipeline_config.toon>
```

Reads both generated config files, polls the inbox directory, and for each unprocessed file:
1. Ingests the file into a per-worker DuckDB staging table (the built-in delimited-text reader, or a custom plugin ingester)
2. Applies typed SQL transformations via DuckDB
3. Writes partitioned output (Parquet or CSV)
4. Optionally registers output into DuckLake
5. Writes a `.processed` marker and updates the status log

---

