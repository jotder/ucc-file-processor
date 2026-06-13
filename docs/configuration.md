# Configuration Reference

> Part of the [Inspecto](../inspecto/README.md) documentation. See the [docs index](../inspecto/README.md#documentation).

## Configuration Reference

The framework uses three config files in `.toon` format (JToon). Only the generation config is hand-authored; the other two are machine-generated and then maintained.

Config files live under `inspecto/config/<adapter>/`.  All `dirs.*` and `schema_file` paths are relative to the **sandbox root** (the JVM working directory).

### Configuration by source format

Configuration is organized **per source format** — each format has its own
block and its own knobs, by design (see
[Design Philosophy](architecture.md#design-philosophy--scope)). Pick the row that matches your
input; the rest of this section details each block.

| Source format | Ingest path | Key config block(s) | Notable knobs |
|---|---|---|---|
| **Delimited text** (CSV, CSV.GZ, TSV, pipe-delimited) | built-in | `processing.csv_settings` + a `schema_file` (or `schemas[]` for multi-schema) | `delimiter`, `engine` (`auto`/`duckdb`/`java`), `skip_header_lines`, `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns`, `has_header`, `date_formats`, `timestamp_formats` |
| **Messy text dumps** (SQL\*Plus exports with banners/footers, ragged columns) | built-in (Java engine) | same as above, with the messy-file knobs set | `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` → forces `engine: java` under `auto` |
| **Fixed-width text** (column-positional records, one record per line) | built-in (native `read_csv`+`substring`) | `frontend: fixedwidth` + a `fixedwidth:` block (inline or in a `*.grammar.toon`) + a `schema_file` | `record: line`, `trim`, `min_record_length`, `fields[]{name,start,length}` |
| **Binary / proprietary / multi-event-type** (CDR blobs, fixed-length binary, anything one parser splits into several record types) | [plugin](plugins.md#plugin-ingester) | `processing.ingester` + `processing.segments` + optional `processing.ingester_config` | `ingester` (FQCN), per-segment schema files, free-form `ingester_config` map for format-specific settings (`record_length`, `byte_order`, …) |

Common to **all** formats: `dirs.*`, `output.format` (`CSV`/`PARQUET`),
`processing.batch.*`, `processing.threads`, the `partitions[]` declaration in
each schema, and the audit/manifest machinery. Format-specific blocks only
cover *how the bytes become rows* — once rows exist, the M..N partition-and-write
path is identical regardless of source format.

### How a source becomes partitioned output (the transform model)

Configuration drives a deliberately thin, **per-record** transformation. One batch of input
files becomes **one** DuckDB `CREATE TABLE … AS SELECT …`, and the three config files each own
one part of it:

| Config file | Owns | Authored |
|---|---|---|
| `*_gen.toon` (Generation) | how to *read the sample* — delimiter, junk/tail trimming, which columns are dates/timestamps | by hand |
| `*_schema.toon` (Schema) | **the transform itself** — `raw.fields[]` (bind output field → source column by zero-based `selector` + declare its type), `mapping.rules[]` (how each target column is produced), `partitions[]` (derive the Hive partition columns) | generated, then tuned |
| `*_pipeline.toon` (Pipeline) | runtime — directories, output format, threads, dedup, and the `date_formats`/`timestamp_formats` lists the casts use | generated, then tuned |

The path every row takes:

```
raw bytes ──(built-in delimited reader │ plugin ingester)──►  VARCHAR staging row
          ──(one mapping rule per column → typed scalar expr)──►  typed output row
          ──(partitions[] → year / month / day / …)──►  Hive-partitioned Parquet/CSV
```

**The boundary that shapes all of this:** a mapping rule is a **scalar expression over a single
row of one table** — cast a type, rename, pick a column, compose a date. It *cannot* join, look
up, or aggregate, because Stage-1 is stateless per record — which is exactly what makes every
batch parallel and safely re-runnable. Joins, lookups, and aggregation live in **Stage-2
enrichment**: a separate `*_enrich.toon` with hand-written SQL over the committed output. See
[Architecture → Design Philosophy & Scope](architecture.md#design-philosophy--scope).

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
    - targetColumn: REVERSAL_DATE
      sourceExpression: REVERSAL_DATE
    - targetColumn: AMOUNT
      sourceExpression: AMOUNT
```

**Partition columns** are declared by `partitionKey:` (shorthand — derives `year`/`month`/`day` from
one column) or an explicit `partitions[]` list. The `DATE_YEAR`/`DATE_MONTH`/`DATE_DAY` types extract
the component from the `source` column, parsing its value with **`timestamp_formats` when the source
field is declared `TIMESTAMP`**, otherwise with **`date_formats`** (`VARCHAR`/`DATE` sources). Put the
matching pattern in the right list: a `TIMESTAMP` value like `2018-04-09-00.00.00.000000` will **not**
match a date-only pattern, and any unparsed value falls into the `1900/01/01` sentinel partition.
(`VARCHAR` partition columns pass through as-is; `DOUBLE`/`INTEGER` use `TRY_CAST`.)

`create-schema` generates pass-through rules with **no `transformType`** — a blank or omitted type means `DIRECT`, so the common case stays uncluttered. Add a `transformType` only for the non-`DIRECT` rules below.

**`selector`** is the zero-based column index in the raw source CSV — decoupled from position in the output schema, so source column order changes do not break the pipeline.

**`transformType`** controls how the source expression is evaluated. It is **optional and
case-insensitive — blank or omitted means `DIRECT`**. An unrecognised *non-blank* value (a typo
like `EXPER`) is rejected at transform time rather than silently treated as `DIRECT`. Recognised
values:

| Value | `sourceExpression` format | Description |
|---|---|---|
| `DIRECT` *(default — leave blank/omit)* | column name | Pass-through with a type cast (DATE/TIMESTAMP/DOUBLE/VARCHAR) driven by the field's declared type in `raw.fields[]` |
| `EXPR` | any DuckDB **scalar** expression | Emitted **verbatim**. Unqualified column names resolve against the source row, so the full DuckDB scalar-function library is available — e.g. `UPPER(TRIM(MSISDN))`, `TRY_CAST(AMT AS DOUBLE) / 100.0`, `CASE WHEN ERRORCODE='0' THEN 'OK' ELSE 'FAIL' END`. You own validity and any explicit cast. **Per-row scalar only** — no aggregates or joins (those are Stage-2). |
| `CONCAT_DT` | `DATE_COL\|TIME_COL` | Concatenate two raw columns into a single TIMESTAMP: `COALESCE(TRY_STRPTIME(date \|\| ' ' \|\| time, ...))` |
| `FILENAME_DATE` | `COL\|PREFIX` or `COL\|PREFIX\|FORMAT` | Extract an 8-digit date from a filename-style column using a fixed prefix. The default format is `%Y%m%d`. **Restricted to `EVENT_DATE` only** — an `IllegalArgumentException` is thrown at startup if used on any other target column. |

**`FILENAME_DATE` example** — <data_source> CDR files carry the event date in the filename (`cbs_cdr_vou_20180409_601_101_057726.add`) rather than a data column:
```yaml
mapping:
  rules[1]{targetColumn,sourceExpression,transformType}:
    EVENT_DATE,FILENAME|cbs_cdr_vou_,FILENAME_DATE
```
The generated SQL is: `TRY_STRPTIME(regexp_extract(raw_input."FILENAME", 'cbs_cdr_vou_([0-9]{8})', 1), '%Y%m%d')::DATE`

**`EXPR` example** — derive columns with arbitrary DuckDB scalar functions, referencing raw columns by name (tabular form; quote the cell because it contains commas/parens):
```yaml
mapping:
  rules[3]{targetColumn,sourceExpression,transformType}:
    ACCOUNT_KEY,  "UPPER(TRIM(ACCOUNT_NUMBER))",                       EXPR
    AMOUNT_MAJOR, "TRY_CAST(RECHARGE_AMT AS DOUBLE) / 100.0",          EXPR
    RESULT,       "CASE WHEN ERRORCODE='0' THEN 'OK' ELSE 'FAIL' END", EXPR
```
The expression is emitted verbatim into the `SELECT`, so it must be a valid **per-row scalar**
expression (the schema is operator-authored and trusted — it is not sandbox-validated, and an
invalid expression fails the batch at `CREATE TABLE … AS SELECT`).

> Each rule compiles to one column expression (`etl/TransformCompiler`); `DataTransformer`
> assembles them — plus the derived partition columns — into the single `CREATE TABLE … AS
> SELECT …`. The type cast a `DIRECT` rule applies comes from the field's declared type in
> `raw.fields[]` and the pipeline's `date_formats`/`timestamp_formats` (see
> [Type Mapping Reference](#type-mapping-reference)).

#### Extending the transform — three levels

Most onboarding needs **only config** (level 1) — and with `EXPR` that already includes the full
DuckDB scalar-function library. Levels 2 and 3 are deliberate code seams in the engine (not a
config SPI), reached only when config genuinely can't express the need:

| Level | Reach for it when | Where / how |
|---|---|---|
| **1 — Mapping rule** *(config, no code)* | rename/select, cast a type, compose a timestamp (`CONCAT_DT`), derive a date from the filename (`FILENAME_DATE`), **or any per-row DuckDB scalar expression (`EXPR`)** | a row in `mapping.rules[]` |
| **2 — New named `transformType`** *(engine code)* | you want a **reusable, named** verb across many schemas (e.g. a domain checksum) rather than repeating the same `EXPR` everywhere | add a `ColumnRule` to the `DATA_RULES` registry in `etl/TransformCompiler` — a one-line addition returning a DuckDB **scalar** expression (one row in → one row out) |
| **3 — Plugin ingester** *(engine code)* | the **input format** isn't delimited text — binary, fixed-width, ASN.1 — or one file splits into several event-type tables | implement [`StreamingFileIngester`](plugins.md#plugin-ingester): you parse and `emit` records; the framework still applies the same `mapping.rules[]` / `partitions[]` to them |

Anything that needs **more than one row** — a join to a reference table, a `GROUP BY`, a running
total — is not a transform and does not belong in any of these levels. It is **Stage-2
enrichment** (`*_enrich.toon`, hand-written SQL over the partitioned output).

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
  schema_file: "inspecto/config/<data_source>/<data_source>_schema.toon"
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

When a single pipeline must handle input files with different column layouts (e.g. three related feeds delivered to the same inbox), replace the single `schema_file:` key with a `schemas[]` inline array. Each entry maps a column count to a schema file and target table, with an optional filename glob for the fast path.

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

### Fixed-width frontend (`frontend: fixedwidth`)

For column-positional records (no delimiter), set `frontend: fixedwidth` in the grammar/`csv_settings`
and declare the byte/character geometry in a `fixedwidth:` block. **Only the tokenisation lives here —
the event `_schema.toon` (field names, types, mapping rules, partitions) is authored exactly like a
delimited source.** Slice index *i* feeds the schema field whose `selector` is *i*.

```yaml
# inline under processing.csv_settings, or in a reusable processing.grammar file
frontend: fixedwidth          # delimited (default) | fixedwidth
has_header: false
date_formats[1]: "%Y-%m-%d"
fixedwidth:
  record: line                # line (newline-delimited text) | bytes (fixed-length binary)
  record_length: 0            # REQUIRED when record: bytes; ignored for line
  trim: both                  # none | left | right | both  (default both)
  min_record_length: 0        # drop shorter lines (blanks/footers); 0 ⇒ default = widest slice end
  fields[3]{name,start,length}:   # start is 0-based; length in chars (bytes for record: bytes)
    ACCOUNT_NUMBER,0,6
    EVENT_DATE,6,10
    AMOUNT,16,8
```

- **`record: line`** (text) is parsed **natively** by DuckDB (`read_csv` reads each line as one VARCHAR
  column — empty `delim`/`quote`/`escape` so a line is never split or quote-merged — then `substring`
  carves each field), reusing the whole CSV streaming/union/chunk path. It is always native regardless
  of `engine`. A field's `selector` must index a declared slice or the config fails to load.
- **`record: bytes`** (binary) is handled by the shipped `com.gamma.ingester.FixedWidthRecordIngester`
  plugin — wire it via `processing.ingester` + `processing.segments` + `ingester_config` (see
  [Plugin Ingester](plugins.md#fixed-length-binary-records-fixedwidthrecordingester)), not the
  `fixedwidth:` block above.
- Worked example: `inspecto/config/subscriber/` (`subscriber.grammar.toon` + `subscriber_schema.toon`
  + `subscriber_pipeline.toon`).

---

### Large files: scratch location & auto-chunking

Each batch runs on a per-batch **embedded DuckDB temp database**, and DuckDB **spills** intermediate
data to disk when it exceeds its memory budget. By default both land in the JVM temp dir
(`java.io.tmpdir`, i.e. the system `/tmp`) — which is often small or RAM-backed (`tmpfs`). A very large
input (tens of GB to TB) will exhaust it with `No space left on device` / out-of-memory. Two additive,
optional config blocks under `processing` make the engine handle big files without touching `/tmp`.

#### `processing.duckdb` — relocate & cap engine scratch

```yaml
processing:
  duckdb:
    temp_directory: temp/<data_source>      # where the temp DB + DuckDB spill live (default: dirs.temp)
    memory_limit: "16GB"                     # RAM cap before spilling (default: DuckDB's own ~80% RAM)
    max_temp_directory_size: "900GB"         # cap spill so a runaway query fails fast, not the disk
```

| Key | Default | Effect |
|---|---|---|
| `temp_directory` | `dirs.temp` | Directory for the per-batch temp database **and** DuckDB's spill scratch. **As of 3.10.0 the engine no longer uses the system `/tmp`** — scratch defaults to the pipeline's `dirs.temp` (on the data volume). Set this to override (point at the roomiest/fastest disk). |
| `memory_limit` | DuckDB default (~80% RAM) | RAM cap per worker connection (DuckDB size string, e.g. `"16GB"`). Lower it to leave headroom for other work; DuckDB spills to `temp_directory` beyond it. |
| `max_temp_directory_size` | DuckDB default | Hard cap on spill size — a pathological query fails fast instead of filling the disk. |

> **This is usually the only change needed for a large single file.** With scratch on a roomy data
> volume, DuckDB's native `read_csv` is internally multi-threaded, so a single large file still uses all
> cores. Budget roughly **1–3× the decoded file size** of free space on `temp_directory` for the
> transient transform table + spill.

#### `processing.chunking` — bound scratch for arbitrarily large files

```yaml
processing:
  chunking:
    max_file_bytes: 5000000000               # 0/absent = disabled (default). Files larger than this are chunked.
    target_chunk_bytes: 2000000000           # approx size of each chunk (default: max_file_bytes)
```

When a single input file exceeds `max_file_bytes`, the CSV ingester **streams it into bounded chunks**
of ~`target_chunk_bytes` and processes them one at a time — so peak scratch stays ~one chunk regardless
of total file size, instead of materialising one multi-hundred-GB unit. Details:

- Only applies on the **native `read_csv` path** (clean files: `skip_junk_lines`/`skip_tail_lines`/
  `skip_tail_columns` all `0`). Messy SQL\*Plus dumps (Java engine) are not chunked.
- Each chunk reproduces the source's leading context (the `skip_header_lines` preamble + header row),
  so the same config reads it; `.csv.gz` inputs are decompressed on the fly.
- Chunks write their own per-partition output files (`<base>_cNNNNN_out.<ext>`), which coexist in the
  partition directories (valid Hive layout). The **original file** remains the unit for audit, lineage,
  markers, and backup — so idempotency and commit semantics are unchanged.
- Exactly one chunk file exists on disk at a time (it's deleted after processing), so chunk staging
  doesn't itself need room for the whole file.

> **Rule of thumb:** if `/tmp` can't grow, set `processing.duckdb.temp_directory` to a big data-volume
> path; if the data volume also can't hold ~1× the file, additionally enable `processing.chunking` to
> cap peak scratch to a chunk. Both are off/inherited by default, so existing pipelines are unchanged.

#### `processing.streaming` — plugin-ingester mode selection (the `StreamingFileIngester` path)

`processing.chunking` above bounds the **CSV** path. Plugin ingesters (`processing.ingester`) get an
analogous control: the framework runs the same `StreamingFileIngester` in one of two modes, chosen per
batch by file size.

```yaml
processing:
  ingester: com.acme.etl.MyCdrIngester
  segments:
    CALL: config/events/call_schema.toon
  streaming:
    large_file_bytes: 268435456     # default 256 MB. A batch whose largest member is ≥ this runs in
                                     # bounded GENERATION mode (huge files); smaller batches use UNION
                                     # mode (many small files → one transform/write). 0 = always union.
    flush_records: 5000000          # rows per generation flush in generation mode (bounds scratch).
  batch:
    max_files: 1000                 # pack many small files per union batch (raise for the many-small case)
```

- **Union mode** (default for files under the threshold) consolidates a batch's members into one
  transform/write and one set of partition output files — the right choice for an exceptionally large
  *number of small files*. Pair it with a high `batch.max_files` so the planner packs many files per batch.
- **Generation mode** (members ≥ `large_file_bytes`) flushes bounded generations so a genuinely huge
  single file processes with bounded heap and scratch; it writes per-generation output files
  (`<stem>_gNNNNN_out.*`).

Both are optional and have working defaults; see [plugins.md](plugins.md) for the full SPI guide.

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

