# Architecture & Design

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

## Design Philosophy & Scope

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

### Deliberate non-goals

The engine **does not** — by design, not by omission:

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
[Batch Processing](operations.md#batch-processing)). Adding joins or lookups would force
shared state, serialize the batch model, and break the clean M..N routing. If
you need heavy joins or enrichment, do it **downstream** — query the
Hive-partitioned Parquet output through the
[warehouse query layer](integrations.md#warehouse-query-layer--dbeaver-via-pg_duckdb) or a
DuckLake catalog, where a real SQL engine can join across the whole dataset. The
multiplexer's job ends at partition-and-write.

### Format-specific configuration

Because different source formats need different handling, configuration is
organized **per format** rather than forced into one shape — and that
divergence is intended. Delimited text uses `csv_settings`; binary/proprietary
formats use a plugin `ingester` plus `ingester_config`. See
[Configuration by source format](configuration.md#configuration-by-source-format) for the map.

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
    BatchProcessor           — owns one temp DuckDB per batch; ingest loop → transform → lineage → commit
    ReprocessCommand         — `ura reprocess <batch_id>`: delete outputs/markers, restore members, re-run
  etl/
    PipelineConfig           — immutable config object; static factory loads and validates .toon
    SchemaSelector           — two-pass schema dispatch (file-pattern fast path + column-count probe)
    CsvIngester              — Java line-by-line CSV/CSV.GZ parser → DuckDB staging table (fallback engine)
    DuckDbCsvIngester        — native vectorized read_csv ingest (4-5× faster; default for clean configs)
    DataTransformer          — applies typed SQL transformations; writes Parquet/CSV output (two-stage)
    MarkerManager            — .processed sentinel files for idempotent ingest; retention-based cleanup
    QuarantineManager        — moves zero-valid-row and unreadable files to quarantine/
    DuckLakeRegistrar        — optional: registers written Parquet files into a DuckLake catalog
    BatchAuditWriter         — thread-safe append to the per-run status, batches, and lineage CSVs
    BatchPlanner             — groups polled files into batches respecting max_files / max_bytes limits
    ManifestStore            — writes and reads per-batch JSON manifests under status_dir/manifests/
    LineageCollector         — tracks input-to-output row counts for the lineage CSV; dynamic partition paths
    IngestResult             — record: parsedRows, errorRows, junkCandidateRows
    PartitionOutput          — record: output paths and sizes produced by one batch
    FileIngester             — plugin interface for custom parsers; ingest() returns one Segment per event type
    PartitionDef             — record + enum for explicit partitions[] declarations; backward-compat fromSchema()
  ingester/
    TypedRecordIngester      — reference FileIngester for type-tagged text records; multi-segment dispatch
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

---

## Directory Layout

```
sandbox-root/                ← working directory for local runs
  file-processor/
    config/
      <data_source>/
        adj_gen.toon               ← generation profile (hand-authored)
        <data_source>_schema.toon     ← field definitions + mapping rules
        <data_source>_pipeline.toon   ← runtime settings (dirs, threads, format)
        test_pipeline.toon         ← lightweight CSV test pipeline
    src/                     ← Java source
    target/
      file-processor-1.3.0.jar   ← fat JAR (built by mvn package)
    pom.xml
    package.ps1              ← builds + bundles a deployment zip
    README.md
  inbox/
    <data_source>/              ← drop input files here (date sub-folders created by utilities)
  database/
    <data_source>/              ← partitioned Parquet output
  backup/
    <data_source>/              ← original source files archived after processing
  temp/
    <data_source>/              ← scratch space for tar extraction (auto-cleaned)
  errors/
    <data_source>/              ← per-file error CSVs (rows rejected during ingest)
  quarantine/
    <data_source>/              ← files quarantined by SourceProcessor (wrong-schema / unreadable)
  markers/
    <data_source>/              ← .processed sentinel files (mirrors inbox tree; auto-pruned by retention_days)
  status/
    <data_source>/              ← per-run audit CSVs: <data_source>_etl_status_<timestamp>.csv
  logs/
    <data_source>/              ← per-run log files: <data_source>_etl_log_<timestamp>.log
  warehouse_setup.sql        ← pg_duckdb warehouse schema, views, and RBAC (run once on server)
```

All `dirs.*` paths in pipeline configs are relative to the **sandbox root** (the JVM working directory).

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
1. Streams raw CSV lines into a per-worker DuckDB staging table
2. Applies typed SQL transformations via DuckDB
3. Writes partitioned output (Parquet or CSV)
4. Optionally registers output into DuckLake
5. Writes a `.processed` marker and updates the status log

---

