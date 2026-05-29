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

## Concurrency model — current mitigation

Ingest is single-threaded *within* a file (sequential read; cannot be
parallelized for one input). The existing mitigation is batch-level
parallelism: `SourceProcessor` runs `cfg.threads` batches concurrently, so N
files ingest simultaneously. Aggregate throughput scales with `threads`, but
per-file latency is fixed by single-threaded ingest.

Note the inner/outer parallelism interaction: each batch opens its own DuckDB
connection, which uses its own thread pool (`= CPU cores` by default) for the
transform/write SQL. With `threads=4` on a 16-core box you can momentarily have
4 × 16 = 64 DuckDB workers. Since transform/write are a small fraction of time,
this rarely matters in practice — but it's worth a `PRAGMA threads` cap if you
push `cfg.threads` high.

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
- **PARQUET vs CSV output** — write cost is dominated by partition file reveal
  (the two-step atomic rename per partition), not the format. 30 partitions
  reveal in <2 s.
- **Partition fan-out** is cheap: 30 distinct dates → 30 output files added no
  measurable cost over a single partition.
