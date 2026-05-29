# Performance Assessment & Bottleneck Analysis

Measured on JDK 26 / DuckDB 1.5.2 via `PipelineBenchmark` (a stage-isolating
benchmark in the test tree). Reproduce with:

```
mvn -Dtest=PipelineBenchmark -DfailIfNoTests=false -Dbench.run=true \
    -Dbench.rows=2000000 -Dbench.cols=12 -Dbench.format=PARQUET test
```

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

## Recommended next steps (ranked by leverage)

1. **Native DuckDB CSV read for the simple-schema path.** When a schema needs
   no junk/tail/echo handling (`skip_junk_lines = 0`, `skip_tail_lines = 0`,
   `skip_tail_columns = 0`), bypass the Java parse+appender entirely and let
   DuckDB ingest the file with its vectorized, parallel reader:
   `CREATE TABLE raw AS SELECT * FROM read_csv('file', …)`. DuckDB's CSV reader
   is multi-threaded and avoids the JNI-per-cell cost — expected 5–10× on wide
   files. Keep the current Java path as the fallback for schemas that need the
   custom line handling. **Highest leverage; biggest change.**

2. **univocity streaming (`parseNext()`) for the Java path.** Rework the
   skip/junk/tail logic to sit on top of univocity's row iterator instead of
   `readLine()` + `parseLine()`. Moderate gain on the Java path, lower risk than
   (1) but touches the trickiest correctness logic in the codebase — add
   characterization tests for the junk/tail/echo edge cases first.

3. **Raise `batch.max_files` and `threads` for wide-schema pipelines.** Pure
   config, no code. Since per-file ingest latency is fixed, the only way to move
   aggregate throughput today is more files in flight. The `ConfigValidator`
   already warns when `threads > 1` but `batch.max_files = 1` (no intra-batch
   packing).

## What's already fast — leave it alone

- **transform / write / lineage** run at 1–1.4M rows/s on DuckDB's vectorized
  engine. No optimization needed; don't add complexity here.
- **PARQUET vs CSV output** — write cost is dominated by partition file reveal
  (the two-step atomic rename per partition), not the format. 30 partitions
  reveal in <2 s.
- **Partition fan-out** is cheap: 30 distinct dates → 30 output files added no
  measurable cost over a single partition.
