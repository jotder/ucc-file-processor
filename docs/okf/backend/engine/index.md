# Engine

The per-batch ETL pipeline: acquire → ingest → transform → write, all backed by embedded DuckDB. Its code
lives in the engine modules extracted below the [core](../modules/engine.md) in the WS-D split —
`inspecto-etl` / `inspecto-engine` / `inspecto-event` / `inspecto-acquire` (see the
[reactor map](../modules/reactor.md)).

# Concepts

* [Ingestion](ingestion.md) - the `StreamingFileIngester` SPI, union vs generation mode, and the batch coordinators.
* [DuckDB](duckdb.md) - Appender-based bulk ingest (~75×), thread auto-derivation, reserved-word quoting.
* [Output & sinks](output-sinks.md) - `OutputFormat`, partitioned vs single-file writers, quarantine outcomes.
* [Transforms & seams](transforms-seams.md) - `TransformCompiler` and the `BatchIngestStrategy` seam.
* [Parsing & grammar](parsing-grammar.md) - the three frontends / one backend model, CSV knobs, delimited grammar, plugin ingesters.
* [Stage-1 architecture](stage1-architecture.md) - the deep design of the batch ETL core (moved from `docs/architecture.md`).
* [DB / persistence layer](db-layer.md) - every store, its backend (DuckDB/Postgres), and the dialect seams (moved from `docs/DB_LAYER.md`).
* [Plugin ingesters](plugins.md) - the drop-in ingester plugin model (moved from `docs/plugins.md`).
