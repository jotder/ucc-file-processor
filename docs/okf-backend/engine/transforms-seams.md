---
type: Concept
title: Transforms & Modularity Seams
description: TransformCompiler (transformType → ColumnRule) and the BatchIngestStrategy seam.
resource: inspecto/src/main/java/com/gamma/etl/TransformCompiler.java
tags: [engine, transform, seam, strategy]
timestamp: 2026-06-28T00:00:00Z
---

# Transforms & Modularity Seams

These behavior-preserving seams keep the engine modular (SQL / `.toon` / on-disk output unchanged).

* **`TransformCompiler`** (`inspecto/src/main/java/com/gamma/etl/TransformCompiler.java`) — a pure
  SQL-expression compiler mapping a `transformType` string to a `ColumnRule` via a static registry
  (`DATA_RULES`): `DIRECT` (default), `EXPR`, `CONCAT_DT`, `FILENAME_DATE`. An unrecognised non-blank type
  throws immediately (typo-safe); adding a type is a one-line registry edit. Note the deliberate asymmetry —
  data columns wrap DATE/TIMESTAMP sources in `CAST(... AS VARCHAR)` before the `TRY_STRPTIME` chain;
  partition columns route through `SqlBuilder.buildCastExpr`.
* **`BatchIngestStrategy`** (`inspecto/src/main/java/com/gamma/inspector/BatchIngestStrategy.java`) — a
  package-private interface, one method `IngestOutcome ingest(Batch, PipelineConfig)`, with two
  implementations: `CsvBatchStrategy` (default) and `StreamingPluginBatchStrategy` (the
  [plugin](ingestion.md) path). Each owns its DuckDB connection lifecycle; the shared commit+audit tail in
  `BatchProcessor` is path-agnostic. The interface also carries static helpers (`dropTable`, `dropView`,
  `partitionColumns`).

Related: `OutputFormat` is the third such enum-as-strategy seam (see [output & sinks](output-sinks.md)).
