# Configuration Reference
> *Moved from `docs/configuration.md` (docs consolidation, 2026-07-16).*

> Part of the [Inspecto](../../../../inspecto/README.md) documentation. See the [docs index](../../../../inspecto/README.md#documentation).

## Configuration Reference

The framework uses three config files in `.toon` format (JToon). Only the generation config is hand-authored; the other two are machine-generated and then maintained.

Config files live under a space's `config/<adapter>/` directory (e.g. `spaces/<id>/config/<adapter>/`; see [Spaces](#spaces-multi-project-layout) below).  All `dirs.*` and `schema_file` paths are relative to the **sandbox root** (the JVM working directory) — for the multi-space runtime that is the repo/bundle root you launch from, so the bundled example configs use repo-root-relative paths such as `spaces/<id>/config/...` and `spaces/<id>/data/...`.

## Spaces (multi-project layout)

One server can host **many isolated spaces** (projects) at once. A space is a self-contained directory under a
container root — launch with `-Dspaces.root=<dir>` (default `./spaces`):

```
spaces/
  <id>/                     # id: [a-z0-9-], 1–63 chars, not starting with '-'
    space.toon              # manifest: display_name, description, created_at
    config/                 # this space's *.toon tree (scanned at boot; hot-reloadable)
    data/  ( data/events/ ) # partition output  ( + rolling Parquet events )
    audit/                  # run journal, watermarks, commit logs
    duckdb/                 # this space's *.duckdb / *.db stores
    flows/                  # authored flows
```

Each space is fully isolated: its own service, scheduler, event log, stores, connection registry and metric
`space` label. Store **backends** (`status.backend`, `objects.backend`, `events.backend`, …) stay process-global
`-D` flags; only their location defaults move under the space root.

- **API:** every route is addressable under `/spaces/{id}/…` (e.g. `GET /spaces/acme/pipelines`); an unknown id
  is a `404`. `/health`, `/ready`, `/metrics` stay un-prefixed and server-global. An un-prefixed API path resolves
  the `default` (or sole) space.
- **Space CRUD** (server-global): `GET /spaces` (list), `POST /spaces` `{id,display_name?,description?}` (create +
  boot, no restart), `DELETE /spaces/{id}` (deregister + stop; add `?purge=true` to also delete its files).
- **Single-tenant mode** (no `-Dspaces.root`, a config/dir passed on the CLI) hosts one `default` space and is
  byte-identical to the pre-spaces behaviour; CRUD returns `409`.

### Migrating a flat deployment

There is no flat fallback — migrate the existing single-tenant layout into `spaces/default/` once:

```
java -cp inspecto.jar com.gamma.service.SpaceMigrator \
     [--id default] [--root ./spaces] [--from .] [--dry-run] <configDir>
```

It relocates `<configDir>` → `spaces/<id>/config` and the working-dir artifacts (`database/` → `data/`,
`jobs_audit/` → `audit/`, `inspecto-events/` → `data/events/`, the `*.duckdb`/`*.db` files → `duckdb/`) and writes
`space.toon`. It is **idempotent** (a re-run relocates nothing) and `--dry-run` prints the plan without moving.
**Caveat:** a config that references a schema by an *absolute* path keeps pointing at the old location after its
file moves — author relative paths, or fix them up after migrating. Custom (non-default) flat locations must be
moved by hand.

### Configuration by source format

Configuration is organized **per source format** — each format has its own
block and its own knobs, by design (see
[Design Philosophy](../engine/stage1-architecture.md#design-philosophy--scope)). Pick the row that matches your
input; the rest of this section details each block.

| Source format | Ingest path | Key config block(s) | Notable knobs |
|---|---|---|---|
| **Delimited text** (CSV, CSV.GZ, TSV, pipe-delimited) | built-in | `processing.csv_settings` + a `schema_file` (or `schemas[]` for multi-schema) | `delimiter`, `engine` (`auto`/`duckdb`/`java`), `skip_header_lines`, `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns`, `has_header`, `date_formats`, `timestamp_formats` |
| **Messy text dumps** (SQL\*Plus exports with banners/footers, ragged columns) | built-in (Java engine) | same as above, with the messy-file knobs set | `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` → forces `engine: java` under `auto` |
| **Fixed-width text** (column-positional records, one record per line) | built-in (native `read_csv`+`substring`) | `frontend: fixedwidth` + a `fixedwidth:` block (inline or in a `*.grammar.toon`) + a `schema_file` | `record: line`, `trim`, `min_record_length`, `fields[]{name,start,length}` |
| **Binary / proprietary / multi-event-type** (CDR blobs, fixed-length binary, anything one parser splits into several record types) | [plugin](../engine/plugins.md#plugin-ingester) | `processing.ingester` + `processing.segments` + optional `processing.ingester_config` | `ingester` (FQCN), per-segment schema files, free-form `ingester_config` map for format-specific settings (`record_length`, `byte_order`, …) |

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
[Architecture → Design Philosophy & Scope](../engine/stage1-architecture.md#design-philosophy--scope).

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
| `duckdb` | Always use DuckDB's vectorized, multi-threaded `read_csv`. **4–5× faster ingest** (more on wide schemas — see [`performance.md`](../build-run/performance.md)). Force this on messy-but-validated sources after confirming output parity. |
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
| **3 — Plugin ingester** *(engine code)* | the **input format** isn't delimited text — binary, fixed-width, ASN.1 — or one file splits into several event-type tables | implement [`StreamingFileIngester`](../engine/plugins.md#plugin-ingester): you parse and `emit` records; the framework still applies the same `mapping.rules[]` / `partitions[]` to them |

Anything that needs **more than one row** — a join to a reference table, a `GROUP BY`, a running
total — is not a transform and does not belong in any of these levels. It is **Stage-2
enrichment** (`*_enrich.toon`, hand-written SQL over the partitioned output).

---

### 3. Pipeline Config (`<source>_pipeline.toon`)

**Machine-generated** by `create-schema`; edit to adjust runtime settings. Also serves as the single configuration file for all pre-ETL utility commands.

```yaml
name: <data_source>_ETL
active: true        # opt-in execution gate — see "Activation" below
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
  schema_file: "spaces/<id>/config/<data_source>/<data_source>_schema.toon"
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

### Activation (`active:`) and config caching

**`active`** is a top-level boolean that gates whether the pipeline is **executed**:

| `active` | Behaviour |
|---|---|
| `true` | The pipeline runs on every poll cycle (and via the `MultiSourceProcessor` CLI). |
| `false` *(default — key absent ⇒ `false`)* | The pipeline is still parsed, indexed and visible in `GET /pipelines`, but **never executed**. |

The default is **off** so a freshly-dropped or half-edited config never runs until you explicitly arm it
with `active: true`. Toggling it requires no restart — the change is picked up on the next cycle (see below).
An operator who explicitly triggers a single pipeline (`POST /pipelines/{name}/trigger`) runs it regardless of
`active` — the gate only governs the *automatic* poll cycle and the multi-source CLI.

**Config caching / hot-reload.** Pipeline configs and the schema / grammar / segment files they reference are
**parsed once and cached**, not re-read every cycle. Each poll cycle re-checks the modification time of every
config file; a pipeline is re-parsed only when its `*_pipeline.toon` *or* one of its referenced files actually
changes on disk (so edits — including flipping `active` — are picked up automatically), and an unchanged
pipeline incurs no disk I/O and no schema-reload logging. Each run still gets its own run-timestamped
status/batch/lineage CSVs; only the parse is cached.

---

### Data acquisition — the `source:` block

By default a pipeline acquires files exactly as it always has: it scans the local `dirs.poll` tree for
`processing.file_pattern`. An **optional, additive** top-level `source:` block makes acquisition pluggable —
selecting a connector (local / SFTP / FTP), gating half-written files, deduplicating by content, detecting gaps
in an expected series, and (for remote sources) fetching with retries, integrity checks, parallelism, rate
limits, and source-side post-actions. **A pipeline with no `source:` block is byte-for-byte unchanged** — every
sub-block below defaults to today's behaviour, and each is parsed only when present, so features can be adopted
one at a time.

```yaml
source:
  connector: sftp                 # local (default) | sftp | ftp
  connection: prod_sftp           # id of a *_connection.toon profile (remote connectors only)
  include[1]: "glob:**/*.csv"     # defaults to processing.file_pattern; glob: or regex: prefixes
  exclude[1]: "glob:**/_*"        # patterns removed from discovery
  recursive_depth: -1             # -1 = unbounded
  stability:                      # readiness gate — never ingest a still-arriving file
    window: 30s
    size_checks: 2
    ready_marker: "{name}.done"
    exclude_temp_files: true      # also drops *.tmp,*.partial,*.filepart,.~lock.*
  duplicate:                      # dedup / change policy
    mode: CHECKSUM                # PATH (default = today's markers) | METADATA | CHECKSUM
    algorithm: SHA256             # MD5 | SHA256 | CRC32
    on_change: REPROCESS          # IGNORE | REPROCESS | ALERT | ARCHIVE_OLD_VERSION
  incremental:                    # only re-examine the recent frontier each scan
    watermark: last_modified      # last_modified (etag/version future); needs a content-based duplicate.mode
  guarantee: EXACTLY_ONCE         # BEST_EFFORT (default) | AT_LEAST_ONCE | EXACTLY_ONCE
  gap_detection:                  # alert on a hole in an expected series
    enabled: true
    sequence: "CDR_{yyyyMMddHH}"
  integrity:                      # verify fetched bytes (remote)
    size_check: true
    checksum: SHA256
  fetch:                          # retrieval tuning (remote)
    parallel_fetch: 8             # >1 ⇒ pool of independent connector sessions
    rate_limit: 50MBps            # token-bucket; 50MB/s, 512KBps, or a bare bytes/s number
  retry:                          # transient-fault retry/backoff (remote)
    count: 5
    backoff: EXPONENTIAL          # EXPONENTIAL | LINEAR | FIXED (all full-jittered)
    initial_delay: 30s
    max_delay: 15m
  circuit_breaker:                # stop hammering a dead endpoint
    failure_threshold: 5
    cooldown: 5m
  post_action:                    # finalize the source file after success (remote)
    on_success: MOVE              # RETAIN (default) | DELETE | MOVE | RENAME | TAG
    archive_path: archive/yyyy/MM/dd
    on_unsupported: WARN_AND_CONTINUE  # FAIL | WARN_AND_CONTINUE | IGNORE
```

**`connector`** — `local` (default) reads `dirs.poll`. `sftp`/`ftp` are served by the optional
`inspecto-connectors` module (see *Integrations*); these require a `connection` profile. An unknown connector
fails fast at startup.

**`connection`** — the id of a reusable `*_connection.toon` profile (host/port/base_path/credentials/tunnel),
resolved at runtime. Secrets in a profile are **references** (`${ENV:…}`/`${SYS:…}`), never literals in the file.

**`include` / `exclude` / `recursive_depth`** — discovery filters. A bare pattern or `glob:` is a path glob;
`regex:` is a Java regex over the forward-slash relative path. `include` defaults to `processing.file_pattern`.

**`stability`** — the readiness gate (the single biggest production safety win): a discovered file is held back
until it has been quiescent for `window` *and* seen at the same size on `size_checks` consecutive poll cycles, so
a file mid-write is never ingested. `ready_marker` (`{name}.done` sibling) is a native readiness signal that
short-circuits the size/mtime wait. The gate is idempotent under repeated polling — a dashboard "pending" count
never steals a hot file's progress.

**`duplicate`** — `PATH` (default) is today's `.processed` marker. `METADATA` keys on name+size+mtime (a cheap
`stat`, no read). `CHECKSUM` hashes content (catches re-uploads that size+mtime hide; one extra read per file, so
it is opt-in). `on_change` decides what happens when a known path's content changed. The fingerprint lives in a
dedicated DuckDB ledger (its own file, single-writer).

**`incremental`** — the high-watermark optimisation: `watermark: last_modified` makes each scan skip any file
modified **strictly before** the source's high-watermark — the greatest `last_modified` the ledger has recorded
for the source. So a re-scan only re-examines the recent frontier instead of re-LIST'ing/re-fetching (remote) or
re-stat'ing (local) the whole history; for a remote source the skip happens **before fetch**, so old objects cost
no bandwidth. The watermark is *derived* from the ledger, so this needs a content-based `duplicate.mode`
(metadata/checksum) — over the path-only default the ledger is empty and it no-ops (the engine warns). It is an
optimisation for **monotonic-arrival** sources (timestamps that only increase); a file re-uploaded below the
watermark is intentionally skipped, so leave it off if you must catch arbitrarily back-dated re-uploads. Skips
are counted in `inspecto_watermark_skipped_total`.

**`guarantee`** — declarative; `AT_LEAST_ONCE`/`EXACTLY_ONCE` need a content-based `duplicate.mode` (metadata or
checksum) to hold. The engine **warns** if a stronger guarantee is set over path-only dedup and behaves as
best-effort + commit-log replay in that case.

**`gap_detection`** — checks discovered names against a `sequence` template (literal text around one `{…}`
Java-date token) and emits a `SEQUENCE_GAP` event per missing key in the series; the service tier promotes that
to a managed ALERT object (trackable in Cases/Issues) with no extra config.

**`integrity`** *(remote)* — every fetched file is checked (size vs. the listing, checksum vs. the server etag
when present). A failure discards the bytes to quarantine (`corrupt_download`) and skips the file — never
processed corrupt.

**`fetch`** *(remote)* — `parallel_fetch > 1` fetches over a pool of independent connector sessions (the clients
hold one non-thread-safe session each, so concurrency uses extra sessions, not shared reuse). `rate_limit` is a
shared token bucket in bytes/s — accepts `50MBps`, `50MB/s`, `512KBps`, `2GBps`, or a bare bytes/s number (KB/MB/GB
are 1024-based).

**`retry`** *(remote)* — `count` retries with `backoff` (full jitter, bounded `initial_delay`…`max_delay`) wrap
discovery and each fetch, so a transient SFTP/FTP hiccup doesn't fail the cycle. Absent ⇒ a single attempt.

**`circuit_breaker`** *(remote)* — after `failure_threshold` consecutive connectivity failures the source trips
OPEN and is skipped for `cooldown` (then one trial), emitting `SOURCE_CIRCUIT_OPEN`, instead of hammering a dead
endpoint every cycle.

**`post_action`** *(remote)* — after a file is fetched, validated, and staged, optionally finalize the
**source-side** copy: `DELETE`, `MOVE` (into a date-resolved `archive_path`), `RENAME` (`processed_<name>`), or
`TAG`. The action is validated once per cycle against the connector's capabilities; `on_unsupported=FAIL` stops
the run before any byte moves, the others degrade to RETAIN. A runtime post-action failure never discards
already-staged good data.

> **Compression on the read path:** alongside `.gz`, the streaming Java ingest path now also transparently reads
> **`.bz2`** and **`.zip`** (first entry) inputs — list them in `processing.file_pattern` (e.g.
> `glob:**/*.{csv,csv.gz,csv.bz2,csv.zip}`). DuckDB's native path already handles `.gz`.

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
  [Plugin Ingester](../engine/plugins.md#fixed-length-binary-records-fixedwidthrecordingester)), not the
  `fixedwidth:` block above.
- Worked example: `spaces/default/config/subscriber/` (`subscriber.grammar.toon` + `subscriber_schema.toon`
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
    CALL: spaces/default/config/events/call_schema.toon
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

Both are optional and have working defaults; see [plugins.md](../engine/plugins.md) for the full SPI guide.

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

## Optional Postgres state store (DAT-6)

Six operational-state stores persist through plain JDBC and default to the **bundled DuckDB** engine (a
local file per space, zero extra dependency). Each can instead point at **PostgreSQL** for a shared /
distributed, multi-writer deployment — one Postgres can back several Inspecto nodes, whereas a file-based
DuckDB holds a single-writer lock. The engine is chosen per store by process-global `-D` flags:

| Store | Backend toggle | URL property | Purpose |
|---|---|---|---|
| Status projection | `-Dstatus.backend=db` | `-Dstatus.db.url` | queryable projection of the run audit |
| Operational objects | `-Dobjects.backend=db` | `-Dobjects.db.url` | mutable alerts/incidents/cases |
| Object links | `-Dobjects.backend=db` | `-Dobjects.links.db.url` | correlation graph (append-only) |
| Object notes | `-Dobjects.backend=db` | `-Dobjects.notes.db.url` | evidence / comments (append-only) |
| Job runs | `-Djobs.backend=postgres` (or `duckdb`) | `-Djobs.db.url` | job-execution reporting (success rate, p50/p95) |
| Flow provenance | `-Dprovenance.backend=postgres` (or `duckdb`) | `-Dprovenance.db.url` | per-edge record counts (T21) |

To use Postgres, give the URL property a `jdbc:postgresql://host:port/db` value and set the store's
backend flag. `objects/links/notes/status` accept any `jdbc:` URL directly on their URL property; `jobs`
and `provenance` additionally accept the case-insensitive aliases `postgres`/`postgresql` on their
`*.backend` flag (which then read the matching `*.db.url`), alongside the pre-existing `duckdb` and raw
`jdbc:` forms. Credentials are `-D<store>.db.user` / `-D<store>.db.password` (or embed them in the URL) —
`objects.db.user`/`.password` are shared by the objects/links/notes trio.

**Driver on the classpath.** The PostgreSQL JDBC driver is **not** bundled in the lean core (SBOM stays
minimal by design); it ships in **inspecto-connectors**. Put that module on the runtime classpath for a
Postgres backend — a `jdbc:postgresql://…` URL with no driver present fails closed with a clear message
and the store degrades to its in-memory / off default.

**Dialect note.** The SQL is otherwise engine-neutral; the one exception is the job-metrics percentiles.
`DbJobRunStore` detects the engine once at connect (`DatabaseMetaData`) and emits the correct continuous
percentile per dialect — DuckDB `quantile_cont(col, p)` vs PostgreSQL `percentile_cont(p) WITHIN GROUP
(ORDER BY col)` — so p50/p95 are correct on both.

---

