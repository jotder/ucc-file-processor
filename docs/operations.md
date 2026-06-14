# Operations: Utilities, Batching, Output & Deployment

> Part of the [Inspecto](../inspecto/README.md) documentation. See the [docs index](../inspecto/README.md#documentation).

## Pre-ETL Utility Suite

All pre-ETL utilities are invoked through the `ura` script (shipped alongside the JAR). Configuration is read directly from the pipeline `.toon` file — no separate properties file is needed.

```bash
# Deployed bundle
bash ura.sh [--dry-run] <command> <pipeline.toon>   # Linux / Mac
ura.bat     [--dry-run] <command> <pipeline.toon>   # Windows

# Local development (from inspecto/ directory)
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
| *(corrupt download)* | a fetched remote file fails its integrity check (size/checksum) | `quarantine/<source_path>/corrupt_download/` |

The `corrupt_download` reason is the acquisition-stage **dead-letter** (Data Acquisition Phase F): a fetched file
whose bytes don't match the listing size or server checksum is quarantined for inspection rather than ingested or
silently deleted; if no quarantine directory is configured it is deleted so it is never processed corrupt.

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
  duckdb_threads: 0       # PRAGMA threads per batch connection (0 = auto: cores ÷ threads)
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
| `processing.duckdb_threads` | `0` | `PRAGMA threads=N` on each batch's DuckDB connection. `0` = **auto** (`max(1, cores ÷ threads)`); `-1` = DuckDB's per-core default; positive `N` = exactly `N`. |

Each concurrent batch opens its own DuckDB connection, and DuckDB defaults to one thread per core — so an *unmanaged* per-core default gives an effective worker count of roughly `threads × cores`. With `threads=4` on a 16-core box that is 4 × 16 = 64 DuckDB workers fighting over 16 CPUs (≈100%-sys oversubscription stall). **Since v3.12.0 the default `duckdb_threads=0` auto-derives `max(1, cores ÷ threads)`**, so the concurrent batches divide the cores (e.g. `threads=4` on 16 cores → 4 threads each; `threads=16` on 56 cores → 3 each); a single batch keeps all cores. Set a positive value to tune manually, or `-1` to deliberately let one batch use the whole machine. The config validator warns at startup when an *explicit* `sources.max × threads × duckdb_threads` exceeds the available cores (it factors in `-Dsources.max` when set).

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

**Three multiplying caps.** Total worker pressure ≈ `sources.max × processing.threads × processing.duckdb_threads`. The auto-derive for `duckdb_threads=0` only divides cores among one source's `threads` — it does **not** know about `sources.max`. When running many sources in one JVM, set `duckdb_threads` explicitly (or lower `sources.max`/`threads`) so the three-way product ≈ cores — e.g. on 16 cores: `sources.max=4`, `threads=2`, `duckdb_threads=2`. When `-Dsources.max > 1` is set, the config validator surfaces this at startup: it factors `sources.max` into the explicit-oversubscription check, and for the auto default (`duckdb_threads=0`) it warns that the auto cap ignores `sources.max` and suggests a concrete value (`cores ÷ (sources.max × threads)`).

### Service mode — always-on host (`SourceService`)

For continuous operation, `SourceService` keeps the JVM up and runs the registry on a poll schedule instead of one-shot. It also emits a **batch-commit event** on every `SUCCESS` flush (carrying the partitions that batch wrote, from lineage) — the trigger backbone the Stage-2 enrichment and Control API build on.

```bash
# Scan paths for *_pipeline.toon (sources), *_enrich.toon (Stage-2 jobs)
# and *_job.toon (config-driven cron/event jobs, v2.8.0)
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

**Run-level audit & lineage.** Every recompute — event, scheduled, or CLI — is recorded under a `_audit` sibling of the output root (`<output.database>_audit/`), so it never collides with the partitioned output tree. Three append-only artifacts per job, all keyed by a correlating `run_id` (which is also the chain `BatchEvent` id, linking the audit to `/metrics` and the `inspecto.events` log):

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

A bearer token guards every route except the public `/health`, `/ready`, and `/metrics` (present it as `Authorization: Bearer <token>` or `X-Api-Token`). **As of v3.0 the API is fail-closed and scoped** — there is no open-by-default mode. Routes carry a scope; current control routes require the `CONTROL` scope (`-Dcontrol.token`). If a scope has no token configured, its routes return `401` (locked) rather than running open. Scopes are hierarchical — `CONTROL` satisfies everything; `assist.write` satisfies `assist.read`. The `assist.read` scope (`-Dassist.read.token`) backs the read-only `/catalog*`, `/config/spec/*` and `/assist/*` routes; `assist.write` (`-Dassist.write.token`) backs `POST /config/write`. Token comparison is constant-time.

| Method & path | Purpose |
|---|---|
| `GET /health`, `GET /ready` | liveness / readiness (open) |
| `GET /pipelines` | list pipelines + paused state + commit count |
| `POST /pipelines` | body `{"configPath":"…"}` — register a new pipeline live from a config under `-Dassist.write.root`, no restart (v4.1) |
| `POST /pipelines/{name}/trigger` | run one pipeline once |
| `POST /pipelines/{name}/pause` · `/resume` | pause (poll cycle skips it) / resume |
| `GET /pipelines/{name}/commits` | committed batch ids |
| `GET /pipelines/{name}/batches` · `/files` · `/lineage[?batchId=]` | audit queries (via `StatusStore`) |
| `GET /pipelines/{name}/quarantine` | quarantined inputs + reason |
| `GET /pipelines/{name}/pending` | inbox scan — files awaiting processing (`pending`), an under-processing flag (`running`), and live per-file progress (`current`: batch id, file, index/total, started-at; `null` when not mid-file); read-only, no audit side effects (v4.1) |
| `POST /pipelines/{name}/reprocess` | body `{"batchId":"…"}` — replay a batch |
| `POST /trigger` | run all pipelines once |
| `POST /validate` | body `{"configPath":"…"}` (saved file) or `{"type":…,"config":{…}[, "safety":true]}` (unsaved draft) — structured findings |
| `GET /status` | live status snapshot — all pipelines + rollup (v2.8.0) |
| `GET /report[?from=&to=]` | service-wide batch-audit report; optional date range (v2.8.0; range v2.10.0) |
| `GET /pipelines/{name}/report[?from=&to=]` | batch-audit report for one pipeline; optional date range (v2.8.0; range v2.10.0) |
| `GET /jobs` | list config-driven jobs + last outcome + next fire (v2.8.0) |
| `GET /jobs/{name}/runs` | recent run history for a job (v2.8.0) |
| `POST /jobs/{name}/trigger` | run a job once now (v2.8.0) |
| `GET /enrichment` | list Stage-2 enrichment jobs + trigger config + last run (v2.9.0) |
| `GET /enrichment/{job}/runs` | enrichment run-audit rows (v2.9.0) |
| `GET /enrichment/{job}/lineage[?runId=]` | enrichment output lineage rows (v2.9.0) |
| `GET /enrichment/{job}/report[?from=&to=]` | run-audit rollup for one enrichment job; optional date range (v2.9.0; range v2.10.0) |
| `GET /catalog` · `/catalog/tables/{id}` · `/catalog/kpis` · `/catalog/graph` | metadata graph (`assist.read` scope; v3.2.0) |
| `GET /config/spec/{type}` | declarative config spec for UI form rendering (`assist.read`; v3.2.0) |
| `GET /assist/diagnoses` | recent event-driven failure diagnoses (`assist.read`; v3.7.0) |
| `POST /assist/{intent}` | run an assist skill; `503` when the agent module is absent (`assist.read`; v3.3.0) |
| `POST /config/write` | body `{type, config, subdir?, overwrite?}` — persist a validated draft as `.toon` under `-Dassist.write.root` (`assist.write` scope; v4.1) |

```bash
curl -s -H "Authorization: Bearer secret" localhost:8080/pipelines
curl -s -X POST -H "Authorization: Bearer secret" localhost:8080/pipelines/adjustment_etl/trigger
```

**Authoring → save → register (v4.1).** With `-Dassist.write.root=<dir>` set, a validated config
draft can be persisted (`POST /config/write`, `assist.write` scope) and then registered as a live
pipeline (`POST /pipelines`, `CONTROL` scope) without a restart — the running service picks it up
on the next poll cycle. Both routes are fail-closed: unset write root ⇒ `503`; paths are jailed
under the root; drafts with ERROR-level findings (spec or hard-fail safety validator) ⇒ `422`;
an existing file ⇒ `409` unless `overwrite:true`. Registration is in-memory — keep the write root
inside the config tree the service was launched with so the pipeline also survives a restart.

**Serving the operator web console (Inspector).** The same `ControlApi` host can serve a built
Angular SPA as static files, so one process hosts both the API and the UI (v4.1):

- `-Dui.dir=<path>` — serve a built SPA bundle (the folder containing `index.html`). Unknown
  **GET** paths with no file extension fall back to `index.html` (SPA deep links), while unmatched
  **API** paths still return JSON `404` — routes always win over the static fallback. Static assets
  are PUBLIC (no token) so the shell loads before the operator connects. A path-traversal guard
  confines reads under the root.
- `-Dcontrol.cors=<origin>` (e.g. `http://localhost:4200`, or `*`) — enable CORS headers + `OPTIONS`
  preflight, for a separately-hosted dev SPA. Omit for prod (same-origin needs no CORS).

Both flags are **off by default**: unset, the control plane behaves exactly as a headless API. The
deploy bundle's `serve.sh` / `serve.bat` wire these up automatically (`-Dui.dir=./ui` when a `ui/`
folder is present; `CORS_ORIGIN` env → `-Dcontrol.cors`). See the
[Operator Console guide](operator-console.md) for the full UI walkthrough.

```bash
# bundle root — serve API + UI on :8080, reading tokens from the environment
CONTROL_TOKEN=secret ASSIST_TOKEN=secret bash serve.sh        # Linux/Mac
set CONTROL_TOKEN=secret && serve.bat                         # Windows
# then open http://localhost:8080/
```

### Reports — status snapshot & batch-audit rollup (`ReportService`)

The audit queries above return raw rows; the **report** endpoints return the aggregated
view operators and dashboards actually want — computed on demand through the same
`StatusStore`, so they work identically over the file or DB backend.

- `GET /status` — a live snapshot: per-pipeline paused state, committed-batch count,
  quarantined-file count, and last-batch id/status/time, plus a service rollup.
- `GET /report` (service-wide) and `GET /pipelines/{name}/report` (one pipeline) — a
  historical batch-audit rollup: total/success/failed batch counts, **error rate**, input
  & output rows, rejected files, output file count and bytes, average / max duration, and
  duration **percentiles** (`p50`/`p95`/`p99`) so a tail-latency spike isn't hidden by the mean.

**Date ranges (v2.10.0).** The `/report`, `/pipelines/{name}/report` and
`/enrichment/{job}/report` endpoints accept an optional inclusive `?from=&to=` window — a
date (`2026-05-01`) or datetime (`2026-05-01 09:00:00`). A date-only `to` covers the whole
day. The rollup (counts, percentiles, first/last time) is computed over just the rows whose
`start_time` falls in the range; the applied bounds are echoed back as `windowFrom`/`windowTo`
(blank = unbounded). Filtering is a lexicographic compare on the audit timestamp, so it works
identically over the file and DB backends.

```bash
curl -s -H "Authorization: Bearer secret" localhost:8080/status
curl -s -H "Authorization: Bearer secret" localhost:8080/pipelines/adjustment_etl/report
# just last month, with p50/p95/p99 over that window:
curl -s -H "Authorization: Bearer secret" \
  "localhost:8080/pipelines/adjustment_etl/report?from=2026-04-01&to=2026-04-30"
```

### Enrichment run audit over the API (`EnrichmentAuditReader`)

Stage-2 recomputes persist a durable run-level ledger (`<output>_audit/<job>_enrich_runs.csv`
+ `_enrich_lineage.csv`, written by `EnrichmentAuditWriter`). From v2.9.0 that ledger is
readable over the Control API — the Stage-2 counterpart to the `/pipelines/{name}/batches`
+ `/lineage` + `/report` surface — so you can answer "did the nightly KPI run, and what did
it write?" without shelling onto the box.

- `GET /enrichment` — list each hosted enrichment job with its trigger config
  (`onPipeline`, `scheduleSeconds`, `eventTriggered`, `scheduled`) and last-run summary
  (`runCount`, `lastStatus`, `lastRunTime`).
- `GET /enrichment/{job}/runs` — the raw run-audit rows (one per recompute, SUCCESS and
  FAILED): trigger, reason, scope, input/output partition & file counts, rows, bytes,
  duration, error.
- `GET /enrichment/{job}/lineage[?runId=]` — one row per written output partition file;
  `?runId=` narrows to a single recompute.
- `GET /enrichment/{job}/report` — the aggregated rollup (mirrors the Stage-1 batch report):
  total/success/failed run counts, **error rate**, output rows/files/bytes, avg/max duration,
  first/last run time.

All four require auth and return `404` when no enrichment is registered, or the named job
is unknown.

```bash
curl -s -H "Authorization: Bearer secret" localhost:8080/enrichment
curl -s -H "Authorization: Bearer secret" localhost:8080/enrichment/EVENTS_DAILY_KPI/report
```

### Config-driven jobs — cron / event / manual (`JobService`)

Beyond the fixed poll cycle, define arbitrary **jobs** in `*_job.toon` files (scanned from
the same paths as pipelines). One uniform scheduler runs four kinds of work — `ingest`
(a Stage-1 pipeline), `enrich` (a Stage-2 job), `report` (emit a report snapshot to the
`inspecto.events` log), and `maintenance` (built-in housekeeping) — each triggered by a cron
expression, an upstream batch-commit event, and/or a manual `POST`.

```toon
# nightly_clean_job.toon  — prune audit CSVs older than 30 days at 02:00 daily
job:
  name: nightly-clean
  type: maintenance
  cron: "0 2 * * *"          # 5 fields (min hour dom mon dow) or 6 (with leading seconds)
  task: cleanup
  dir: /var/lib/inspecto/test-status
  retention_days: 30
  glob: "*.csv"
```

```toon
# kpi_refresh_job.toon  — recompute a KPI hourly AND whenever EVENTS commits a batch
job:
  name: kpi-refresh
  type: enrich
  cron: "0 0 * * * *"        # top of every hour
  on_pipeline: EVENTS         # also fire on the upstream's batch-commit event
  config: config/events/events_daily_kpi_enrich.toon
```

The cron parser is a small, dependency-free quartz-like engine: `*`, ranges (`9-17`),
steps (`*/15`, `9-17/2`), lists (`MON,WED,FRI`) and month/day names. When both
day-of-month and day-of-week are restricted, a time matches if **either** does (Vixie-cron
semantics). Every run is recorded to `jobs_audit/jobs_runs.csv` (override with
`-Djobs.audit.dir`) and a short in-memory history the API serves.

```bash
curl -s -H "Authorization: Bearer secret" localhost:8080/jobs
curl -s -X POST -H "Authorization: Bearer secret" localhost:8080/jobs/nightly-clean/trigger
curl -s -H "Authorization: Bearer secret" localhost:8080/jobs/nightly-clean/runs
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
URL given it opens a local file `inspecto-status.db`:

```bash
# DuckDB (default DB backend — embedded, single-process, zero extra deps)
java -cp file-processor.jar com.gamma.control.ControlApi \
     -Dcontrol.token=secret \
     -Dstatus.backend=db \
     -Dstatus.db.url="jdbc:duckdb:/var/lib/inspecto/status.db" \
     config/
```

The store is engine-neutral JDBC over portable SQL. It creates its schema on first connect
(`inspecto_status_{commits,batches,files,lineage,quarantine}`; pre-rebrand `ucc_status_*`
tables are renamed in place on first connect, and a legacy default `ucc-status.db` file is
still picked up when no `inspecto-status.db` exists) and **syncs at startup and after
every poll cycle**, so the DB reflects the latest committed state (up to one cycle of
staleness) and the API/observability transparently read from it — no endpoint changes. A sync
is a transactional DELETE-then-INSERT per pipeline, so it is idempotent and doubles as the
migrate/backfill of existing file audit into the database.

> **Future / distributed:** the same code path runs on **PostgreSQL** for a multi-writer or
> multi-node deployment — point the URL at `jdbc:postgresql://host:5432/inspecto` (with
> `-Dstatus.db.user`/`.password`) and put the PostgreSQL JDBC driver on the classpath. The
> driver is *not* bundled (bring-your-own) to keep the default fat-JAR lean; DuckDB's
> single-process file lock is fine for the current single-JVM service, and Postgres is what
> you switch to when you split into separate processes/nodes.

### Object backend (Alert Center) — in-memory (default) or database (`DbObjectStore`)

The Alert Center (Phase 2) records **operational objects** — managed, *mutable* things with a
lifecycle (an `ALERT` walks `OPEN → ACKNOWLEDGED → RESOLVED`), the counterpart to the immutable
event log. Because they mutate they live in a table store, not Parquet. By default they are held
in memory (`-Dobjects.backend=memory`); set `-Dobjects.backend=db` for a durable store — the same
engine-neutral JDBC-over-DuckDB pattern as the status backend (no extra dependency), default file
`inspecto-ops.db`:

```bash
java -cp file-processor.jar com.gamma.control.ControlApi \
     -Dcontrol.token=secret \
     -Dobjects.backend=db \
     -Dobjects.db.url="jdbc:duckdb:/var/lib/inspecto/ops.db" \
     config/
```

It creates table `inspecto_ops_objects` on first connect and does a real `UPDATE` on each
transition (unlike the status store's DELETE-then-INSERT projection — these objects are the source
of truth). A fired alert is promoted to an `OPERATIONAL_OBJECT(ALERT)` linked to the triggering
event, and every transition emits an `OBJECT_OPENED`/`OBJECT_ACTIVITY` event so the change shows in
the event log. Operate the objects over the Control API (CONTROL scope):

```bash
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects?type=ALERT&status=OPEN"
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/ack
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/resolve
```

> **Future / distributed:** like the status backend, point `-Dobjects.db.url` at
> `jdbc:postgresql://host:5432/inspecto` (with `-Dobjects.db.user`/`.password` and the PostgreSQL
> JDBC driver on the classpath) for a multi-writer deployment. The default lifecycle can be
> overridden with a `*_workflow.toon`.

### Issue Tracker (Phase 3) — operator-created issues + SLA tracking

Issues reuse the **same** `inspecto_ops_objects` table and workflow engine as alerts — only
`object_type=ISSUE` differs, so there is no new storage or backend to configure. Where an `ALERT` is
auto-promoted from a fired rule, an `ISSUE` is **operator-created** with `POST /objects` and walks a
richer lifecycle `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` (actions `assign`/`start`/
`resolve`/`close`; only `CLOSED` is terminal). Drive the moves through the generic
`/objects/{id}/transition` route:

```bash
# create an issue with a 2-hour SLA (dueInMinutes; or pass an absolute dueAt in epoch millis)
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects \
  -d '{"title":"reconcile mismatch on pipeX","severity":"HIGH","assignee":"alice","priority":"P1","dueInMinutes":120}'
# walk the lifecycle
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/transition -d '{"action":"assign","actor":"alice"}'
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/transition -d '{"action":"start"}'
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/transition -d '{"action":"resolve"}'
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects?type=ISSUE&status=IN_PROGRESS"
```

**SLA tracking** is opt-in per issue: set `dueAt` (epoch millis) or `dueInMinutes` at creation. A
scheduled sweep then breaches any issue that passes its deadline while still being worked (i.e. not
yet `RESOLVED`/`CLOSED`); each breach stamps a `slaBreachedAt` marker on the object (so it fires once)
and emits an **`OBJECT_SLA_BREACH`** event into the event log, where it surfaces in `/events`
alongside the issue's activity. The cadence is `-Dobjects.sla.sweep.seconds` (default `60`; set `0`
to disable):

```bash
java -cp file-processor.jar com.gamma.control.ControlApi \
     -Dcontrol.token=secret -Dobjects.sla.sweep.seconds=30 config/
# find breached issues via the event feed
curl -s -H "Authorization: Bearer secret" "localhost:8080/events/search?type=OBJECT_SLA_BREACH"
```

### Case Management (Phase 4) — correlation links & graph

A `CASE` (`object_type=CASE`, lifecycle `OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED`) groups
the alerts and issues of one investigation. Phase 4 makes **correlation first-class**: an append-only
`OBJECT_LINK` graph records directed edges between objects — `Case CONTAINS Issue`, `Issue
ESCALATED_FROM Alert`, `Alert CAUSED_BY Event` — so you can pivot from any object to everything related
to it. Links follow the same `-Dobjects.backend` toggle (durable in their own DuckDB file
`inspecto-ops-links.db`, or one Postgres alongside the objects).

```bash
# create a case, then link the issue it contains
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects \
  -d '{"type":"CASE","title":"Q2 reconciliation incident","severity":"HIGH"}'
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<caseId>/links \
  -d '{"to":"<issueId>","relationship":"contains","actor":"alice"}'
# the case's neighbourhood, and a 2-hop correlation subgraph (nodes + edges)
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects/<caseId>/links"
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects/<caseId>/graph?depth=2"
# every correlation also lands in the event feed
curl -s -H "Authorization: Bearer secret" "localhost:8080/events/search?type=OBJECT_LINKED"
```

> Links are immutable facts (append-only — no edit/delete), like events.

**Evidence — comments, attachments, RCA.** An object also carries an append-only **note** thread:
free-text *comments* and *attachment* references (file/URL metadata — the bytes stay out of the lean
core). An **RCA template** seeds one comment per section, giving an investigator a structured skeleton to
fill in — supplied inline (`{sections[]}`) or authored as a `*_rca.toon` (loaded at startup from the same
config paths, listed at `GET /rca/templates`, applied by `{template:"<name>"}`). Notes follow the same
`-Dobjects.backend` toggle (their own DuckDB file `inspecto-ops-notes.db` when durable).

```bash
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/comments \
  -d '{"author":"alice","body":"reproduced on pipeX; investigating the reconciler"}'
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/attachments \
  -d '{"name":"trace.log","uri":"s3://evidence/trace.log","contentType":"text/plain","author":"alice"}'
# seed an RCA skeleton (one comment per section), then read the thread
curl -s -H "Authorization: Bearer secret" -X POST localhost:8080/objects/<id>/rca \
  -d '{"sections":["Summary","Timeline","Root cause","Impact","Remediation"],"actor":"alice"}'
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects/<id>/comments"
curl -s -H "Authorization: Bearer secret" "localhost:8080/objects/<id>/attachments"
```

### Observability — metrics & structured events

The Control API host also exposes `GET /metrics` (open — scrapers don't carry tokens) in **Prometheus text format**, served from a zero-dependency in-process registry. No extra agent or sidecar.

```bash
curl -s localhost:8080/metrics
```

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `inspecto_batches_total` | counter | `pipeline`, `status` | terminal batches (SUCCESS + FAILED) |
| `inspecto_output_rows_total` | counter | `pipeline` | rows written by committed batches |
| `inspecto_rejected_files_total` | counter | `pipeline` | quarantined member files |
| `inspecto_partitions_written_total` | counter | `pipeline` | output partitions written |
| `inspecto_batch_duration_seconds` | histogram | `pipeline` | batch wall-clock latency |
| `inspecto_enrichment_recomputes_total` | counter | `job`, `trigger` | Stage-2 recomputes (event vs schedule) |
| `inspecto_enrichment_duration_seconds` | histogram | `job` | enrichment recompute latency |
| `inspecto_poll_cycles_total` · `inspecto_source_run_failures_total` | counter | — | poll cycles run / source-run failures |
| `inspecto_active_runs` | gauge | — | source runs currently executing |
| `inspecto_committed_batches` · `inspecto_quarantine_files` | gauge | `pipeline` | durable commit count / quarantine depth |
| `inspecto_inbox_oldest_seconds` | gauge | `pipeline` | **lag** — age of the oldest unprocessed inbox file |
| `inspecto_paused` | gauge | `pipeline` | 1 if paused |
| `inspecto_files_waiting_stability` | gauge | `pipeline` | discovered files held back by the readiness gate |
| `inspecto_duplicates_skipped_total` | counter | `pipeline` | files skipped by content-based dedup |
| `inspecto_sequence_gaps_total` | counter | `pipeline` | missing files detected in a configured sequence |
| `inspecto_files_discovered_total` · `inspecto_files_downloaded_total` | counter | `pipeline` | remote files listed / fetched+validated |
| `inspecto_downloads_failed_total` · `inspecto_post_actions_failed_total` | counter | `pipeline` | fetch/integrity failures / failed source post-actions |
| `inspecto_bytes_transferred_total` | counter | `pipeline` | bytes retrieved from source connectors |
| `inspecto_fetch_seconds` | histogram | `pipeline` | time to fetch one remote file |
| `inspecto_active_connections` | gauge | `pipeline` | open source-connector sessions |

Eager metrics are recorded off the batch-commit event; the point-in-time gauges (lag, quarantine depth, commit count) are computed lazily when `/metrics` is scraped, so they reflect current state without a polling loop.

**Structured event log.** Alongside the human logs, the `inspecto.events` logger emits one JSON line per batch — correlatable by `batch_id`:

```json
{"event":"batch","pipeline":"adjustment_etl","batch_id":"20260530_000652_default_0001","status":"SUCCESS","rows":3,"partitions":2,"rejected":0,"duration_ms":650}
```

Route that logger to a file or shipper to stream batch events into a log pipeline.

**Acquisition lifecycle events.** When a `source:` block is configured, the `EventLog` emits structured facts
for the file-acquisition lifecycle (queryable via `GET /events`): `FILE_DISCOVERED` → `FILE_STABLE` →
`FILE_FETCHED` → `FILE_VALIDATED` → (`FILE_CHANGED`) → `FILE_ARCHIVED`, plus the failure/operational facts
`FILE_FETCH_FAILED`, `SEQUENCE_GAP`, and `SOURCE_CIRCUIT_OPEN`. A `SEQUENCE_GAP` is automatically promoted to a
managed ALERT object (trackable in Cases/Issues) by the service tier.

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
# From the sandbox root or from inside inspecto/:
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1

# Skip the Maven build if the JAR is already current:
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1 -NoBuild
```

This produces **`file-processor-deploy.zip`** in the sandbox root. The script:
1. Runs `mvn clean package` to build a fresh fat JAR
2. Builds the optional operator UI (`inspecto-ui/` via npm) and bundles its `dist/` as `ui/` — skip with `-NoUi`, or omitted automatically when `inspecto-ui/` is absent
3. Assembles a self-contained bundle with the JAR, config files, and run/serve scripts
4. Rewrites `schema_file` paths in the bundled configs so they are relative to the bundle root
5. Creates all placeholder directories (inbox, database, backup, temp, errors, quarantine)
6. Zips everything into `file-processor-deploy.zip`

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
  ui/                         ← built Inspector SPA (present only when inspecto-ui/ was built); served via -Dui.dir=./ui
  run.sh                      ← Linux/Mac ETL launcher  (java -jar ... <adapter>_pipeline.toon)
  run.bat                     ← Windows ETL launcher
  serve.sh                    ← Linux/Mac control-plane + UI launcher (ControlApi; reads CONTROL_TOKEN/ASSIST_TOKEN/PORT/CORS_ORIGIN)
  serve.bat                   ← Windows control-plane + UI launcher
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

# 6. OR run the always-on control plane + operator console (serves every pipeline under config/)
CONTROL_TOKEN=secret ASSIST_TOKEN=secret bash serve.sh    # Linux/Mac → http://localhost:8080/
set CONTROL_TOKEN=secret && serve.bat                     # Windows
```

**Direct invocation** (without the run scripts):
```bash
java --enable-native-access=ALL-UNNAMED \
     -jar file-processor.jar \
     config/<data_source>/<data_source>_pipeline.toon
```

**Java requirement:** Java 25 or later. No other runtime dependencies.

### Performance reference (single-node, HDD, 4 threads)

| Source | Files | Rows/file (avg) | Time/file | Total (30 days) |
|---|---|---|---|---|
| <data_source> | 30 × `.csv.gz` | ~2.3 M | ~19 min | ~2.5 hr |
| <data_source> | varies | ~420 K | ~3.4 min | — |

Note: the 20200117 <data_source> file is ~4.3 GB uncompressed (~2.97 M rows) due to ISIZE overflow in the gz header — the ETL handles it transparently via streaming.

### Pre-production checklist (<data_source>)

- [ ] Delete `inbox/<data_source>/20200101/vou_DATE_20200101.csv/` — this is an 8 GB uncompressed directory (duplicate of the `.gz`); the glob pattern would pick up the file inside it and double-process the day
- [ ] Run from the bundle root (or sandbox root locally) so relative paths resolve correctly
- [ ] Verify Java 25 is on `PATH`: `java -version`

---

## Onboarding a New Source

1. **Write a generation config** — copy `config/<data_source>/adj_gen.toon` and adapt:
   - Set the correct `delimiter`, skip counts, and date formats
   - List column names that should be forced to `DATE` or `TIMESTAMP` in `type_patterns`

2. **Run `create-schema`** against a representative sample file:
   ```bash
   # From the inspecto/ directory
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
        -jar target/file-processor-<version>.jar \
        config/mysource/mysource_pipeline.toon
   ```

---

