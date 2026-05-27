# Batch Processing (many-to-many) — Design Spec

**Date:** 2026-05-27
**Project:** `file-processor` (`C:\sandbox\URA\sandbox\file-processor`)
**Status:** Approved for planning

## 1. Problem & goal

The current pipeline processes **one input file per worker** (one-to-many): each file is
ingested into its own DuckDB instance, transformed, and written as partitioned output named
`<source_basename>_out.<ext>`. This is ideal for large files but produces a separate output
file (and a separate status row) for every input — wasteful when ingesting **huge numbers of
small files** (typically a few rows each), which proliferates tiny partition files.

**Goal:** add a *batch* as the unit of processing. A batch ingests **many files in one pass**
into consolidated output, up to a configurable cap (file count **or** total bytes). A single
large file is simply a batch of one. Because one output file then contains rows from many
inputs, the system must maintain a **many-to-many (input → output) audit**, commit batch
status atomically at the end of the batch, and support deleting-and-reprocessing a whole batch.

## 2. Decisions (locked)

| # | Decision |
|---|----------|
| Lineage | Count matrix per **(input file → output file)** pair (row counts), not per-record |
| Batch cap | **count OR total bytes**, whichever trips first |
| Schema scope | **one schema/table per batch**; poller groups files by resolved schema, then packs |
| Rejection | A rejected member is **quarantined**, gets an audit row with a `QUARANTINED_*` status, its rows are dropped; the batch proceeds with the survivors |
| Reprocess | **CLI `ura reprocess <batch_id>`** → delete outputs + markers, restore members from backup, re-run |
| Coexistence | **Unify** onto one batch code path; a batch of one keeps the legacy `<basename>_out.<ext>` name |
| Audit store | **Flat CSVs** (evolved status + batches + lineage), append-only, structured for a future RDBMS load |
| Mechanism | **Approach A** — per-file staging table → tagged shared `raw_input` → single transform |

## 3. Current architecture (baseline)

- **Entry point:** `com.gamma.inspector.SourceProcessor#main` → `pollInbox` walks `dirs.poll`,
  matches a glob, excludes `errors/` and `quarantine/`, and submits **each file** to a fixed
  thread pool (`processing.threads`).
- **Per file (`processFile`):** duplicate-check marker → schema selection (`SchemaSelector` or
  `singleSchema`) → per-worker temp DuckDB → `CsvIngester.ingest` into `raw_input`
  (`IngestResult{parsedRows, errorRows, junkCandidateRows}`) → zero-valid-rows quarantine →
  `DataTransformer.transform` writes partitioned output (`TransformResult{outputPaths,
  outputSizes}`) → `DuckLakeRegistrar.register` → `MarkerManager.createMarkerFile` →
  backup source → `StatusWriter.append` (one CSV row per file).
- **Output:** `DataTransformer` materializes a `transformed` table, `COPY … PARTITION_BY
  (year, month, day)` to a per-worker `.staging/<uuid>/` dir, then a two-step atomic rename to
  `<basename>_out.<ext>` in each final partition dir.
- **Status (`StatusWriter`):** run-scoped CSV, `synchronized` append, columns
  `start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error`.
- **Markers (`MarkerManager`):** zero-byte sentinel mirroring the poll-relative path under
  `dirs.markers`; stale markers pruned by `retention_days`.
- **Config (`PipelineConfig`):** loaded from a `.toon`; `dirs.status_dir` (new, run-scoped
  timestamped CSV) or `dirs.status_file` (legacy literal); `processing.threads`,
  `processing.file_pattern`, `processing.duplicate_check`, `processing.csv_settings`,
  `processing.schemas[]` (resolves `(schema, table)` by `column_count`/`file_pattern`) or
  legacy `processing.schema_file`. Run timestamp format `yyyyMMdd_HHmmss`.
- **Dependencies available:** DuckDB JDBC 1.5.2.1, univocity-parsers, JToon + Jackson, Gson
  2.11.0 (used for JSON sentinel files), OpenCSV, commons-compress.

## 4. Target architecture

### 4.1 New components (`com.gamma.etl`)

- **`BatchPlanner`** — pure planning, no I/O side effects beyond reading file sizes.
  - Input: list of matched, **not-yet-marked** files; the `SchemaSelector` (or `singleSchema`);
    caps `maxFiles`, `maxBytes`.
  - Resolves each file's `SchemaSelector.Selection` **once** and caches it.
  - Groups files by resolved `(schema, table)` identity.
  - Within each group, sorts files by poll-relative path (deterministic) and greedily packs
    into batches: start a batch, keep adding the next file while
    `count < maxFiles AND bytes + nextBytes <= maxBytes`. A file whose own size exceeds
    `maxBytes` forms a batch of one (never split a file).
  - Output: `List<Batch>`, each with an assigned `batchId`.

- **`Batch`** (record) — `String batchId`, `String schemaName`, `String table`,
  `List<Member> members`. `Member` = record `(File file, int srcId, long bytes,
  SchemaSelector.Selection selection)`. `srcId` is the 0-based index within the batch.

- **`BatchProcessor`** — orchestrates one batch (the unit submitted to the thread pool).
  Owns one temp DuckDB; runs the ingest loop, the single transform, lineage collection, and the
  atomic commit. This is the batch-era analog of `SourceProcessor.processFile`.

- **`PartitionWriter`** — extracted from `DataTransformer`. Given a materialized table name, an
  output base-name, and `cfg`: builds the `COPY … PARTITION_BY (year, month, day)` to a per-batch
  `.staging/<uuid>/` dir and performs the existing two-step atomic rename into the final
  partition dirs. Returns `List<PartitionOutput>` where
  `PartitionOutput = (String partition, String outputFile, long bytes)` and
  `partition = "year=Y/month=M/day=D"`.

- **`LineageCollector`** — given the `transformed` table (carrying `__src_id`), the connection,
  the `srcId → filename` map, and the list of `PartitionOutput`: runs
  `SELECT __src_id, year, month, day, COUNT(*) FROM transformed GROUP BY 1,2,3,4`,
  joins each `(year,month,day)` to its `PartitionOutput.outputFile`, and produces the count-matrix
  rows `LineageRow = (batchId, srcId, inputFile, outputFile, partition, rowCount)`.

- **`BatchManifest` + `ManifestStore`** — Gson-serialized JSON at
  `<status_dir>/manifests/<batch_id>.json`. Fields:
  `batchId, pipeline, schemaName, outputTable, createdAt`,
  `members: [{filename, srcId, originalRelPath, backupPath, status}]`,
  `outputs: [{partition, outputFile}]`, `markers: [path]`.
  `ManifestStore` writes during commit, reads/renames during reprocess.

- **`BatchAuditWriter`** — buffers a batch's audit rows in memory during processing and appends
  them atomically at commit. One lock per CSV file; each batch's rows are written contiguously.
  Writes to the three CSVs in §4.4.

### 4.2 Changed components

- **`CsvIngester`** — add a target-table parameter (additive overload) so each member can be
  ingested into its own temp table `raw_f<srcId>`. Existing single-arg callers unchanged.

- **`DataTransformer`** — `raw_input` now carries an extra `__src_id INTEGER` column. The
  `transformed` table carries `__src_id` through (for `LineageCollector`). The `COPY` projection
  **excludes** `__src_id`, so the output schema is identical to today. The `COPY`/rename logic
  moves into `PartitionWriter`. Output base-name is a parameter:
  `<basename>` when the batch has exactly one member, else `<batchId>`.

- **`SourceProcessor.pollInbox`** — collects candidate files, filters out
  already-marked files via `MarkerManager.isAlreadyProcessed` (duplicate check moves **up** into
  the polling/planning stage so marked files are never packed), calls `BatchPlanner`, and submits
  each `Batch` to the existing fixed thread pool. Batches run in parallel; each batch is
  single-threaded internally. `processFile` is removed; its lifecycle responsibilities move into
  `BatchProcessor`.

- **`MainApp`** — new `case "reprocess":` dispatching to `ReprocessCommand`; `printUsage()` updated.

- **`ReprocessCommand`** (new, `com.gamma.util` or `com.gamma.inspector`) — implements §4.5.

### 4.3 Batch lifecycle

```
poll → match glob → exclude errors/quarantine → drop already-marked files
     → BatchPlanner: group-by-(schema,table) + pack to max_files OR max_bytes
     → submit each Batch to thread pool (threads = processing.threads)
        └─ BatchProcessor.process(batch):
             1. open per-batch temp DuckDB
             2. for each member (sequential, srcId order):
                  a. CsvIngester.ingest(member.file, conn, member.selection.schema, cfg,
                                        targetTable = "raw_f<srcId>")  → IngestResult
                  b. per-file zero-valid-rows / field-mismatch check (same predicate as today):
                       • REJECT → QuarantineManager.quarantine(file) ;
                                  buffer batch_file row with QUARANTINED_* status (reason) ;
                                  DROP TABLE raw_f<srcId> ;  (rows NEVER enter raw_input)
                       • ACCEPT → ensure raw_input exists (created from first accepted temp
                                  table's columns + "__src_id INTEGER") ;
                                  INSERT INTO raw_input SELECT *, <srcId> FROM raw_f<srcId> ;
                                  buffer SUCCESS batch_file row (counts) ;
                                  DROP TABLE raw_f<srcId>
             3. if raw_input is empty (every member rejected):
                  batch status = EMPTY ; no outputs ; no markers ;
                  write batches.csv row + buffered batch_file rows ; done
             4. DataTransformer.materialize → `transformed` (carries __src_id)
             5. base = (survivingMembers == 1) ? legacyBasename : batchId
                PartitionWriter.write("transformed", base, cfg) → List<PartitionOutput> (staged)
             6. LineageCollector.collect(...) → List<LineageRow>
             7. COMMIT (atomic tail, in order):
                  i.   reveal outputs (two-step atomic rename, as today)
                  ii.  DuckLakeRegistrar.register(outputs, table, cfg)
                  iii. ManifestStore.write(manifest)        ← written before markers/backup
                  iv.  MarkerManager.createMarkerFile for each surviving member
                  v.   move each surviving source file to backup (record backupPath in manifest)
                  vi.  BatchAuditWriter.flush: append batches.csv + batch_file.csv + lineage.csv
             8. delete temp DuckDB
```

### 4.4 Audit model — three run-scoped CSVs in `dirs.status_dir`

Append-only; join key `batch_id`; column layout chosen for a clean future bulk load into RDBMS
tables. The existing per-file status CSV is **evolved** by appending a `batch_id` column at the
end (header-based, additive — existing positional readers are unaffected for the original columns).

1. **`<pipeline>_status_<ts>.csv`** — *batch_file table; one row per member (surviving or rejected)*
   `start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,batch_id`
   `status ∈ { SUCCESS, QUARANTINED_UNREADABLE, QUARANTINED_MISMATCH }`.
   For rejected members `output_paths`/`output_sizes_bytes` are empty.

2. **`<pipeline>_batches_<ts>.csv`** — *one row per batch*
   `batch_id,pipeline,schema_name,output_table,start_time,end_time,status,member_count,rejected_count,total_input_rows,total_output_rows,output_file_count,total_output_bytes,duration_ms,error`
   `status ∈ { SUCCESS, EMPTY, FAILED }`.

3. **`<pipeline>_lineage_<ts>.csv`** — *the many-to-many count matrix; one row per (member → output file) pair*
   `batch_id,src_id,input_file,output_file,partition,row_count`
   `partition = "year=Y/month=M/day=D"`.

### 4.5 Reprocessing — `ura reprocess <batch_id>`

`ReprocessCommand`:
1. Load `<status_dir>/manifests/<batch_id>.json`. Error out clearly if missing or already
   `.superseded`.
2. Delete every `outputs[].outputFile`.
3. Delete every marker in `markers[]`.
4. Restore each member: copy/move `members[].backupPath` → `poll/<originalRelPath>`.
5. Rename the manifest to `<batch_id>.json.superseded`.
6. Trigger a normal poll (same as a fresh run) so the restored set is re-ingested into fresh
   batch(es) with new `batch_id`(s).

The original `batches.csv` / `lineage.csv` rows remain as immutable history. Re-batching the
restored set is acceptable — the **set** is the unit of reprocessing. Reprocessing a single
member of a batch is intentionally **not** supported.

### 4.6 Error handling & atomicity

- **Per-member rejection** never touches `raw_input` (Approach A guarantee): the bad file is
  quarantined, a REJECTED row is recorded, and the batch proceeds with survivors.
- **Batch failure before the commit tail** (transform/write error): staging is cleaned, a
  `FAILED` row is appended to `batches.csv`, and **no markers / no backup** are written — the
  whole set stays in the inbox and is retried on the next poll. The staging + atomic-rename
  strategy guarantees no half-revealed output is ever visible to downstream readers.
- **Crash inside the commit tail** (after reveal, before markers): may leave orphan output files.
  The manifest is written first in the tail (step iii) so an operator can detect and clean
  orphans. **No automatic orphan GC in v1** (YAGNI).
- Audit appends are serialized per file (one lock each); each batch's rows are contiguous.

### 4.7 Config additions (`.toon`)

```
processing:
  batch:
    max_files: 500
    max_bytes: 268435456   # 256 MB
```

- New `PipelineConfig` fields: `int batchMaxFiles`, `long batchMaxBytes`.
- Defaults when the `batch` section is **absent**: `max_files = 1`, `max_bytes = Long.MAX_VALUE`
  → every batch is a single file with legacy naming, i.e. **zero behavior change** until
  consolidation is explicitly configured.
- `max_files: 1` always reproduces today's exact behavior.

## 5. Testing strategy

**Unit**
- `BatchPlanner`: count-cap boundary, byte-cap boundary, oversize-single-file batch, grouping by
  schema/table, deterministic ordering, and `(maxFiles=1)` → all batches-of-one.
- `LineageCollector`: counts sum to total output rows; correct `(input → output)` mapping across
  multiple partitions; multiple inputs into one output file.
- Output naming rule: 1 member → `<basename>_out`; N members → `<batchId>_out`.

**Integration** (small CSV fixtures + a `batch_test_pipeline.toon` with tiny inputs)
- Many small CSVs in one schema → one consolidated output file per partition + a correct
  lineage matrix; `batches.csv` totals reconcile with the sum of `lineage.csv` row counts.
- One malformed member among good ones → that file quarantined + REJECTED `status.csv` row +
  survivors produce output; `rejected_count = 1` in `batches.csv`.
- Batch-of-one → legacy `<basename>_out.<ext>` filename preserved.
- `reprocess <batch_id>` → outputs and markers deleted, members restored to inbox, manifest
  marked `.superseded`, and a re-run reproduces the same data.

## 6. Out of scope (v1)

- Per-record lineage (only the count matrix is built).
- Mixed-schema batches (a batch is always one schema/table).
- Automatic orphan-output garbage collection after a mid-commit crash.
- Loading audit CSVs into an RDBMS (the CSV layout is designed for it; the load is a future task).
- Reprocessing a single member of a batch.
