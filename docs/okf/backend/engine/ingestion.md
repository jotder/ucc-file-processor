---
type: Concept
title: Ingestion (StreamingFileIngester + batch coordination)
description: The single emit-based ingestion SPI, its union/generation modes, and the batch coordinators.
resource: inspecto/src/main/java/com/gamma/etl/StreamingFileIngester.java
tags: [engine, ingestion, spi, streaming, batch]
timestamp: 2026-06-28T00:00:00Z
---

# Ingestion

## The SPI

`StreamingFileIngester` (`inspecto/src/main/java/com/gamma/etl/StreamingFileIngester.java`) is the **only**
plugin ingestion SPI (the old whole-file `FileIngester` was removed in v3.11.0). Implementations decode a
file and push records one at a time via `RecordSink.emit()`; the framework owns DuckDB table creation,
transform, partitioned write, and lineage.

## Two execution modes

`StreamingPluginBatchStrategy` (`inspecto/src/main/java/com/gamma/inspector/StreamingPluginBatchStrategy.java`)
picks a mode per batch by inspecting member file sizes, with no extra I/O:

* **Union mode** (`UnionModeIngester`) — all members are below `processing.streaming.large_file_bytes`. Each
  member's records accumulate into a per-member raw table (`raw_<KEY>_f<srcId>`), then all are `UNION ALL`-ed
  and run through **one** transform/write/lineage pass — amortising fixed per-batch cost.
* **Generation mode** (`GenerationModeIngester`) — the largest member is ≥ `large_file_bytes`. Each member
  streams in bounded "generations": once a segment hits `flush_records` rows it is transformed, written,
  lineage-counted, then dropped — so peak heap/scratch stays ≈ one generation regardless of file size. Each
  generation emits its own `<stem>_gNNNNN_out.*` files (valid Hive layout).

Selectors (parsed in `PipelineConfigParser`): `processing.streaming.large_file_bytes` (default **256 MB**),
`processing.streaming.flush_records` (default **5,000,000**).

## Batch coordination

* `SourceProcessor` (`inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java`) — the per-source ETL
  entry point and one poll cycle: scan inbox → group into `Batch`es (bounded by `processing.batch.max_files`/
  `max_bytes`) → submit to a virtual-thread executor bounded by `Semaphore(processing.threads)`. Also the
  single-pipeline CLI `main`. Drives all the [acquisition](../acquisition/framework.md) phases.
* `BatchProcessor` (`inspecto/src/main/java/com/gamma/inspector/BatchProcessor.java`) — a thin, stateless
  coordinator: pick a [`BatchIngestStrategy`](transforms-seams.md) (CSV or plugin), run `ingest()` → an
  `IngestOutcome`, then the path-agnostic tail `commit()` (DuckLake register → manifest → backup originals →
  markers → ledger, in that crash-safe order) and `writeAudit()`. Never throws for a batch failure — audit is
  always written (see [quarantine](output-sinks.md)).
* `MultiSourceProcessor` (`inspecto/src/main/java/com/gamma/inspector/MultiSourceProcessor.java`) — the outer
  orchestrator running many `.toon` sources concurrently in one JVM, bounded by `Semaphore(sources.max)`.
  Total worker pressure = `sources.max × processing.threads × duckdb_threads`.
