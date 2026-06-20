# Inspecto Examples — play with every feature

A growing set of **self-contained, runnable** example pipelines. Each one ships tiny synthetic
sample data, runs offline with a single command, and writes **only** under its own `out/`
directory — so you can run, inspect, delete `out/`, and re-run freely without touching anything else.

> **Status:** this catalog is being built out feature-by-feature. The examples below are the
> Stage-1 ingest / parsing / schema / output core (all verified end-to-end). Acquisition, jobs,
> authored flows, Stage-2 enrichment, and operational-intelligence examples — plus an
> `_reference/` set of shape-correct templates for features that need external infra (SFTP/FTP/DB
> connections) or aren't runnable offline — are landing in subsequent batches.

## How to run

```bash
# from this examples/ directory (works in the source tree and the release bundle):
pwsh run-example.ps1 01-ingest/hello-csv          # Windows / PowerShell 7
bash run-example.sh  01-ingest/hello-csv          # Linux / Git-Bash
#   add  -Clean  (ps1)  or  --clean  (sh)  to wipe out/ and start fresh
```

The runner resolves the engine JAR automatically (`$INSPECTO_JAR` → `../file-processor.jar` in the
bundle → `../target/file-processor-*.jar` in the source tree), creates the output dirs, seeds a
fresh `out/inbox/` from the pristine `samples/`, and runs the pipeline with the mandatory DuckDB
flag. To run by hand instead:

```bash
cd 01-ingest/hello-csv
mkdir -p out/inbox && cp samples/* out/inbox/
java --enable-native-access=ALL-UNNAMED -jar <path-to>/file-processor.jar pipeline.toon
#   output lands in out/database/  (Hive-partitioned: year=/month=/day=)
```

Each example folder contains: `pipeline.toon` (+ `schema.toon` and/or `grammar.toon`), a
`samples/` dir (committed, never modified), and a `README` line below. `out/` is generated and
git-ignored.

## Catalog

| Example | Feature it demonstrates | Run |
|---|---|---|
| **01-ingest/hello-csv** | The minimum viable pipeline: CSV → Hive-partitioned Parquet, `active: true`, 4 typed columns. Start here. | `run-example 01-ingest/hello-csv` |
| **02-parsing/pipe-delimited** | A non-comma delimiter (`delimiter: "\|"`). Any single char works (`,` `;` `\|`); for a real tab use an actual tab character. | `… 02-parsing/pipe-delimited` |
| **02-parsing/no-header** | Headerless input (`has_header: false`) — columns bound purely by position. | `… 02-parsing/no-header` |
| **02-parsing/compressed-gzip** | Transparent `.csv.gz` ingest (also `.bz2`, `.zip`) — no manual decompression. | `… 02-parsing/compressed-gzip` |
| **02-parsing/fixedwidth** | Fixed-width text via an external `grammar.toon` (`frontend: fixedwidth`, column slices by start/length). | `… 02-parsing/fixedwidth` |
| **03-schema-transform/expr-transform** | Per-record `EXPR` transforms: `UPPER(TRIM(...))` and a derived `GROSS = ROUND(net*1.1, 2)` column. | `… 03-schema-transform/expr-transform` |
| **03-schema-transform/reject-routing** | Structurally-bad rows (wrong column count) are split out to `out/errors/*_errors.csv` while good rows still land in `out/database/`. | `… 03-schema-transform/reject-routing` |
| **04-output/csv-output** | Switch the sink to `format: CSV` (vs the default Parquet+snappy), same Hive partition layout. | `… 04-output/csv-output` |
| **05-acquisition/dedup-rerun** | Disk-marker dedup (`processing.duplicate_check`): run it **twice** (no `--clean`) — the 2nd run reports "No new files" and skips the already-processed file. | `… 05-acquisition/dedup-rerun` (run ×2) |
| **05-acquisition/gap-detection** | Configures `source.gap_detection` over a numbered feed (`FEED_yyyyMMdd`, day 02 missing). Both files ingest; the `SEQUENCE_GAP` **alert** is emitted in serve mode — see the runnable serve example below. | `… 05-acquisition/gap-detection` |
| **06-serve/sequence-gap** | The same gap feed run **as a service**: the poll loop detects the hole in the `FEED_{yyyyMMdd}.csv` series (day 02 missing) and emits a `SEQUENCE_GAP` event + ALERT — observable only in serve mode. | `serve-example 06-serve/sequence-gap --demo` |
| **06-serve/checksum-change** | Content-based (SHA256) dedup with change alerting (`source.duplicate { mode: checksum, on_change: alert }`). A two-cycle example (uses `phase2/`): the same `ORDERS.csv` path is re-presented with **changed** content → the engine sees the checksum differ, emits `FILE_CHANGED`, and reprocesses. | `serve-example 06-serve/checksum-change --demo` |
| **06-serve/incremental-watermark** | Incremental high-watermark discovery (`source.incremental.watermark: last_modified` + `source.duplicate.mode: metadata`). Two-cycle (uses `phase2/` + `mtimes.txt`): phase 1 sets the watermark; in phase 2 a **back-dated** file is skipped while a newer one is processed. | `serve-example 06-serve/incremental-watermark --demo` |
| **06-serve/job-on-commit** | A **job triggered by a pipeline commit** (`heartbeat_job.toon` with `on_pipeline: sales_pipeline`). When the pipeline commits, JobService runs the maintenance/heartbeat job; `/jobs` and `/jobs/{name}/runs` show the SUCCESS run. | `serve-example 06-serve/job-on-commit --demo` |

## Serve-mode examples — `serve-example.{ps1,sh}`

Several acquisition features are **event-driven and only observable when the engine runs as a
service** (the poll loop + event log + alert bridge), not via the one-shot batch runner: sequence-gap
alerts, fingerprint dedup (`source.duplicate`, in-memory ledger per process), incremental
high-watermark, and stability windows. For these, use the **serve runner**, which starts
`com.gamma.control.ControlApi` over the example's config dir, seeds a fresh `out/inbox/` from
`samples/`, waits a couple of poll cycles, and probes the Control API:

```bash
serve-example 06-serve/sequence-gap --demo      # prints the probes once and stops (self-checking)
serve-example 06-serve/sequence-gap             # stays running for exploration (Enter to stop)
#   --port N   --poll N   --wait N   --clean
```

Each serve example adds a `probes.txt` (one Control API path per line; `#` comments allowed) on top
of the usual `*_pipeline.toon` + `schema.toon` + `samples/`. The runner always probes `/pipelines`
and `/events?limit=20`, then each path in `probes.txt`. NB **pipeline ids are lower-cased** from the
config `name:`, so audit routes use e.g. `/pipelines/seq_gap_feed/files`.

**Two-cycle examples (`phase2/`):** the acquisition re-presentation family (checksum/metadata change,
content dedup, incremental watermark) is only observable across two poll cycles — process a file, then
re-present it. If an example ships a `phase2/` directory, the runner seeds it into the inbox as a
**second drop** (after the first ingest cycle) and waits again before probing. See
`06-serve/checksum-change`. An example may also ship an `mtimes.txt` (`<filename> <ISO-8601>` per
line) — the runner stamps those modification times onto the seeded inbox files after each drop, so
mtime-sensitive features (incremental high-watermark, metadata dedup) are deterministic even though
git does not preserve mtimes. See `06-serve/incremental-watermark`. **Note:** with content-based dedup (`source.duplicate.mode` =
`checksum`/`metadata`), dedup is driven by the in-process fingerprint ledger, **not** the path-marker
sentinel — the engine skips marker writes in this mode, so a `markers:` dir is simply unused. These
examples omit it for clarity.

More config shapes are in
[`../../docs/FEATURE_INVENTORY.md`](../../docs/FEATURE_INVENTORY.md) §I; jobs, authored flows, and
Stage-2 enrichment serve examples are landing in subsequent batches.

## Things worth knowing (learned the hard way)

- **A `DATE`/`TIMESTAMP` column requires `date_formats`/`timestamp_formats`** in `csv_settings` (or
  the grammar). Leaving them empty makes the generated SQL fail. Use ISO dates (`%Y-%m-%d`) for the
  simplest case.
- **The poll dir is an inbox — the engine consumes it.** Input files are moved to `backup/` on
  success (or `quarantine/` on failure). That's why the runner keeps `samples/` pristine and seeds
  a throwaway `out/inbox/` each run.
- **`has_header` skips the header — NOT `skip_header_lines`.** A standard "header + data" CSV needs
  nothing: `has_header` defaults to `true`, so the one header row is skipped and every data row is
  ingested (schemas here bind columns by position, so the header's names aren't used). `skip_header_lines`
  is for **junk preamble lines _before_ the header** and is **added on top** of the header skip — so
  `skip_header_lines: 1` on a plain header+data file skips **two** lines and silently drops the first
  data row. For a headerless feed use `has_header: false` (see `02-parsing/no-header`).
- **Type-cast failures become `NULL`** (via `TRY_CAST`); only **structural** problems (wrong column
  count) are rejected to the errors CSV. See `reject-routing`.
- **No `#` comments in `.toon` files** — the parser stops at the first `#`.
- **`--enable-native-access=ALL-UNNAMED` is mandatory** (DuckDB JNI). The runner adds it for you.
