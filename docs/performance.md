# Performance Assessment & Bottleneck Analysis

Measured on JDK 26 / DuckDB 1.5.2 via `PipelineBenchmark` (a stage-isolating
benchmark in the test tree). Reproduce with:

```
mvn -Dtest=PipelineBenchmark -DfailIfNoTests=false -Dbench.run=true \
    -Dbench.rows=2000000 -Dbench.cols=12 -Dbench.engine=java -Dbench.format=PARQUET test
# swap -Dbench.engine=duckdb for the native reader
```

> **RESOLVED in v1.4.0** — the ingest bottleneck identified below is fixed by
> the native `DuckDbCsvIngester` (see "Resolution" at the bottom). The analysis
> is kept for the record and because the Java path remains the fallback for
> messy-file configs.

> **Re-measured v3.9.0 (engine modularity pass).** The behavior-injection refactor
> ([design-notes D7](outdated-doc/design-notes.md#d7--engine-modularity-pass-behavior-injection-seams--done-v390))
> touched the **transform** stage (`DataTransformer` now delegates per-column SQL to
> `TransformCompiler`) and the **write** stage (`PartitionWriter` resolves format via the
> `OutputFormat` enum). Re-running the 2M-row × 12-col × PARQUET scenario on JDK 26 shows
> **no regression** — transform `1.4–1.8 s` (≈1.1–1.4M rows/s) and write `1.9 s`
> (≈1.0M rows/s), matching the pre-refactor figures in the table below. Expected: both
> seams run **once per column / once per write**, not per row, so the vectorized per-row
> cost is unchanged. Ingest re-measured at `3.5 s` (duckdb) / `15.6 s` (java), also in line.

## Headline: ingest is the bottleneck

For a 2,000,000-row × 12-column file → PARQUET, 30 date partitions:

| Stage | Time | Throughput | Share |
|---|---|---|---|
| **ingest** (Java CSV parse + DuckDB appender) | **15.5 s** | 129K rows/s | **~78%** |
| tag (union / `__src_id`) | 1.4 s | — | 7% |
| transform (`CREATE TABLE AS SELECT`) | 1.4 s | 1.42M rows/s | 7% |
| write (`COPY … PARTITION_BY` + reveal) | 1.8 s | 1.09M rows/s | 9% |
| lineage (`GROUP BY`) | 0.02 s | — | <1% |

The DuckDB-native stages (transform, write) run at **1.0–1.4M rows/s**. The
Java-side ingest runs at **129K rows/s** — an order of magnitude slower — and
dominates wall-clock time. Everything downstream of ingest is effectively free
by comparison.

## Ingest cost scales with total cells

Ingest time is roughly linear in (rows × columns), at **~0.6–1.0 µs per cell**:

| Columns | Ingest (2M rows) | rows/s | µs/cell |
|---|---|---|---|
| 3 | 5.2 s | 384K | 0.87 |
| 12 | 15.5 s | 129K | 0.65 |
| 40 | 79.3 s | 25K | 0.99 |

**Implication for wide schemas.** The real workloads are wide:
`voucher_unknown_76.toon` is 76 columns, and `SchemaSelector`'s own comments
reference a 537-column CDR variant. Extrapolating at ~0.8 µs/cell:

| Schema | Cols | 2M-row ingest (projected) |
|---|---|---|
| adjustment / generic | ~12 | ~16 s |
| voucher | 76 | ~120 s (2 min) |
| 537-col CDR | 537 | ~860 s (14 min) |

Per-file ingest latency on the widest schemas is the practical ceiling on this
pipeline's throughput.

## Why ingest is slow — root causes

Three costs in `CsvIngester`'s per-row loop, in rough order of impact:

1. **Per-cell JNI into the DuckDB appender.** `appender.append(value)` is one
   JNI crossing per cell — 40 cols × 2M rows = 80M native calls. This is the
   single largest contributor and the reason cost scales with column count.
   The appender *is* DuckDB's recommended bulk-insert API, so this is mostly
   intrinsic; the lever is reducing the number of cells touched, not the API.

2. **`parser.parseLine(line)` per line.** univocity's one-line-at-a-time API
   re-initializes parser state per call. Its streaming iterator
   (`beginParsing(reader)` + `parseNext()`) reuses internal buffers and is
   materially faster for bulk parsing, but the current skip/junk/tail/echo
   line-handling logic is built around `BufferedReader.readLine()`, so adopting
   it is a non-trivial refactor (see "Recommended next steps").

3. **Selector string-parse in the loop (FIXED in v1.3.3).** The previous code
   ran `Integer.parseInt(String.valueOf(field.get("selector")))` for every cell
   of every row — 24M string parses on a 2M×12 file. Hoisting the selectors to
   an `int[]` once before the loop gave a measured **~13% ingest speedup**
   (17.84 s → 15.49 s at 12 cols). Shipped.

## Concurrency model (v1.5.0)

Ingest is single-threaded *within* a file (sequential read; cannot be
parallelized for one input). Aggregate throughput comes from batch-level
parallelism: `SourceProcessor` submits every batch to a virtual-thread executor
bounded by `Semaphore(cfg.processing().threads())`, so up to `processing.threads` batches ingest
simultaneously while blocked batches park cheaply instead of pinning platform
threads. Per-file latency is still fixed by single-threaded ingest (now ~4–5×
lower thanks to the native engine below).

**Two controllable axes** (set both to keep the CPU honest):

| Knob | Controls | Default |
|---|---|---|
| `processing.threads` | how many batches run concurrently (semaphore permits) | 4 |
| `processing.duckdb_threads` | `PRAGMA threads=N` per batch's DuckDB connection | 0 = **auto** (`cores ÷ threads`) |

The inner/outer interaction: each concurrent batch opens its own DuckDB
connection, and DuckDB defaults to one thread per core. So with `threads=4` and
an *unmanaged* per-core default on a 16-core box you would momentarily have
4 × 16 = 64 DuckDB workers fighting over 16 CPUs — the classic ~100%-sys / ~2%-user
oversubscription stall (felt acutely on big-core boxes: 16 batches × 56 cores ≈ 896 workers).

**Since v3.12.0 this is handled by default.** `duckdb_threads=0` (the default) now
**auto-derives** `max(1, cores ÷ threads)` so the concurrent batches divide the cores
(e.g. `threads=4` on 16 cores → 4 each; `threads=16` on 56 cores → 3 each). A single
batch (`threads ≤ 1`) still gets all cores. Overrides:

- **positive N** — use exactly `N` threads per batch (manual tuning; honored verbatim).
- **`-1`** — opt out: leave DuckDB's per-core default even under concurrency (when you
  deliberately want one batch to use the whole machine).

`ConfigValidator` warns when an *explicit* `threads × duckdb_threads` exceeds the core
count; the `0` default is self-managing and never trips it.

### Inbox discovery — parallel duplicate check (v3.12.0)

Before any batch runs, `SourceProcessor` scans `dirs.poll` for matching files and drops the
ones already processed in a prior run. The directory walk is one tree traversal, but the
per-file duplicate check is a **filesystem stat** (`Files.exists` on the marker mirror under
`dirs.markers`). On an inbox of tens of thousands of files that stat *latency* — not CPU —
dominates the scan, and it serialises ahead of the first batch.

The scan is now split: one walk collects the matching regular files, then the marker check
runs in parallel (`parallelStream`, bounded to ≈ cores by the common pool — each task is a
single short stat, so briefly parking a carrier is fine and there's no oversubscription).
`BatchPlanner` re-sorts candidates by path, so parallelising the filter changes nothing about
batch composition or output. Parallelism is gated on `processing.threads > 1`; a deliberately
single-threaded run keeps its sequential scan, and it's skipped entirely when
`duplicate_check.enabled = false` (no stat to do).

## Resolution — native DuckDB CSV engine (v1.4.0)

`DuckDbCsvIngester` replaces the Java parse+appender loop with DuckDB's native
`read_csv` for well-formed files. Selectable via `csv_settings.engine`:

| `engine` | Behaviour |
|---|---|
| `auto` (default) | Native reader when `skip_junk_lines`/`skip_tail_lines`/`skip_tail_columns` are all `0`; Java parser otherwise. No semantic change for any messy-configured source. |
| `duckdb` | Always native (operator accepts the too-many-columns rejection difference). |
| `java` | Always the original Java parser. |

**Measured speedup** (2M rows → PARQUET, JDK 26 / DuckDB 1.5.2):

| Cols | Java ingest | DuckDB ingest | Speedup |
|---|---|---|---|
| 12 | 15.7 s (127K r/s) | 3.8 s (523K r/s) | **4.1×** |
| 40 | 80.2 s (25K r/s) | 16.0 s (125K r/s) | **5.0×** |

The speedup grows with column count — exactly where the Java path was worst.
Projected onto the wide schemas: the 537-col CDR file drops from ~14 min to
~3 min of ingest per 2M-row file.

**Correctness.** `DuckDbCsvIngesterTest.parityWithJavaEngineOnCleanFile` asserts
byte-identical table contents between the two engines on clean input. Short
rows, footers, and blank lines are rejected identically via
`ignore_errors=true, null_padding=false`; rejected rows are captured from
DuckDB's `reject_errors` table into the same `errors/<base>_errors.csv` the Java
path writes. The existing `BatchProcessorTest` / `SourceProcessorPollTest`
configs are clean, so `auto` already runs them through the native engine and
they pass unchanged.

**The one semantic difference** (documented, handled by routing): DuckDB rejects
rows with *more* columns than declared (`TOO MANY COLUMNS`); the Java path keeps
them and ignores the extras. That's what `skip_tail_columns` is for, so `auto`
routes any config using it to the Java path. `ConfigValidator` warns if `duckdb`
is forced alongside `skip_tail_columns > 0`.

## Remaining options (not pursued — native engine made them unnecessary)

- **univocity streaming (`parseNext()`)** would speed up the Java *fallback*
  path, but the fallback now only runs for genuinely messy files where
  throughput matters less. Revisit only if a high-volume source genuinely needs
  `skip_tail_columns`.
- **Raise `batch.max_files` / `threads`** still helps aggregate throughput
  regardless of engine (more files in flight). `ConfigValidator` warns when
  `threads > 1` but `batch.max_files = 1`.

## What's already fast — leave it alone

- **transform / write / lineage** run at 1–1.4M rows/s on DuckDB's vectorized
  engine. No optimization needed; don't add complexity here.
- **PARQUET vs CSV output** — write cost is dominated by the DuckDB `COPY`, not the
  format. The post-COPY reveal (a two-step atomic rename per partition file) is cheap
  at low fan-out; at *high* partition cardinality the serial rename loop starts to show,
  so since **v3.12.0** the reveal runs in parallel once there are ≥ 16 staged files
  (`PartitionWriter.REVEAL_PARALLEL_THRESHOLD`) — each file targets a distinct partition
  directory so the renames don't contend, and the temp name embeds the staged file name
  so parallel reveals into the same directory can't collide. Below the threshold it stays
  a sequential loop (byte-identical to before, no fork/join overhead).
- **Partition fan-out** is cheap: 30 distinct dates → 30 output files added no
  measurable cost over a single partition. Thousands of partitions (high-cardinality
  partition keys) is where the parallel reveal above earns its keep.

## Very large single files (3.10.0)

For a multi-hundred-GB / TB single file the bottleneck is **scratch**, not CPU — DuckDB's
native `read_csv` already parallelises across the whole file. Two changes target scratch:

- **Scratch off `/tmp`.** The per-batch temp DB + DuckDB spill default to `dirs.temp` (data
  volume), tunable via `processing.duckdb.{temp_directory,memory_limit,max_temp_directory_size}`.
  Previously both went to `java.io.tmpdir` (system `/tmp`), which fails fast on big inputs.
- **One materialization, not 2–3×.** Native (`read_csv`) batches stream `read_csv → transform →
  COPY` through lazy views, so only the `transformed` table is materialised (peak scratch ~1×
  the decoded data) instead of `raw_f<id>` + `raw_input` + `transformed` (~2–3×). A *single-member*
  batch streams its one file through a `raw_input` view; a *multi-member* batch (v3.12.0) builds one
  lazy `read_csv` view per member and `UNION ALL`s them into a single `raw_input` view that one
  transform pulls through — so a consolidated many-file batch is also materialised exactly once
  rather than copying every member into `raw_f<id>` then into `raw_input` first. Per-member
  quarantine is preserved (each member is probed with a `COUNT(*)` over its view; an unreadable
  file throws and is quarantined individually). Fewer intermediate writes also means less I/O —
  faster, not just smaller. The `transformed` table is deliberately kept (the DuckDB-AVX2 COPY
  workaround depends on it). *(The Java parse engine still stages per-member tables — line-by-line
  parsing can't stream through a view.)*
- **`processing.chunking`** bounds peak scratch to ~one chunk regardless of file size; the split
  is a single sequential read (cheap vs the materialization it avoids). Chunks process
  sequentially today — each is already internally multi-threaded, so cores stay busy.

### Plugin ingestion: one SPI, two size-routed modes

Custom (binary / proprietary / ASN.1) files go through the single `StreamingFileIngester` SPI; the
framework picks an execution mode per batch by file size (`processing.streaming.large_file_bytes`,
default 256 MB):

- **Union mode** (many small files) — members accumulate into per-member raw tables, which are then
  consolidated through a lazy `UNION ALL` **view** (since v3.12.0) that one transform/write pulls
  through per batch. Output is **consolidated** (one set of partition files), and the fixed per-batch
  cost is amortised across all packed files (raise `processing.batch.max_files`). The view replaced an
  intermediate physical `raw_<KEY>` table that every member's rows were copied into before transform,
  so the batch now materialises once (`transformed_<KEY>`) instead of twice — peak scratch drops by
  ~1× the segment's data and a full copy pass is gone. Mirrors the native CSV streaming-UNION path.
- **Generation mode** (a huge single file) — records flush in bounded generations (default 5M rows via
  `processing.streaming.flush_records`); peak heap and scratch stay bounded regardless of total size,
  without any auto-chunker (which can't split an opaque format).

**Insert path: DuckDB Appender, not JDBC.** Both `DuckDbRecordSink` and the reference
`TypedRecordIngester` bulk-load via the DuckDB **Appender** API. Measured on 1M `ID,EVT_DATE` rows
(`PluginIngestBenchmark`): the old JDBC `PreparedStatement.executeBatch` path ran ~6.9K rows/s; the
Appender path runs **~520–530K rows/s — ~75× faster**, at parity with the native CSV `duckdb` engine.
This is what makes union-mode many-small-files throughput acceptable and shrinks the single-threaded
ingest trough that otherwise starves many-core hosts (see [troubleshooting → CPU](troubleshooting.md)).

Mode comparison (`PluginIngestBenchmark`, 500K rows, PARQUET, 30 day-partitions): union ≈ 233K rows/s /
30 consolidated files / 23 MB peak; generation (4 gens) ≈ 202K rows/s / 120 files / 15 MB peak — i.e.
generation trades output fragmentation and a little throughput for bounded memory. See
[plugins.md → execution modes](plugins.md#execution-modes--the-framework-picks-by-file-size) and
[design-notes D9/D10](outdated-doc/design-notes.md).
