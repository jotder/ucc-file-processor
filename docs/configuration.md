# Configuration Reference

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

## Configuration Reference

The framework uses three config files in `.toon` format (JToon). Only the generation config is hand-authored; the other two are machine-generated and then maintained.

Config files live under `file-processor/config/<adapter>/`.  All `dirs.*` and `schema_file` paths are relative to the **sandbox root** (the JVM working directory).

### Configuration by source format

Configuration is organized **per source format** — each format has its own
block and its own knobs, by design (see
[Design Philosophy](architecture.md#design-philosophy--scope)). Pick the row that matches your
input; the rest of this section details each block.

| Source format | Ingest path | Key config block(s) | Notable knobs |
|---|---|---|---|
| **Delimited text** (CSV, CSV.GZ, TSV, pipe-delimited) | built-in | `processing.csv_settings` + a `schema_file` (or `schemas[]` for multi-schema) | `delimiter`, `engine` (`auto`/`duckdb`/`java`), `skip_header_lines`, `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns`, `has_header`, `date_formats`, `timestamp_formats` |
| **Messy text dumps** (SQL\*Plus exports with banners/footers, ragged columns) | built-in (Java engine) | same as above, with the messy-file knobs set | `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` → forces `engine: java` under `auto` |
| **Binary / proprietary / multi-event-type** (CDR blobs, fixed-width, anything one parser splits into several record types) | [plugin](plugins.md#plugin-ingester) | `processing.ingester` + `processing.segments` + optional `processing.ingester_config` | `ingester` (FQCN), per-segment schema files, free-form `ingester_config` map for format-specific settings (`record_length`, `byte_order`, …) |

Common to **all** formats: `dirs.*`, `output.format` (`CSV`/`PARQUET`),
`processing.batch.*`, `processing.threads`, the `partitions[]` declaration in
each schema, and the audit/manifest machinery. Format-specific blocks only
cover *how the bytes become rows* — once rows exist, the M..N partition-and-write
path is identical regardless of source format.

### 1. Generation Config (`<source>_gen.toon`)

**Hand-authored.** Tells SchemaExtractor how to read the sample file and which columns to force-type.

```yaml
csv_settings:
  delimiter: ","
  engine: auto              # auto | duckdb | java  (CSV parse engine — see below)
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

**`engine`** (default `auto`) selects the CSV parse engine. This is a pure
performance lever — output is identical for clean files (proven by parity test).

| Value | Behaviour |
|---|---|
| `auto` | Native DuckDB `read_csv` when `skip_junk_lines`, `skip_tail_lines`, and `skip_tail_columns` are all `0`; the Java parser otherwise. Safe default — no existing source's parse semantics change. |
| `duckdb` | Always use DuckDB's vectorized, multi-threaded `read_csv`. **4–5× faster ingest** (more on wide schemas — see `docs/performance.md`). Force this on messy-but-validated sources after confirming output parity. |
| `java` | Always use the original line-by-line Java parser. |

> The native reader rejects rows with *more* columns than the schema declares,
> whereas the Java parser keeps them and ignores the extras. That is precisely
> what `skip_tail_columns` handles, so `auto` routes any config using it to the
> Java path, and `ConfigValidator` warns if you force `engine: duckdb` alongside
> `skip_tail_columns > 0`. Everything else (leading banners, footers, short rows,
> blank lines) is rejected identically by both engines.

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

