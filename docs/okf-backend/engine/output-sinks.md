---
type: Concept
title: Output & Sinks
description: The OutputFormat strategy, partitioned vs single-file writers, and quarantine outcomes.
resource: inspecto/src/main/java/com/gamma/etl/PartitionWriter.java
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
* **`PartitionSinkWriter`** (`inspecto/src/main/java/com/gamma/flow/exec/PartitionSinkWriter.java`) — the
  [flow-engine](../flow-graph/live-execution.md) sink writer: delegates to `PartitionWriter` when partitions are
  declared, else writes a **single unpartitioned file**. (`sink.view` subtypes write no bytes — they register
  a view definition instead.)

## Quarantine outcomes

* `QUARANTINED_UNREADABLE` — the ingester threw (file unreadable/undecodable).
* `QUARANTINED_MISMATCH` — the ingester emitted **zero** rows (parsed, but nothing came out).
* `SinkFlushException` — a framework/schema error during a generation flush → **fail the batch** (not
  quarantine). Audit is always written regardless (see [ingestion](ingestion.md)).
