# Engine

The per-batch ETL pipeline inside [`inspecto/`](../modules/engine.md): acquire → ingest → transform → write,
all backed by embedded DuckDB.

# Concepts

* [Ingestion](ingestion.md) - the `StreamingFileIngester` SPI, union vs generation mode, and the batch coordinators.
* [DuckDB](duckdb.md) - Appender-based bulk ingest (~75×), thread auto-derivation, reserved-word quoting.
* [Output & sinks](output-sinks.md) - `OutputFormat`, partitioned vs single-file writers, quarantine outcomes.
* [Transforms & seams](transforms-seams.md) - `TransformCompiler` and the `BatchIngestStrategy` seam.
* [Parsing & grammar](parsing-grammar.md) - the three frontends / one backend model, CSV knobs, delimited grammar, plugin ingesters.
