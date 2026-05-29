# Operations: Utilities, Batching, Output & Deployment

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

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
  threads: 4              # max batches processed concurrently (see Concurrency below)
  duckdb_threads: 4       # PRAGMA threads per batch connection (0 = DuckDB default)
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

### Concurrency (M..N parallelism)

Within a single source run, batches execute on a **virtual-thread executor bounded by a semaphore**. Every batch is submitted up front; at most `processing.threads` of them do heavy work at once, and a batch blocked on file I/O or DuckDB parks its (virtual) carrier cheaply instead of pinning a platform thread. So `threads` is a *concurrency cap*, not a fixed pool size — raise it to overlap more I/O-bound batches without paying for idle platform threads.

Two knobs control CPU/I/O pressure, and they multiply:

| Key | Default | Controls |
|---|---|---|
| `processing.threads` | `4` | How many batches run concurrently (semaphore permits). |
| `processing.duckdb_threads` | `0` | `PRAGMA threads=N` on each batch's DuckDB connection. `0` leaves DuckDB's default (one thread per core). |

Each concurrent batch opens its own DuckDB connection, so the effective worker count is roughly `threads × duckdb_threads`. With `duckdb_threads=0` and `threads=4` on a 16-core box you can momentarily run 4 × 16 = 64 DuckDB workers and oversubscribe the CPU. Set `duckdb_threads` so the product ≈ core count (e.g. `threads=4, duckdb_threads=4` on 16 cores). The config validator warns when `threads × duckdb_threads` exceeds the available cores.

### Multiple sources in one process

`MultiSourceProcessor` runs several sources (each its own `pipeline.toon`) concurrently in a single JVM — the outer layer of the M..N model, composing the per-source batch runner above.

```bash
# one or more pipeline toons and/or directories (searched for *_pipeline.toon)
java -cp file-processor.jar com.gamma.inspector.MultiSourceProcessor \
     -Dsources.max=4 \
     config/adjustment/adjustment_pipeline.toon \
     config/voucher/voucher_unknown_pipeline.toon
# or point it at a directory tree of configs:
java -cp file-processor.jar com.gamma.inspector.MultiSourceProcessor config/
```

Sources run on a virtual-thread executor bounded by `-Dsources.max` (default: all resolved sources in parallel). Each source is isolated — one source failing (bad config or batch failures) is logged and counted but never aborts the others; the process exits non-zero if any source failed. A failed source does not stop the rest.

**Three multiplying caps.** Total worker pressure ≈ `sources.max × processing.threads × processing.duckdb_threads`. Size them together for the host — e.g. on 16 cores: `sources.max=4`, `threads=2`, `duckdb_threads=2`.

### Service mode — always-on host (`SourceService`)

For continuous operation, `SourceService` keeps the JVM up and runs the registry on a poll schedule instead of one-shot. It also emits a **batch-commit event** on every `SUCCESS` flush (carrying the partitions that batch wrote, from lineage) — the trigger backbone the Stage-2 enrichment and Control API build on.

```bash
# Scan paths for *_pipeline.toon (sources) and *_enrich.toon (Stage-2 jobs)
java -cp file-processor.jar com.gamma.service.SourceService \
     -Dservice.poll.seconds=60 -Dservice.max.runs=4 config/
```

Each poll cycle reloads configs (so edits are picked up without a restart) and runs the registry via the same bounded virtual-thread executor as `MultiSourceProcessor`. On startup it reports each pipeline's previously committed batches (from the commit log) for recovery visibility; batch atomicity + marker dedup already make an interrupted batch safe to reprocess next cycle.

### Stage-2 enrichment (`EnrichmentProcessor`)

The multiplexer (Stage 1) deliberately does no joins/aggregation. The separate **enrichment engine** turns its partitioned output into reports/KPIs, reading the Hive-partitioned tree with DuckDB, applying a configured columnar transform (reference joins + aggregation), and writing a new partitioned dataset **idempotently** (`OVERWRITE_OR_IGNORE`). Config is an `*_enrich.toon` (see `config/events/events_daily_kpi.toon`).

```bash
# Full recompute over all input partitions
java -cp file-processor.jar com.gamma.enrich.EnrichmentProcessor config/events/events_daily_kpi.toon
# Incremental — recompute only the given partitions (semicolon-separated)
java -cp file-processor.jar com.gamma.enrich.EnrichmentProcessor config/events/events_daily_kpi.toon \
     --partitions "event_type=CALL/year=2020/month=04/day=03"
```

When hosted by `SourceService`, an enrichment's optional `triggers` section drives it automatically:

```
triggers:
  on_pipeline: events        # freshness: recompute the committed partitions when that
                             # Stage-1 pipeline (or an upstream enrichment, by name) commits
  schedule_seconds: 3600     # completeness: full recompute on this interval (reconciles late data)
```

Chains form naturally — set `on_pipeline` to an upstream enrichment's `name` and it fires on that enrichment's own commit. Recomputes for one job are serialised (a per-job lock), and idempotent writes make event + schedule overlap converge.

**Run-level audit & lineage.** Every recompute — event, scheduled, or CLI — is recorded under a `_audit` sibling of the output root (`<output.database>_audit/`), so it never collides with the partitioned output tree. Three append-only artifacts per job, all keyed by a correlating `run_id` (which is also the chain `BatchEvent` id, linking the audit to `/metrics` and the `ucc.events` log):

| File | Rows |
|---|---|
| `<job>_enrich_runs.csv` | one per recompute (SUCCESS **and** FAILED): trigger (`event`/`schedule`/`cli`), reason, input scope, output partition/file counts, total rows, bytes, duration, error |
| `<job>_enrich_lineage.csv` | one per written output partition file (run_id, partition, file, bytes) |
| `<job>_enrich_commits.log` | durable, fsync'd `CommitLog` of successful runs (the "did this recompute finish" ledger) |

### Control API — REST control plane (`ControlApi`)

`ControlApi` runs the service **with** an embedded REST surface (JDK `HttpServer`, no extra deps), so every CLI operation is reachable over HTTP — for operators now, UI/agent later.

```bash
java -cp file-processor.jar com.gamma.control.ControlApi \
     -Dcontrol.port=8080 -Dcontrol.token=secret \
     -Dservice.poll.seconds=60 config/
```

A bearer token guards every route except `/health` and `/ready` (present it as `Authorization: Bearer <token>` or `X-Api-Token`). If no token is set the API runs open, with a warning (dev only).

| Method & path | Purpose |
|---|---|
| `GET /health`, `GET /ready` | liveness / readiness (open) |
| `GET /pipelines` | list pipelines + paused state + commit count |
| `POST /pipelines/{name}/trigger` | run one pipeline once |
| `POST /pipelines/{name}/pause` · `/resume` | pause (poll cycle skips it) / resume |
| `GET /pipelines/{name}/commits` | committed batch ids |
| `GET /pipelines/{name}/batches` · `/files` · `/lineage[?batchId=]` | audit queries (via `StatusStore`) |
| `GET /pipelines/{name}/quarantine` | quarantined inputs + reason |
| `POST /pipelines/{name}/reprocess` | body `{"batchId":"…"}` — replay a batch |
| `POST /trigger` | run all pipelines once |
| `POST /validate` | body `{"configPath":"…"}` — config warnings |

```bash
curl -s -H "Authorization: Bearer secret" localhost:8080/pipelines
curl -s -X POST -H "Authorization: Bearer secret" localhost:8080/pipelines/adjustment_etl/trigger
```

### Status backend — file (default) or database (`DbStatusStore`)

The audit queries above (`commits`/`batches`/`files`/`lineage`/`quarantine`) and the
observability gauges read through a pluggable **`StatusStore`**. By default it reads the
on-disk audit artifacts directly (`FileStatusStore`). Set `-Dstatus.backend=db` to make the
service project that audit into a database and serve queries from it instead — durable and
SQL-queryable, while ingest keeps writing the file audit unchanged (it stays the write-time
source of truth and survives a DB outage).

The DB engine is **DuckDB by default** — already bundled for ingest/enrichment, so the DB
backend adds **no new dependency** and the same engine serves tests and production. With no
URL given it opens a local file `ucc-status.db`:

```bash
# DuckDB (default DB backend — embedded, single-process, zero extra deps)
java -cp file-processor.jar com.gamma.control.ControlApi \
     -Dcontrol.token=secret \
     -Dstatus.backend=db \
     -Dstatus.db.url="jdbc:duckdb:/var/lib/ucc/status.db" \
     config/
```

The store is engine-neutral JDBC over portable SQL. It creates its schema on first connect
(`ucc_status_{commits,batches,files,lineage,quarantine}`) and **syncs at startup and after
every poll cycle**, so the DB reflects the latest committed state (up to one cycle of
staleness) and the API/observability transparently read from it — no endpoint changes. A sync
is a transactional DELETE-then-INSERT per pipeline, so it is idempotent and doubles as the
migrate/backfill of existing file audit into the database.

> **Future / distributed:** the same code path runs on **PostgreSQL** for a multi-writer or
> multi-node deployment — point the URL at `jdbc:postgresql://host:5432/ucc` (with
> `-Dstatus.db.user`/`.password`) and put the PostgreSQL JDBC driver on the classpath. The
> driver is *not* bundled (bring-your-own) to keep the default fat-JAR lean; DuckDB's
> single-process file lock is fine for the current single-JVM service, and Postgres is what
> you switch to when you split into separate processes/nodes.

### Observability — metrics & structured events

The Control API host also exposes `GET /metrics` (open — scrapers don't carry tokens) in **Prometheus text format**, served from a zero-dependency in-process registry. No extra agent or sidecar.

```bash
curl -s localhost:8080/metrics
```

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `ucc_batches_total` | counter | `pipeline`, `status` | terminal batches (SUCCESS + FAILED) |
| `ucc_output_rows_total` | counter | `pipeline` | rows written by committed batches |
| `ucc_rejected_files_total` | counter | `pipeline` | quarantined member files |
| `ucc_partitions_written_total` | counter | `pipeline` | output partitions written |
| `ucc_batch_duration_seconds` | histogram | `pipeline` | batch wall-clock latency |
| `ucc_enrichment_recomputes_total` | counter | `job`, `trigger` | Stage-2 recomputes (event vs schedule) |
| `ucc_enrichment_duration_seconds` | histogram | `job` | enrichment recompute latency |
| `ucc_poll_cycles_total` · `ucc_source_run_failures_total` | counter | — | poll cycles run / source-run failures |
| `ucc_active_runs` | gauge | — | source runs currently executing |
| `ucc_committed_batches` · `ucc_quarantine_files` | gauge | `pipeline` | durable commit count / quarantine depth |
| `ucc_inbox_oldest_seconds` | gauge | `pipeline` | **lag** — age of the oldest unprocessed inbox file |
| `ucc_paused` | gauge | `pipeline` | 1 if paused |

Eager metrics are recorded off the batch-commit event; the point-in-time gauges (lag, quarantine depth, commit count) are computed lazily when `/metrics` is scraped, so they reflect current state without a polling loop.

**Structured event log.** Alongside the human logs, the `ucc.events` logger emits one JSON line per batch — correlatable by `batch_id`:

```json
{"event":"batch","pipeline":"adjustment_etl","batch_id":"20260530_000652_default_0001","status":"SUCCESS","rows":3,"partitions":2,"rejected":0,"duration_ms":650}
```

Route that logger to a file or shipper to stream batch events into a log pipeline.

### Audit files written to `dirs.status_dir`

Each ETL run produces three timestamped CSVs alongside the existing status file. All three use the same `<yyyyMMdd_HHmmss>` timestamp suffix so runs never overwrite each other.

| File | Description |
|---|---|
| `<pipeline>_status_<ts>.csv` | One row per processed file — same as before, with a `batch_id` column appended at the end |
| `<pipeline>_batches_<ts>.csv` | One row per batch: batch_id, member count, total input bytes, total output bytes, status, duration |
| `<pipeline>_lineage_<ts>.csv` | Input-to-output row-count matrix — one row per (input file, output partition) pair |

### Commit log — the durable "did this batch finish" ledger

In addition to the three per-run CSVs, a single **persistent, append-only** commit log accumulates across every run of the pipeline:

```
<status_dir>/<pipeline>_commits.log
```

Each committed batch appends one line — `committed_at,batch_id,pipeline,status,member_count,output_count,output_rows,output_bytes` — and the write is **`fsync`'d to disk** before the batch is considered done. Unlike the per-run `_batches_<ts>.csv` (which is buffered and can lose its tail if the process is killed), a line in the commit log durably means the batch's outputs, manifest, backup, and markers are all on disk. It's the file to `grep` when you need to answer "did batch X finish?" definitively:

```bash
grep ',SUCCESS,' status/<adapter>/<pipeline>_commits.log   # every committed batch
```

Disabled (not written) when `dirs.status_dir` is unset. The path is available programmatically as `cfg.dirs().commitLogPath()`, and `CommitLog.committedBatchIds()` reads back the set of SUCCESS batch ids for recovery tooling.

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

### Crash semantics — batch commit ordering

`BatchProcessor.commit` writes durable state in a specific order so a process crash mid-commit is idempotent on rerun:

1. **DuckLake register** (optional, non-fatal — log and continue if catalog is unreachable).
2. **Manifest write** — required for reprocess; this is what `ura reprocess` reads.
3. **Backup originals** — moves files out of the inbox into `dirs.backup`.
4. **Marker files** — last. A marker means "this file is fully durable, skip on next poll."

A crash at any point before step 4 leaves the input file in the inbox without a marker, so the next poll cycle re-processes it. Output writes use `OVERWRITE_OR_IGNORE`, so re-running the same data is safe. The only way a file is "stranded" (in backup, never marked) is if backup completes and the marker write fails — `MarkerManager` logs to SLF4J in that case, and a rerun will not re-process the file (no markers) but the file is also no longer in the inbox.

### Exit codes

`SourceProcessor.main` returns:

| Exit code | Meaning |
|---|---|
| `0` | All planned batches succeeded; no files quarantined. |
| `1` | Invalid invocation (missing `pipeline.toon` argument). |
| `2` | At least one batch threw an exception. Per-batch stack traces are in the log; aggregate `BatchProcessingException` message is on stderr. |

Wrapper scripts (`run.sh`, `run.bat`, cron jobs) should treat non-zero as failure and alert.

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

