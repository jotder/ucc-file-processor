# UCC File Processor
High-throughput, configuration-driven ETL pipeline that ingests CSV and binary files, applies typed transformations via DuckDB, and writes Hive-partitioned Parquet or CSV output. Onboard a new CSV source with a single config file; plug in a custom Java parser for proprietary or binary formats that emit multiple event types.

Includes a set of **Pre ETL utility suite** for sourcing, staging, and arranging raw deliveries before the pipeline picks them up.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Features](#features)
3. [Directory Layout](#directory-layout)
4. [Two-Step Process](#two-step-process)
5. [Quick Start](#quick-start)
6. [Utility Suite](#pre-etl-utility-suite)
   - [Pipeline configurations sections for utilities](#pipeline-toon-sections-for-utilities)
   - [Commands](#commands)
   - [Typical workflow](#typical-pre-etl-workflow)
7. [Configuration Reference](#configuration-reference)
   - [Generation Config](#1-generation-config-source_gentoon)
   - [Schema Config](#2-schema-config-source_schematoon)
   - [Pipeline Config](#3-pipeline-config-source_pipelinetoon)
     - [Multi-schema dispatch](#multi-schema-dispatch)
8. [Output Structure](#output-structure)
9. [Status Log & Auditing](#status-log--auditing)
10. [Batch Processing](#batch-processing)
11. [Plugin Ingester](#plugin-ingester)
    - [FileIngester interface](#fileingester-interface)
    - [Segment schema toon (partitions\[\])](#segment-schema-toon-partitions)
    - [Pipeline config](#pipeline-config-plugin)
    - [Output layout](#output-layout)
12. [DuckLake Integration](#ducklake-integration)
13. [Warehouse Query Layer](#warehouse-query-layer--dbeaver-via-pg_duckdb)
14. [Deployment — Remote Server](#deployment--remote-server)
15. [Onboarding a New Source](#onboarding-a-new-source)
16. [Type Mapping Reference](#type-mapping-reference)
17. [Troubleshooting](#troubleshooting)

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
    SourceProcessor          — thin ETL orchestrator; drives the batch lifecycle via the thread pool
    BatchProcessor           — owns one temp DuckDB per batch; ingest loop → transform → lineage → commit
    ReprocessCommand         — `ura reprocess <batch_id>`: delete outputs/markers, restore members, re-run
  etl/
    PipelineConfig           — immutable config object; static factory loads and validates .toon
    SchemaSelector           — two-pass schema dispatch (file-pattern fast path + column-count probe)
    CsvIngester              — streams CSV/CSV.GZ into a DuckDB raw_input staging table
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

## Features

| Feature | Detail |
|---|---|
| **Generic** | Onboard any CSV source with one hand-authored config file |
| **Three-tier config** | Generation → Schema → Pipeline; each layer has a single responsibility |
| **Pre-ETL utilities** | 6 independent commands to search, stage, extract, and archive raw deliveries |
| **Adaptive junk detection** | Skips SQL\*Plus preamble lines (fixed + variable, e.g. ORA-28002 password expiry) |
| **Multi-format dates** | `COALESCE(TRY_STRPTIME(...))` chains handle multiple Oracle date formats per column |
| **Typed output** | DATE, TIMESTAMP, DOUBLE, VARCHAR — all cast safely; bad values land as NULL, not crashes |
| **Hive partitioning** | `year=YYYY/month=MM/day=DD` directory structure, partition key from config |
| **Dynamic filenames** | Output file named after the input: `adj_DATE_20200403_out.parquet` |
| **CSV or Parquet output** | Switched per pipeline via `output.format`; Snappy compression for Parquet |
| **Parallel processing** | Virtual-thread executor + Phaser coordination throughout — both ETL and utilities |
| **Idempotency** | Marker files (`.processed`) in a dedicated `dirs.markers` directory prevent re-ingestion; stale markers are pruned automatically based on `retention_days`; existing-file skips in all utilities |
| **Multi-schema dispatch** | `schemas[]` array routes files to different schemas by filename pattern (fast path, no I/O) or column-count probe (fallback) — one pipeline handles multiple CSV layouts simultaneously |
| **Plugin ingester** | `processing.ingester:` loads a custom `FileIngester` from the fat JAR; one input file can emit multiple event-type segments that land in separate partitioned tables with independent schemas |
| **Headerless CSV** | `has_header: false` in `csv_settings` — files without a column-name header row are processed using `selector` index only |
| **Full audit log** | Per-file status CSV with start/end time, parsed rows, error rows, output paths and sizes |
| **Error row capture** | Rejected rows written to `errors/<basename>_errors.csv` with line number and reason |
| **Quarantine** | Wrong-schema and unreadable files are automatically isolated — never retried |
| **Directory safety** | Startup validation ensures output dirs (backup, temp, errors, quarantine, markers) are never inside the poll directory |
| **DuckLake registration** | Optional: registers written Parquet files into a DuckLake catalog backed by PostgreSQL |
| **Fat JAR deployment** | Single `mvn clean package` produces a fully self-contained deployable JAR |

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

## Quick Start

### Build

```powershell
cd file-processor
mvn clean package
# Produces: target/file-processor-1.3.0.jar  (~94 MB, all deps bundled)
```

### Generate schema for a new source

```powershell
# From the file-processor/ directory (Windows)
ura.bat create-schema <data_source> path\to\sample.csv config\<data_source>\adj_gen.toon

# Linux / Mac
./ura.sh create-schema <data_source> path/to/sample.csv config/<data_source>/adj_gen.toon
```

### Run the ETL pipeline

```powershell
# <data_source> — from file-processor/ directory (Windows)
run.bat <data_source>

# <data_source> (Windows)
run.bat <data_source>

# Linux / Mac
bash run.sh <data_source>
bash run.sh <data_source>
```

### Drop files and run

Place `.csv` or `.csv.gz` files under `inbox/<adapter>/` (in date sub-folders) and run the pipeline. Already-processed files are skipped automatically: a `.processed` marker file is written into `markers/<adapter>/` after each successful file. Markers older than `retention_days` (default 90) are pruned automatically at each poll start.

---

## Pre-ETL Utility Suite

All pre-ETL utilities are invoked through the `ura` script (shipped alongside the JAR). Configuration is read directly from the pipeline `.toon` file — no separate properties file is needed.

```bash
# Deployed bundle
bash ura.sh [--dry-run] <command> <pipeline.toon>   # Linux / Mac
ura.bat     [--dry-run] <command> <pipeline.toon>   # Windows

# Local development (from file-processor/ directory)
./ura.sh    [--dry-run] <command> <pipeline.toon>   # Linux / Mac
ura.bat     [--dry-run] <command> <pipeline.toon>   # Windows (uses target/ JAR)
```

Run `ura.bat help` (or `bash ura.sh help`) for the full command reference.

### Pipeline toon sections for utilities

Each pre-ETL command has a dedicated top-level section in the pipeline `.toon` file alongside the existing `dirs`, `output`, and `processing` sections. Add only the sections relevant to the commands you will run.

**`search` section** — used by `search` and `copy` commands:
```yaml
search:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
  csv_input:     GSM_RESUBMISSION.csv
  log_available: available_files.csv
  log_missing:   missing_files.csv
  log_error:     error_log.csv
```

**`copy_tars` section** — used by `copy-tars` command:
```yaml
copy_tars:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
```

**`backup` section** — used by `backup` command:
```yaml
backup:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
  log_available: available_files.csv
```

The `extract` and `prepare-inbox` commands need no extra section — they use only the existing `dirs.poll`, `dirs.temp`, and `dirs.backup` keys.

> **Note:** JToon does not support `#` comments. Do not add comment lines to `.toon` files — parsing stops at the first unrecognised line.

**Safety constraint:** `dirs.poll` must not be nested inside any `base_dirs` entry. The command validates this at startup.

### Commands

| Command | Reads from toon | Description |
|---|---|---|
| `search` | `search.*`, `dirs.poll` | Scan base_dirs for files in csv_input. Log found/missing. No copy. |
| `copy` | `search.*`, `dirs.poll` | Scan base_dirs, copy matching files to `dirs.poll/<date>/`. |
| `copy-tars` | `copy_tars.base_dirs`, `dirs.poll` | Find `*.tar.gz` in base_dirs, copy flat to `dirs.poll`. |
| `extract` | `dirs.poll`, `dirs.temp`, `dirs.backup` | Unpack `*.tar.gz` in `dirs.poll`, arrange CSVs by date, backup archives. |
| `backup` | `backup.*`, `dirs.backup` | Move originals listed in available_files.csv to `dirs.backup`. |
| `prepare-inbox` | `dirs.poll`, `dirs.temp`, `dirs.backup` | Same as `extract` — toon-native alias for pipeline-level use. |
| `create-schema` | *(gen config toon, not pipeline)* | Generate `<source>_schema.toon` + `<source>_pipeline.toon`. Args: `<source_name> <sample_csv> <gen_config.toon>` |

**`--dry-run`** is honoured by all commands — prints intended actions without modifying any files.

#### Date detection

The `copy`, `extract`, and `prepare-inbox` commands arrange files into date-named sub-folders. The date is extracted from the filename using the pattern `((?:19|20)\d{6})` — the first 8-digit token in the range 1900–2099. Files with no matching token land in `obscure/`.

### Typical pre-ETL workflow

```bash
TOON="config/<data_source>/<data_source>_pipeline.toon"

# 1. Audit: find which files from the manifest exist in the source dirs
bash ura.sh search    $TOON

# 2. Copy matching CSVs to dirs.poll, arranged by date
bash ura.sh copy      $TOON

# 3. Copy any .tar.gz deliveries flat to dirs.poll
bash ura.sh copy-tars $TOON

# 4. Extract tar archives; arrange CSVs by date; backup archives
bash ura.sh extract   $TOON

# 5. Move all original source files to dirs.backup
bash ura.sh backup    $TOON

# 6. Run the ETL pipeline
bash run.sh <data_source>
```

Add `--dry-run` to any `ura.sh` step to preview actions without touching files.

**Windows equivalent** (from the bundle root):
```bat
set TOON=config\<data_source>\<data_source>_pipeline.toon
ura.bat search    %TOON%
ura.bat copy      %TOON%
ura.bat copy-tars %TOON%
ura.bat extract   %TOON%
ura.bat backup    %TOON%
run.bat <data_source>
```

---

## Configuration Reference

The framework uses three config files in `.toon` format (JToon). Only the generation config is hand-authored; the other two are machine-generated and then maintained.

Config files live under `file-processor/config/<adapter>/`.  All `dirs.*` and `schema_file` paths are relative to the **sandbox root** (the JVM working directory).

### 1. Generation Config (`<source>_gen.toon`)

**Hand-authored.** Tells SchemaExtractor how to read the sample file and which columns to force-type.

```yaml
csv_settings:
  delimiter: ","
  skip_header_lines: 0      # blank lines before the column-name header row
  skip_junk_lines: 13       # max lines to scan past header looking for first data row
  skip_tail_lines: 2        # footer lines to discard at EOF (SQL*Plus row-count etc.)
  skip_tail_columns: 0      # trailing source metadata columns to strip
  date_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
  timestamp_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"

type_patterns:
  dates[4]: REVERSAL_DATE, ENTRY_DATE, EVENT_DATE, POPULATION_DATE_TIME
  timestamps[4]: PRE_APPLY_TIME, PRE_EXPIRE_TIME, CUR_EXPIRE_TIME, STARTTIMEOFBILLCYCLE
```

**`skip_junk_lines`** is a *cap*, not a fixed count. SourceProcessor uses adaptive detection — it scans forward until it finds a line with enough columns that does not echo the header. Use `-1` to scan without limit (for sources like Oracle <data_source>s with variable-length preambles).

**Date/timestamp format arrays** use JToon inline syntax: `key[n]: val1, val2, ..., valN`. All values must be on a single line.

---

### 2. Schema Config (`<source>_schema.toon`)

**Machine-generated** by `create-schema`; edit manually if needed. This is the source of truth for the ETL transformation.

```yaml
# Option A — legacy single-key shorthand (CSV path)
partitionKey: REVERSAL_DATE          # column used to derive year/month/day partitions

# Option B — explicit partitions[] list (plugin ingester / multi-type events)
# partitions:
#   - column: event_type             # output partition column name
#     source: EVENT_TYPE             # column in the raw DuckDB table (set by ingester)
#     type: VARCHAR
#   - column: year
#     source: REVERSAL_DATE
#     type: DATE_YEAR
#   - column: month
#     source: REVERSAL_DATE
#     type: DATE_MONTH
#   - column: day
#     source: REVERSAL_DATE
#     type: DATE_DAY

raw:
  fields[3]:
    - name: ACCOUNT_NUMBER
      selector: 0                    # zero-based column index in the source CSV
      type: VARCHAR
    - name: REVERSAL_DATE
      selector: 4
      type: DATE
    - name: AMOUNT
      selector: 12
      type: DOUBLE

mapping:
  rules[3]:
    - targetColumn: ACCOUNT_NUMBER
      sourceExpression: ACCOUNT_NUMBER
      transformType: DIRECT
    - targetColumn: REVERSAL_DATE
      sourceExpression: REVERSAL_DATE
      transformType: DIRECT
    - targetColumn: AMOUNT
      sourceExpression: AMOUNT
      transformType: DIRECT
```

**`selector`** is the zero-based column index in the raw source CSV — decoupled from position in the output schema, so source column order changes do not break the pipeline.

**`transformType`** controls how the source expression is evaluated:

| Value | `sourceExpression` format | Description |
|---|---|---|
| `DIRECT` | column name | Pass-through with type cast (DATE, TIMESTAMP, DOUBLE, VARCHAR) |
| `CONCAT_DT` | `DATE_COL\|TIME_COL` | Concatenate two raw columns into a single TIMESTAMP: `COALESCE(TRY_STRPTIME(date \|\| ' ' \|\| time, ...))` |
| `FILENAME_DATE` | `COL\|PREFIX` or `COL\|PREFIX\|FORMAT` | Extract an 8-digit date from a filename-style column using a fixed prefix. The default format is `%Y%m%d`. **Restricted to `EVENT_DATE` only** — an `IllegalArgumentException` is thrown at startup if used on any other target column. |

**`FILENAME_DATE` example** — <data_source> CDR files carry the event date in the filename (`cbs_cdr_vou_20180409_601_101_057726.add`) rather than a data column:
```yaml
mapping:
  rules[1]{targetColumn,sourceExpression,transformType}:
    EVENT_DATE,FILENAME|cbs_cdr_vou_,FILENAME_DATE
```
The generated SQL is: `TRY_STRPTIME(regexp_extract(raw_input."FILENAME", 'cbs_cdr_vou_([0-9]{8})', 1), '%Y%m%d')::DATE`

---

### 3. Pipeline Config (`<source>_pipeline.toon`)

**Machine-generated** by `create-schema`; edit to adjust runtime settings. Also serves as the single configuration file for all pre-ETL utility commands.

```yaml
name: <data_source>_ETL
version: 1

dirs:
  poll:       inbox/<data_source>
  database:   database/<data_source>
  backup:     backup/<data_source>
  temp:       temp/<data_source>
  errors:     errors/<data_source>
  quarantine: quarantine/<data_source>
  markers:    markers/<data_source>
  status_dir: status/<data_source>
  log_dir:    logs/<data_source>

output:
  format: PARQUET
  compression: snappy
  ducklake:
    enabled: false
    catalog_url: "postgresql://user:password@localhost:5432/ducklake_db"
    data_path: "/opt/adj-lake"
    schema: <data_source>s
    table: <data_source>_data

processing:
  threads: 4
  file_pattern: "glob:**/*.{csv,csv.gz}"
  duplicate_check:
    enabled: true
    marker_extension: .processed
    retention_days: 90
  schema_file: "file-processor/config/<data_source>/<data_source>_schema.toon"
  csv_settings:
    delimiter: ","
    skip_header_lines: 0
    skip_junk_lines: 13
    skip_tail_lines: 2
    skip_tail_columns: 0
    date_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
    timestamp_formats[3]: %d-%b-%y, "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"

search:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
  csv_input:     GSM_RESUBMISSION_1.csv
  log_available: available_files.csv
  log_missing:   missing_files.csv
  log_error:     error_log.csv

copy_tars:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2

backup:
  base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
  log_available: available_files.csv
```

The `dirs`, `output`, and `processing` sections are used by `SourceProcessor` (the ETL runtime). The `search`, `copy_tars`, and `backup` sections are used exclusively by the pre-ETL utility commands in `MainApp`. All sections coexist in a single file — the one file configures everything for a source.

All seven core `dirs.*` entries are required for SourceProcessor (`poll`, `database`, `backup`, `temp`, `errors`, `quarantine`, `markers`). The optional `status_dir` and `log_dir` entries enable per-run audit and log files. Startup validation confirms that all managed directories are not nested inside the `poll` directory.

**`dirs.markers`** — dedicated directory for `.processed` sentinel files. Mirrors the poll directory tree: a file at `inbox/<data_source>/20200403/feed.csv.gz` produces a marker at `markers/<data_source>/20200403/feed.csv.gz.processed`. Markers are pruned automatically at each poll start; any marker file older than `processing.duplicate_check.retention_days` days is deleted and empty subdirectories are removed. This keeps the markers directory bounded in size without manual intervention.

**`dirs.status_dir`** — directory for per-run status CSVs. Each ETL run creates a new file named `<pipeline_name>_status_<yyyyMMdd_HHmmss>.csv` — runs never overwrite each other. Omit (or leave blank) to disable the status log.

**`dirs.log_dir`** — directory for per-run log files. Each run creates `<pipeline_name>_log_<yyyyMMdd_HHmmss>.log`, capturing a tee of all stdout and stderr output. The timestamp matches the status file for easy correlation. Omit (or leave blank) to disable file logging.

**`processing.duplicate_check.retention_days`** — how far back duplicate detection reaches (default: `90`). A file delivered more than 90 days after its first processing will be treated as a new file and processed again. Increase this value for sources that occasionally re-deliver old data.

> **JToon note:** The `.toon` format does not support `#` comment lines. Parsing stops at the first unrecognised character, so do not add inline or standalone comments.

**`processing.csv_settings.has_header`** (default: `true`) — when set to `false`, the first data line is treated as a row rather than a column-name header. Use for source files that contain no header row; columns are bound to the schema by `selector` index. Omitting the key is equivalent to `true`.

#### Multi-schema dispatch

When a single pipeline must handle CSV files with different column layouts (e.g. three related feeds delivered to the same inbox), replace the single `schema_file:` key with a `schemas[]` inline array. Each entry maps a column count to a schema file and target table, with an optional filename glob for the fast path.

```yaml
processing:
  threads: 4
  file_pattern: "glob:**/*.csv"
  schemas[3]{column_count,file_pattern,schema_file,table}:
    76,  "glob:**/*other*", "config/<data_source>/<data_source>_76.toon",  <data_source>_other
    116, "glob:**/*main*",  "config/<data_source>/<data_source>_116.toon", <data_source>_main
    537, "",                "config/<data_source>/<data_source>_537.toon", <data_source>_cdr
  csv_settings:
    delimiter: ","
    has_header: false
    skip_header_lines: 0
    date_formats[1]: "%Y%m%d"
```

**Schema selection — two-pass algorithm:**

1. **File-pattern match (fast path, no file I/O)** — entries are checked in declaration order; the first whose `file_pattern` glob matches the input file path is selected. Entries with an empty `file_pattern` are skipped in this pass.
2. **Column-count probe (fallback)** — if no pattern matched, the file is opened, up to 200 non-blank lines are scanned, and the entry whose `column_count` equals the maximum column count seen is selected.

| Field | Required | Description |
|---|---|---|
| `column_count` | yes | Expected column count; used as the key for the fallback probe |
| `file_pattern` | no | Glob applied to the full input file path. Set to `""` to skip pattern matching for this entry (column-count probe only) |
| `schema_file` | yes | Path to the `_schema.toon` for this layout |
| `table` | yes | Logical table name used in log output and DuckLake registration |

> **Windows glob ordering:** Java's `PathMatcher` is case-insensitive on Windows — `glob:**/vou_*` matches `VOU_MAIN_2018.csv`. Always list the most specific patterns first; the first match wins. For entries that should be reached only via the column-count probe, set `file_pattern` to `""` rather than using a broad pattern that could steal files from more specific entries.

#### `output.format`

| Value | Effect |
|---|---|
| `CSV` | Writes `.csv` files; `compression` is ignored |
| `PARQUET` | Writes `.parquet` files with the specified compression codec |

#### `output.compression` (Parquet only)

| Value | Notes |
|---|---|
| `snappy` | Default. Fast, moderate compression — best for analytics workloads |
| `zstd` | Higher compression ratio, slightly slower |
| `gzip` | Maximum compatibility with external tools |

---

## Output Structure

Each input file produces one output file per partition it touches. The output file is named after the input file — no UUID noise.

```
database/<data_source>/
  year=1900/
    month=01/
      day=01/
        adj_DATE_20200116_out.parquet     ← REVERSAL_DATE sentinel (01-JAN-1900)
  year=2000/
    month=01/
      day=01/
        adj_DATE_20200101_out.parquet
        adj_DATE_20200403_out.parquet

errors/<data_source>/
  adj_DATE_20200403_errors.csv            ← only created when rows are rejected

quarantine/<data_source>/
  field_mismatch/
    wrong_source.csv                      ← 0 valid rows parsed
  unreadable/
    corrupt.csv.gz                        ← IOException during read
```

**Filename derivation:** `adj_DATE_20200403.csv.gz` → strip `.gz` → strip `.csv` → append `_out.parquet` → `adj_DATE_20200403_out.parquet`.

**Partition key:** The `partitionKey` field in the schema config names the column whose value drives `year`, `month`, and `day`. Rows where the partition key is NULL (unparseable date) land in `year=NULL/month=NULL/day=NULL`.

---

## Status Log & Auditing

Each ETL run creates a new, timestamped status CSV in `dirs.status_dir` named `<pipeline_name>_status_<yyyyMMdd_HHmmss>.csv`. Runs never overwrite each other — each run is isolated in its own file. One row per processed file.

```
start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error
```

| Column | Description |
|---|---|
| `start_time` | ISO datetime when file processing started |
| `end_time` | ISO datetime when it completed |
| `filename` | Input file name |
| `status` | `SUCCESS`, `FAILED`, `QUARANTINED_MISMATCH`, or `QUARANTINED_UNREADABLE` |
| `parsed_rows` | Rows successfully ingested into DuckDB staging table |
| `error_rows` | Rows rejected (insufficient columns, unparseable structure) |
| `output_paths` | Semicolon-separated list of output file paths |
| `output_sizes_bytes` | Semicolon-separated file sizes in bytes |
| `duration_ms` | Wall-clock processing time in milliseconds |
| `error` | Exception message when `status=FAILED`; empty otherwise |

**Example:**
```
2026-05-19 15:34:36,2026-05-19 15:37:57,adj_DATE_20200403.csv.gz,SUCCESS,419906,0,
  "database/year=2000/month=01/day=01/adj_DATE_20200403_out.parquet","1329331940",200878,""
```

### Error CSV

When rows are rejected during ingestion, a per-file error CSV is written to `errors/<source>/<basename>_errors.csv`. The file is **only created when at least one row is rejected** — no file is written for clean inputs, keeping the directory uncluttered.

```csv
line_number,reason,raw_line
42,"Insufficient columns (expected >477, found 3)","some,short,line"
```

### Quarantine

Files that cannot be processed at all are moved out of the inbox into `quarantine/<source>/` so they are never retried and are clearly visible for manual inspection.

| Status | Trigger | Quarantine sub-directory |
|---|---|---|
| `QUARANTINED_MISMATCH` | 0 valid rows parsed — every row fails field validation | `quarantine/<source_path>/field_mismatch/` |
| `QUARANTINED_UNREADABLE` | `IOException` thrown while opening or streaming the file | `quarantine/<source_path>/unreadable/` |

`QUARANTINED_MISMATCH` catches two cases:
- **Appender rejection** — rows reach the appender but fail column-count (`errorRows > 0`)
- **Junk-scan exhaustion** — all rows have fewer columns than the schema expects and never exit junk detection; the appender loop is never entered

The `<source_path>` mirrors the file's subdirectory path relative to the poll root. For `QUARANTINED_MISMATCH`, the companion `_errors.csv` is moved alongside the quarantined file so the rejection evidence is co-located.

---

## Batch Processing

By default the pipeline processes one file per batch (`max_files = 1`, `max_bytes = Long.MAX_VALUE`), which is identical to the previous single-file behaviour. Consolidation activates only when you explicitly set either limit.

### Configuration

Add a `batch:` sub-section inside the `processing:` block of the pipeline toon:

```yaml
processing:
  threads: 4
  file_pattern: "glob:**/*.{csv,csv.gz}"
  batch:
    max_files: 500
    max_bytes: 268435456
```

| Key | Default | Description |
|---|---|---|
| `processing.batch.max_files` | `1` | Maximum number of input files consolidated into one batch |
| `processing.batch.max_bytes` | `Long.MAX_VALUE` | Maximum total uncompressed size of files in one batch (bytes) |

Files are grouped greedily in poll order; a new batch starts whenever either limit would be exceeded by the next candidate.

### Audit files written to `dirs.status_dir`

Each ETL run produces three timestamped CSVs alongside the existing status file. All three use the same `<yyyyMMdd_HHmmss>` timestamp suffix so runs never overwrite each other.

| File | Description |
|---|---|
| `<pipeline>_status_<ts>.csv` | One row per processed file — same as before, with a `batch_id` column appended at the end |
| `<pipeline>_batches_<ts>.csv` | One row per batch: batch_id, member count, total input bytes, total output bytes, status, duration |
| `<pipeline>_lineage_<ts>.csv` | Input-to-output row-count matrix — one row per (input file, output partition) pair |

### Per-batch JSON manifest

After each batch completes, a manifest is written to `<status_dir>/manifests/<batch_id>.json`. It records:

- The list of member input files and their paths in `dirs.backup`
- All output file paths and sizes produced by the batch
- The marker paths written for each member
- The batch status and completion timestamp

Manifests are used by the reprocess command to reconstruct exactly what a batch did and reverse it.

### Reprocessing a batch

```bash
ura.sh reprocess config/<source>/<source>_pipeline.toon <batch_id>
ura.bat reprocess config\<source>\<source>_pipeline.toon <batch_id>
```

`reprocess` performs the following atomically:

1. Reads the manifest for `<batch_id>`.
2. Deletes all output files and partition directories the batch created.
3. Deletes the `.processed` marker for each member.
4. Restores each member file from `dirs.backup` back into its original inbox path.
5. Supersedes the manifest with a `REPROCESSED` status entry so the audit trail is preserved.
6. On the next poll cycle the files are picked up and processed fresh.

`--dry-run` is honoured — prints the intended actions without modifying anything.

### Backward compatibility

When a batch contains exactly one member file, the output retains the legacy `<basename>_out.<ext>` naming convention (e.g. `adj_DATE_20200403_out.parquet`). Warehouse views built against single-file outputs are therefore unaffected when `max_files` remains at its default of `1`.

---

## Plugin Ingester

Use the plugin ingester when:

- The source format is binary, proprietary, or otherwise not parseable by `CsvIngester`.
- A single input file emits **multiple event types** (e.g. CALL + SMS interleaved in one CDR file) that must land in separate output tables with different schemas.
- Partition columns such as `event_type` are **computed by the parser** — they do not exist as raw data columns but are derived from the record structure.

The CSV path is unchanged for all existing sources. Plugin ingester is an opt-in mode activated by adding `processing.ingester:` to the pipeline toon.

### FileIngester interface

Implement `com.gamma.etl.FileIngester` in the same fat JAR and provide a public zero-arg constructor:

```java
package com.acme.etl;

import com.gamma.etl.*;
import java.io.File;
import java.sql.*;
import java.util.List;

public class MyCdrIngester implements FileIngester {

    @Override
    public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
            throws Exception {

        // 1. Parse the binary/proprietary file in whatever way you need.
        // 2. For each event type, create one DuckDB table named "raw_<KEY>_f<srcId>".
        //    Include payload columns AND any derived partition columns (e.g. EVENT_TYPE).
        //    Do NOT add a __src_id column — the framework adds it when building the union.
        // 3. Return a Segment record for each created table.

        int callCount = 0, smsCount = 0;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"raw_CALL_f" + srcId + "\" " +
                       "(ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE DATE)");
            st.execute("CREATE TABLE \"raw_SMS_f" + srcId + "\" " +
                       "(ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE DATE)");
            // ... parse file, insert rows into the appropriate tables ...
        }
        return List.of(
            new Segment("CALL", "raw_CALL_f" + srcId, new IngestResult(callCount, 0, 0)),
            new Segment("SMS",  "raw_SMS_f"  + srcId, new IngestResult(smsCount,  0, 0))
        );
    }
}
```

**Contract:**

| Rule | Detail |
|---|---|
| Table name | Must be `raw_<KEY>_f<srcId>` — the framework unions per-member tables by this convention |
| Derived columns | Add computed partition columns (e.g. `EVENT_TYPE VARCHAR`) directly into the raw table as extra columns |
| `__src_id` | Do **not** include — the framework appends it when creating the union table |
| Quarantine | Throw `IOException` → `QUARANTINED_UNREADABLE`. Return `parsedRows = 0` for all segments → `QUARANTINED_MISMATCH` |
| Segment keys | Must match the keys declared in `processing.segments:` in the pipeline toon |

### Segment schema toon (`partitions[]`) {#segment-schema-toon-partitions}

Each segment key has its own schema toon. Use the `partitions[]` list syntax instead of the legacy `partitionKey:` shorthand. Each entry maps an output partition column to a raw table source column and specifies how to derive the value.

```yaml
# file: config/events/call_schema.toon

partitions:
  - column: event_type   # output partition column name
    source: EVENT_TYPE   # column in raw_CALL_f<srcId> added by the ingester
    type: VARCHAR
  - column: year
    source: EVENT_DATE
    type: DATE_YEAR
  - column: month
    source: EVENT_DATE
    type: DATE_MONTH
  - column: day
    source: EVENT_DATE
    type: DATE_DAY

raw:
  name: call
  format: CSV
  fields[3]{name,selector,type}:
    ID,"0",VARCHAR
    EVENT_TYPE,"1",VARCHAR
    EVENT_DATE,"2",DATE

mapping:
  canonicalName: call
  rawName: call
  rules[3]{targetColumn,sourceExpression,transformType}:
    ID,ID,DIRECT
    EVENT_TYPE,EVENT_TYPE,DIRECT
    EVENT_DATE,EVENT_DATE,DIRECT
```

**`PartitionDef.type` values:**

| Type | SQL generated | Use for |
|---|---|---|
| `VARCHAR` | direct column reference | String columns the ingester computes (e.g. `EVENT_TYPE`) |
| `DOUBLE` | `TRY_CAST(col AS DOUBLE)` | Numeric partition key |
| `INTEGER` | `TRY_CAST(col AS INTEGER)` | Integer partition key |
| `DATE_YEAR` | `YEAR(TRY_STRPTIME(CAST(col AS VARCHAR), fmt))::VARCHAR` | Year component of a date column |
| `DATE_MONTH` | `LPAD(MONTH(…)::VARCHAR, 2, '0')` | Zero-padded month |
| `DATE_DAY` | `LPAD(DAY(…)::VARCHAR, 2, '0')` | Zero-padded day |

The `DATE_*` types cast the source column to `VARCHAR` before parsing, so the same schema toon works whether the raw DuckDB column is already typed `DATE` (plugin path) or is a VARCHAR string (CSV path).

The legacy `partitionKey: COLUMN` shorthand synthesises three `DATE_YEAR` / `DATE_MONTH` / `DATE_DAY` entries automatically and remains fully supported for CSV sources.

### Pipeline config (plugin) {#pipeline-config-plugin}

Replace `schema_file:` with `ingester:` and `segments:`. The `duplicate_check:` block is optional (plugin path does not use `.processed` markers by default).

```yaml
name: EVENTS_ETL
version: 1

dirs:
  poll:       inbox/events
  database:   database/events
  backup:     backup/events
  temp:       temp/events
  errors:     errors/events
  quarantine: quarantine/events
  status_dir: status/events
  log_dir:    logs/events

output:
  format: CSV           # or PARQUET

processing:
  threads: 1
  file_pattern: "glob:**/*.bin"
  ingester: com.acme.etl.MyCdrIngester   # fully-qualified class in the fat JAR
  segments:
    CALL: config/events/call_schema.toon  # key must match Segment.key() from the ingester
    SMS:  config/events/sms_schema.toon
  csv_settings:
    delimiter: ","
    skip_header_lines: 0
    skip_junk_lines: 0
    skip_tail_lines: 0
    date_formats[1]: "%Y-%m-%d"
    timestamp_formats[1]: "%Y-%m-%d"
```

| Key | Required | Description |
|---|---|---|
| `processing.ingester` | yes | Fully-qualified class name of a `FileIngester` implementation in the fat JAR |
| `processing.segments` | yes (when ingester is set) | Ordered map of segment key → schema toon path; validated at startup |

> `segments:` must be a non-empty map when `ingester:` is set; a missing or empty map throws `IllegalArgumentException` at startup. Each schema file must exist; a missing file throws `FileNotFoundException`.

### Output layout

Output files are written under `database/<source>/<SEGMENT_KEY>/` and partitioned by the columns declared in that segment's `partitions[]` list. Multiple segments from the same input file land in independent sub-trees:

```
database/events/
  CALL/
    event_type=CALL/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv
  SMS/
    event_type=SMS/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv
```

All lineage (input → output row counts), audit files (`_batches_`, `_lineage_`, `_status_`), and per-batch JSON manifests are written identically to the CSV path — one consolidated `BatchRow` per batch, with the segment keys used as the `schemaLabel`.

---

## DuckLake Integration

DuckLake is a lakehouse format that uses a SQL database (PostgreSQL) as the catalog/metadata store, with data stored as Parquet files. This lets remote clients query the data using standard DuckDB tooling.

### Setup

1. **Enable PostgreSQL** on the server and create a database for the catalog:
   ```sql
   CREATE DATABASE ducklake_db;
   ```

2. **Configure the pipeline** (`<data_source>_pipeline.toon`):
   ```yaml
   output:
     format: PARQUET
     compression: snappy
     ducklake:
       enabled: true
       catalog_url: "postgresql://etl_user:password@localhost:5432/ducklake_db"
       data_path: "/opt/adj-lake"
       schema: <data_source>s
       table: <data_source>_data
   ```

3. **Run the ETL.** After each file is written, SourceProcessor will:
   - `INSTALL ducklake FROM core` (downloads on first run; cached thereafter)
   - `ATTACH` the PostgreSQL catalog
   - Create the schema and table if they do not exist
   - `INSERT INTO` the DuckLake table by reading the just-written Parquet files

   DuckLake registration is **non-fatal** — if it fails (e.g. PostgreSQL unreachable), the file is still marked processed and the failure is logged to stderr. The Parquet output on disk is unaffected.

### Remote access via DBeaver

Each remote user installs the **DuckDB JDBC driver** in DBeaver and connects using the ducklake extension pointed at the same PostgreSQL catalog. Parquet files must be on a path accessible from the client (network share / NFS mount).

```sql
-- In a DBeaver DuckDB connection
INSTALL ducklake FROM core;
LOAD ducklake;
ATTACH 'ducklake:postgresql://user:password@server:5432/ducklake_db'
    AS lake (DATA_PATH '/mnt/adj-lake');

SELECT * FROM lake.<data_source>s.<data_source>_data
WHERE year = '2000' AND month = '01'
LIMIT 100;
```

---

## Warehouse Query Layer — DBeaver via pg_duckdb

Parquet output can be queried directly from DBeaver (or any PostgreSQL client) without loading data into PostgreSQL. The `pg_duckdb` extension embeds DuckDB inside PostgreSQL as a transparent execution engine — users connect with a standard PostgreSQL driver and DuckDB is invisible to them.

```
DBeaver (laptop)  →  PostgreSQL :5432  →  pg_duckdb extension  →  database/**/*.parquet
```

No data is copied into PostgreSQL. PostgreSQL handles only the wire protocol; DuckDB does all I/O and vectorised execution against the Parquet files on disk.

### One-time server setup

**1. Install pg_duckdb on the Linux server**

```bash
# PostgreSQL 16 example — replace version number as needed
apt-get install -y postgresql-16-pgduckdb

# Enable the extension (requires a PostgreSQL restart)
psql -U postgres -c "ALTER SYSTEM SET shared_preload_libraries = 'pg_duckdb';"
sudo systemctl restart postgresql
```

**2. Apply `warehouse_setup.sql`**

```bash
# From the bundle root — substitute your actual data path
export DATA_ROOT=/opt/ura/sandbox
sed "s|DATA_ROOT|${DATA_ROOT}|g" warehouse_setup.sql > warehouse_setup_final.sql
psql -U postgres -d yourdb -f warehouse_setup_final.sql
```

**3. Create login accounts** (edit the commented block at the bottom of the file):

```sql
CREATE USER alice WITH PASSWORD 'changeme' IN ROLE analyst;
CREATE USER bob   WITH PASSWORD 'changeme' IN ROLE <data_source>_analyst;
```

### Views in the `warehouse` schema

| View | Source path | Columns | Partition key |
|---|---|---|---|
| `<data_source>_cdr` | `database/<data_source>/<data_source>_cdr/**` | 537 | EVENT_DATE (extracted from filename) |
| `<data_source>_main` | `database/<data_source>/<data_source>_main/**` | 116 | TRANSACTION_START_DATE |
| `<data_source>_other` | `database/<data_source>/<data_source>_other/**` | 76 | TRANSACTION_START_DATE |
| `<data_source>` | `database/<data_source>/**` | 477 | REVERSAL_DATE |
| `<data_source>_all` | union of all 3 <data_source> views | common cols | — |
| `data_catalog` | partition summary across all tables | — | — |

### Roles

| Role | Access |
|---|---|
| `analyst` | all warehouse views |
| `<data_source>_analyst` | <data_source>_cdr, <data_source>_main, <data_source>_other, <data_source>_all |
| `<data_source>_analyst` | <data_source> only |

### DBeaver connection

Use the standard **PostgreSQL** driver. No special configuration needed.

| Field | Value |
|---|---|
| Host | `your-linux-server` |
| Port | `5432` |
| Database | `yourdb` |
| Driver | PostgreSQL |

Partition pruning is automatic — DuckDB reads only the files that match the `WHERE` predicates:

```sql
-- Check what data has landed across all tables
SELECT * FROM warehouse.data_catalog ORDER BY table_name, year, month, day;

-- Query with partition pruning (reads only year=2020/month=01/day=01 files)
SELECT * FROM warehouse.<data_source>_cdr
WHERE year = 2020 AND month = 1 AND day = 1
LIMIT 100;
```

---

## Deployment — Remote Server

The fat JAR bundles all dependencies — no JVM classpath setup needed on the target.

### Build the deployment bundle

```powershell
# From the sandbox root or from inside file-processor/:
powershell -ExecutionPolicy Bypass -File file-processor\package.ps1

# Skip the Maven build if the JAR is already current:
powershell -ExecutionPolicy Bypass -File file-processor\package.ps1 -NoBuild
```

This produces **`file-processor-deploy.zip`** in the sandbox root. The script:
1. Runs `mvn clean package` to build a fresh fat JAR
2. Assembles a self-contained bundle with the JAR, config files, and run scripts
3. Rewrites `schema_file` paths in the bundled configs so they are relative to the bundle root
4. Creates all placeholder directories (inbox, database, backup, temp, errors, quarantine)
5. Zips everything into `file-processor-deploy.zip`

### Bundle contents

```
file-processor-deploy/
  file-processor.jar              ← fat JAR, all dependencies included (~94 MB)
  config/
    <data_source>/<data_source>_pipeline.toon
                  <data_source>_schema.toon
                  adj_gen.toon
    <data_source>/<data_source>_pipeline.toon
                  <data_source>_schema.toon
  inbox/<data_source>/               ← drop input files here (or run pre-ETL utilities)
  database/<data_source>/               ← partitioned Parquet output
  backup/<data_source>/               ← original source files archived after processing
  temp/<data_source>/               ← scratch space for tar extraction
  errors/<data_source>/               ← per-file error CSVs
  quarantine/<data_source>/               ← quarantined files
  markers/<data_source>/               ← .processed sentinel files (auto-pruned; mirrors inbox tree)
  run.sh                      ← Linux/Mac ETL launcher  (java -jar ... <adapter>_pipeline.toon)
  run.bat                     ← Windows ETL launcher
  ura.sh                      ← Linux/Mac utility CLI   (java -cp ... MainApp <command> ...)
  ura.bat                     ← Windows utility CLI
  warehouse_setup.sql         ← pg_duckdb warehouse schema, views, and RBAC (run once on server)
  README.md
```

### Deploy and run

```bash
# 1. Copy the zip to the server and extract
unzip file-processor-deploy.zip
cd file-processor-deploy

# 2. Stage files (optional — use pre-ETL utilities if source is on a network share or in tarballs)
bash ura.sh copy-tars config/<data_source>/<data_source>_pipeline.toon
bash ura.sh extract   config/<data_source>/<data_source>_pipeline.toon

# 3. Drop input files directly into the inbox (if no pre-ETL staging needed)
cp /data/feeds/*.csv.gz inbox/<data_source>/

# 4. Run the ETL (Linux)
bash run.sh <data_source>          # or: bash run.sh <data_source>

# 5. Run the ETL (Windows)
run.bat <data_source>
```

**Direct invocation** (without the run scripts):
```bash
java --enable-native-access=ALL-UNNAMED \
     -jar file-processor.jar \
     config/<data_source>/<data_source>_pipeline.toon
```

**Java requirement:** Java 24 or later. No other runtime dependencies.

### Performance reference (single-node, HDD, 4 threads)

| Source | Files | Rows/file (avg) | Time/file | Total (30 days) |
|---|---|---|---|---|
| <data_source> | 30 × `.csv.gz` | ~2.3 M | ~19 min | ~2.5 hr |
| <data_source> | varies | ~420 K | ~3.4 min | — |

Note: the 20200117 <data_source> file is ~4.3 GB uncompressed (~2.97 M rows) due to ISIZE overflow in the gz header — the ETL handles it transparently via streaming.

### Pre-production checklist (<data_source>)

- [ ] Delete `inbox/<data_source>/20200101/vou_DATE_20200101.csv/` — this is an 8 GB uncompressed directory (duplicate of the `.gz`); the glob pattern would pick up the file inside it and double-process the day
- [ ] Run from the bundle root (or sandbox root locally) so relative paths resolve correctly
- [ ] Verify Java 24 is on `PATH`: `java -version`

---

## Onboarding a New Source

1. **Write a generation config** — copy `config/<data_source>/adj_gen.toon` and adapt:
   - Set the correct `delimiter`, skip counts, and date formats
   - List column names that should be forced to `DATE` or `TIMESTAMP` in `type_patterns`

2. **Run `create-schema`** against a representative sample file:
   ```bash
   # From the file-processor/ directory
   ./ura.sh create-schema mySource path/to/sample.csv config/mysource/mysource_gen.toon
   # Windows:
   ura.bat create-schema mySource path\to\sample.csv config\mysource\mysource_gen.toon
   ```
   This generates `mysource_schema.toon` and `mysource_pipeline.toon` under `config/mysource/`. The generated pipeline includes all required ETL `dirs.*` keys.

3. **Add the pre-ETL utility sections** to the generated pipeline toon (edit `mysource_pipeline.toon`):
   ```yaml
   search:
     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
     csv_input:     GSM_RESUBMISSION.csv
     log_available: available_files.csv
     log_missing:   missing_files.csv
     log_error:     error_log.csv

   copy_tars:
     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2

   backup:
     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
     log_available: available_files.csv
   ```
   Omit any section for commands you won't use.

4. **Review the generated schema** — check that types are correct, adjust `partitionKey` if needed.

5. **Edit the pipeline config** if required — adjust `output.format`, thread count, or the DuckLake section.

6. **Run the ETL:**
   ```bash
   bash run.sh mysource      # after adding mysource to run.sh
   # or directly:
   java --enable-native-access=ALL-UNNAMED \
        -jar target/file-processor-1.3.0.jar \
        config/mysource/mysource_pipeline.toon
   ```

---

## Type Mapping Reference

Transformations are applied by DuckDB during the `CREATE TABLE AS SELECT` step.

| Schema type / transform | DuckDB expression | NULL behaviour |
|---|---|---|
| `DATE` | `COALESCE(TRY_STRPTIME(col, 'fmt1'), TRY_STRPTIME(col, 'fmt2'), ...)::DATE` | Unparseable value → `NULL` |
| `TIMESTAMP` | `COALESCE(TRY_STRPTIME(col, 'fmt1'), TRY_STRPTIME(col, 'fmt2'), ...)::TIMESTAMP` | Unparseable value → `NULL` |
| `DOUBLE` | `TRY_CAST(col AS DOUBLE)` | Non-numeric value → `NULL` |
| `VARCHAR` | Direct column reference — no cast | Never NULL from cast |
| `FILENAME_DATE` | `TRY_STRPTIME(regexp_extract(col, 'PREFIX([0-9]{8})', 1), '%Y%m%d')::DATE` | No regex match → `NULL` |

Multiple date/timestamp formats are tried left-to-right via `COALESCE`. Common Oracle patterns:

| Format string | Example value |
|---|---|
| `%d-%b-%y` | `01-JAN-00` |
| `%d-%b-%Y` | `01-JAN-2000` |
| `%d-%b-%Y %H:%M:%S` | `01-JAN-1900 00:00:00` |

---

## Troubleshooting

### pg_duckdb: "column X does not exist" when querying a view

**Cause:** `read_parquet()` is a DuckDB table-valued function. PostgreSQL's planner cannot see its column names from the catalog, so a `SELECT *` view causes `column "event_date" does not exist` errors the moment you reference a column in `WHERE`, `GROUP BY`, or `ORDER BY`.

**Fix:** Every column must be explicitly aliased in the view using the `r['colname']` syntax:

```sql
CREATE OR REPLACE VIEW public.<data_source> AS
SELECT
  r['year']::integer           AS "year",
  r['ENTRY_DATE']::date        AS "ENTRY_DATE",
  r['EVENT_DATE']::timestamp   AS "EVENT_DATE",
  r['RECHARGE_CODE']::text     AS "RECHARGE_CODE",
  r['RECHARGE_AMT']::double precision AS "RECHARGE_AMT"
  -- ... all remaining columns
FROM read_parquet('/data/.../<data_source>/**/*.parquet', hive_partitioning := true) r;
```

With 100–500 columns this is impractical to write by hand. Use the included generator script instead:

```bash
# Run on the Linux server (where Parquet files live)
python3 generate_warehouse_views.py

# Apply the generated DDL
psql -U postgres -d yourdb -f warehouse_views_generated.sql
```

The script introspects the live Parquet schema via DuckDB and emits a complete `CREATE OR REPLACE VIEW` for every table with all columns mapped to their correct PostgreSQL types.

---

### All output lands in `year=NULL/month=NULL/day=NULL`

The partition key column value does not match any configured date format. Check the actual values in the source file and add the matching format to `date_formats` / `timestamp_formats` in the pipeline config.

### ORA-28002 files have extra junk lines leaking into data

The `skip_junk_lines` cap is too low. Oracle password-expiry warnings add extra preamble lines. Increase the cap — or set it to `-1` for unlimited scan. The adaptive detector will still find the first real data row correctly.

### Wrong-schema file is not quarantined (lands as SUCCESS with parsed=0)

This was a bug fixed in the current version. The quarantine condition now also catches files where every row has too few columns to exit junk detection (the `junkCandidateRows` counter). Update to the latest JAR if you see this.

### Config error: directory X must not be inside poll directory

The startup validator enforces that `database`, `backup`, `temp`, `errors`, `quarantine`, and `markers` are all siblings of the poll directory — not nested inside it. This prevents the ETL from recursing into its own output, scratch, or marker space. Move the offending directory in the pipeline config.

### JVM crash (EXCEPTION_ACCESS_VIOLATION in VCRUNTIME140.dll)

DuckDB 1.1.1 on Windows has a native AVX2 page-boundary bug triggered by large files. The current code works around this by materialising the transformation into an intermediate table (`CREATE TABLE transformed AS ...`) before `COPY TO`. If the crash recurs after a DuckDB version upgrade, check whether the workaround is still in place.

### DuckLake registration fails silently

Check stderr output for `DuckLake registration failed`. Common causes:
- PostgreSQL not reachable from the ETL server — verify `catalog_url` host/port/credentials
- `ducklake` extension download blocked — server needs outbound internet access on first run (extension is cached after initial download)
- `data_path` does not exist or is not writable

### `.processed` files prevent re-processing

Marker files are stored in `markers/` (not in `inbox/`), mirroring the inbox tree. Delete them to force reprocessing:
```bash
# Delete all markers for one adapter
find markers/<data_source>/ -name "*.processed" -delete

# Delete a single marker (e.g. to reprocess one specific file)
rm markers/<data_source>/20200403/adj_DATE_20200403.csv.gz.processed
```

Stale markers are pruned automatically at each poll start based on `processing.duplicate_check.retention_days`. You only need manual deletion to reprocess files within the retention window.

### Tar extraction: file already exists in target

The `extract` and `prepare-inbox` commands skip CSV files that already exist at the destination path (`[SKIP] Already exists: ...`). This is intentional — re-running after a partial failure is safe. Delete the destination file manually to force re-extraction.
