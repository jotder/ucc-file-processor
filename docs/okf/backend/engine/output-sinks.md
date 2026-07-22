---
type: Concept
title: Output & Sinks
description: The OutputFormat strategy, partitioned vs single-file writers, and quarantine outcomes.
resource: inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [engine, output, sink, partition, quarantine]
timestamp: 2026-06-28T00:00:00Z
---

# Output & Sinks

* **`OutputFormat`** (`inspecto/src/main/java/com/gamma/etl/OutputFormat.java`) — an enum-as-strategy with
  `PARQUET` (compressible) and `CSV`. Each constant carries its own `copyToken()`/`extension()`/
  `supportsCompression()`; `resolve(token)` maps the config token (anything but `"PARQUET"` → `CSV`).
* **`PartitionWriter`** (`inspecto/src/main/java/com/gamma/etl/PartitionWriter.java`) — writes a materialized
  table to Hive-partitioned output via DuckDB `COPY … PARTITION_BY`. **Requires non-empty partition columns**
  (default `["year","month","day"]`), excludes the internal `__src_id` column, uses a two-step atomic rename,
  and parallelises rename fan-out above 16 partitions.
* **`PartitionSinkWriter`** (`inspecto/src/main/java/com/gamma/pipeline/exec/PartitionSinkWriter.java`) — the
  [pipeline-engine](../pipeline-graph/live-execution.md) sink writer: delegates to `PartitionWriter` when partitions are
  declared, else writes a **single unpartitioned file**. (`sink.view` subtypes write no bytes — they register
  a view definition instead.)

## Store-layout contract (decided 2026-07-18 — closes the UAT double-count)

One store-layout contract governs where sink bytes land and what reads sweep (root cause of the
UAT-proven +72% double-count: a flow job's `data_dir` pointed inside the `orders` store, its sink
nested at `orders/rollup/`, and the dataset's recursive glob counted both):

* **Write side** — a persistent store is a **top-level directory under the space data root**.
  `PipelineJobRunner.requireTopLevelSinks` fails a flow run closed *before any bytes are written*
  when a sink would resolve deeper (a `data_dir` pointed inside another store's tree, or a slashed
  `store` name). A `data_dir` fully **outside** the data root stays allowed (external export). Job
  configs bypass `ConfigSafetyValidator`, so this is enforced at run time.
* **Read side** — `SqlViews.storeReadRoot(dir)`: a **pipeline-shaped store** (one with a
  `database/` subtree) is read at its *mapped output* (`<store>/database/**`), so `backup/`,
  `quarantine/` (incl. Decision-Rule record quarantine) and any stray nested trees never leak into
  reads. A flat snapshot store (no `database/`) reads unchanged. Applied by `DatasetRelation`
  (`physicalRef` datasets), `SourceStoreReader` (flow seeds + `sql.template` sources — a flow can
  now seed straight from an ingest pipeline's store by name, no `data_dir` hack),
  `PipelineJobRunner.deriveViewSql`, and `ExpectationEvaluator`. An **explicit deeper ref**
  (`orders/database`, `orders/backup`) is honoured as written; `shared/…` Exchange refs are exempt.
  (`DbBrowserRoutes` already resolves pipeline stores to `dirs.database` by pipeline lookup.)

Tests: `DatasetRelationTest.physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`,
`PipelineJobRunnerTest` (`seedReadsAPipelineShapedStoresMappedOutputOnly`,
`sinkNestedInsideAnotherStoreFailsClosed`, `slashedSinkStoreNameFailsClosed`,
`externalDataDirStaysAllowed`).

## Quarantine outcomes

* `QUARANTINED_UNREADABLE` — the ingester threw (file unreadable/undecodable).
* `QUARANTINED_MISMATCH` — the ingester emitted **zero** rows (parsed, but nothing came out).
* `SinkFlushException` — a framework/schema error during a generation flush → **fail the batch** (not
  quarantine). Audit is always written regardless (see [ingestion](ingestion.md)).
