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
| **05-acquisition/gap-detection** | Configures `source.gap_detection` over a numbered feed (`FEED_yyyyMMdd`, day 02 missing). Both files ingest; the `SEQUENCE_GAP` **alert** is emitted in serve mode (see note below). | `… 05-acquisition/gap-detection` |

## Serve-mode acquisition features (next phase)

Several acquisition features are **event-driven and only observable when the engine runs as a
service** (the poll loop + event log + alert bridge) — not via the one-shot runner: sequence-gap
alerts, fingerprint dedup (`source.duplicate` checksum/metadata, whose ledger is in-memory per
process), incremental high-watermark, and stability windows. Their config shapes are in the examples
above and in [`../../docs/FEATURE_INVENTORY.md`](../../docs/FEATURE_INVENTORY.md) §I. A serve-mode
example group — start the service, drop files, watch `GET /events` and `GET /objects` — is the next
batch, alongside jobs, authored flows, and Stage-2 enrichment.

## Things worth knowing (learned the hard way)

- **A `DATE`/`TIMESTAMP` column requires `date_formats`/`timestamp_formats`** in `csv_settings` (or
  the grammar). Leaving them empty makes the generated SQL fail. Use ISO dates (`%Y-%m-%d`) for the
  simplest case.
- **The poll dir is an inbox — the engine consumes it.** Input files are moved to `backup/` on
  success (or `quarantine/` on failure). That's why the runner keeps `samples/` pristine and seeds
  a throwaway `out/inbox/` each run.
- **Type-cast failures become `NULL`** (via `TRY_CAST`); only **structural** problems (wrong column
  count) are rejected to the errors CSV. See `reject-routing`.
- **No `#` comments in `.toon` files** — the parser stops at the first `#`.
- **`--enable-native-access=ALL-UNNAMED` is mandatory** (DuckDB JNI). The runner adds it for you.
