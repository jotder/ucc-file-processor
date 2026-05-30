# UCC File Processor
A small, high-throughput, configuration-driven ETL engine with an **M..N multiplexer** architecture: it ingests **M** CSV or binary input files, applies light per-record transformations via DuckDB, and demultiplexes them into **N** Hive-partitioned Parquet or CSV output files (not one-to-one). Onboard a new CSV source with a single config file; plug in a custom Java parser for proprietary or binary formats that emit multiple event types. It deliberately does **not** do heavy joins, lookups, or cross-record aggregation.

Includes a set of **Pre-ETL utilities** for sourcing, staging, and arranging raw deliveries before the pipeline picks them up.

---

## Documentation

This README is the overview and quick start. Detailed topics live under [`../docs/`](../docs/):

| Doc | Covers |
|---|---|
| [Architecture & Design](../docs/architecture.md) | Design philosophy & scope (the M..N multiplexer, deliberate non-goals), system architecture, directory layout, the two-step process |
| [Configuration Reference](../docs/configuration.md) | The three config files (generation → schema → pipeline), configuration by source format, multi-schema dispatch, type mapping |
| [Plugin Ingester](../docs/plugins.md) | The `FileIngester` interface, segment schemas, the `TypedRecordIngester` reference plugin, and the plugin-author workflow |
| [Operations](../docs/operations.md) | Pre-ETL utility suite, batch processing & concurrency, multi-source orchestration, output structure, audit logs, deployment, onboarding a new source |
| [Integrations](../docs/integrations.md) | DuckLake registration and the pg_duckdb warehouse query layer |
| [Troubleshooting](../docs/troubleshooting.md) | Common failures and fixes |

Engineering notes (not user-facing): [design decisions & deferred work](../docs/design-notes.md) · [performance & bottleneck analysis](../docs/performance.md) · [test coverage](../docs/test-coverage.md).

**v3.x line** (current): the 3.x roadmap introduces an optional embedded AI assist agent and a
machine-readable "Smart Config" model. See [v3 architecture & redesign](../docs/v3-architecture.md),
the [assist-agent MVP](../docs/v3-agent-mvp.md), and the [v3 plan](../docs/v3-plan.md).

---

## Repository layout (v3.x)

A two-module Maven reactor (parent POM at the repo root):

| Module | Role |
|---|---|
| `file-processor/` | The lean, deployable ETL engine + control plane (this README). The fat-JAR; **stays zero-new-dependency**. |
| `file-processor-agent/` | **Optional** embedded assist agent (v3.0 SPI scaffold). All AI/LLM dependencies live here only, so the core JAR stays lean. Loaded in-process by `SourceService` via `ServiceLoader` when present. |

`cd file-processor && mvn clean package` builds just the core (the parent is resolved by relative
path); `mvn clean package` at the repo root builds the whole reactor.

---

## Design philosophy in one paragraph

The engine is an **M..N multiplexer**: a batch of **M** input files is demultiplexed and routed into **N** partitioned output files — decoupled from the input file count. Records are routed by **partition key** (e.g. `year/month/day`) and, on the plugin path, by **segment type** (one file → many typed streams). Transformations are **stateless and per-record** — type coercion, column selection, partition-key derivation, light date composition. It deliberately **does not** join against external data, aggregate across records, or hold state — those constraints are what keep every batch an embarrassingly parallel, crash-isolated unit. Need heavy joins? Do them downstream over the Parquet output. Full rationale: [Architecture & Design](../docs/architecture.md#design-philosophy--scope).

---

## Features

| Feature | Detail |
|---|---|
| **Generic** | Onboard any CSV source with one hand-authored config file |
| **Three-tier config** | Generation → Schema → Pipeline; each layer has a single responsibility |
| **Pre-ETL utilities** | 6 independent commands to search, stage, extract, and archive raw deliveries |
| **Vectorized CSV ingest** | `csv_settings.engine: auto` uses DuckDB's native `read_csv` for clean files (4–5× faster); falls back to the Java parser for messy SQL\*Plus dumps |
| **Adaptive junk detection** | Skips SQL\*Plus preamble lines (fixed + variable, e.g. ORA-28002 password expiry) |
| **Multi-format dates** | `COALESCE(TRY_STRPTIME(...))` chains handle multiple Oracle date formats per column |
| **Typed output** | DATE, TIMESTAMP, DOUBLE, VARCHAR — all cast safely; bad values land as NULL, not crashes |
| **Hive partitioning** | `year=YYYY/month=MM/day=DD` directory structure, partition key from config |
| **CSV or Parquet output** | Switched per pipeline via `output.format`; Snappy compression for Parquet |
| **Controllable parallelism** | Virtual-thread executors with semaphore caps at both the batch and multi-source levels; per-connection DuckDB thread cap |
| **Multi-source orchestration** | `MultiSourceProcessor` runs many sources concurrently in one JVM, failure-isolated |
| **Idempotency** | Marker files (`.processed`) prevent re-ingestion; stale markers pruned by `retention_days` |
| **Multi-schema dispatch** | `schemas[]` routes files to schemas by filename pattern or column-count probe |
| **Plugin ingester** | `processing.ingester:` loads a custom `FileIngester`; one input file can emit multiple event-type segments into separate partitioned tables |
| **Full audit log** | Per-file status CSV, per-batch summary, and an input→output lineage matrix |
| **Quarantine** | Wrong-schema and unreadable files are automatically isolated — never retried |
| **DuckLake registration** | Optional: registers written Parquet files into a DuckLake catalog backed by PostgreSQL |
| **Fat JAR deployment** | Single `mvn clean package` produces a fully self-contained deployable JAR |

---

## Quick Start

### Build

```powershell
cd file-processor
mvn clean package
# Produces: target/file-processor-<version>.jar  (~90 MB, all deps bundled)
```

### Generate schema for a new source

```powershell
# From the file-processor/ directory (Windows)
ura.bat create-schema <data_source> path\to\sample.csv config\<data_source>\adj_gen.toon

# Linux / Mac
./ura.sh create-schema <data_source> path/to/sample.csv config/<data_source>/adj_gen.toon
```

See [Configuration Reference](../docs/configuration.md) for what the generated files contain and how to tune them.

### Run one source

From the **repository root**, the bundled sample scripts run a pipeline end-to-end:

```powershell
run-adjustment.bat       # Windows — runs config/adjustment/adjustment_pipeline.toon
run-voucher.bat          # Windows — runs config/voucher/voucher_unknown_pipeline.toon
bash run-adjustment.sh   # Linux / Mac
bash run-voucher.sh
```

Or run any pipeline config directly (the scripts just wrap this):

```powershell
java -jar file-processor/target/file-processor-<version>.jar config/<source>/<source>_pipeline.toon
```

> The deploy bundle produced by `package.ps1` ships a generic `run.sh <adapter>` / `run.bat <adapter>`
> that resolves `config/<adapter>/*_pipeline.toon` automatically.

### Run many sources concurrently

```bash
java -cp target/file-processor-<version>.jar com.gamma.inspector.MultiSourceProcessor \
     -Dsources.max=4 config/
```

`MultiSourceProcessor` runs every `*_pipeline.toon` it finds (files or directories), bounded by `-Dsources.max`, with each source failure-isolated. See [Operations → Multiple sources in one process](../docs/operations.md#multiple-sources-in-one-process).

### Drop files and run

Place `.csv` or `.csv.gz` files under `inbox/<adapter>/` (in date sub-folders) and run the pipeline. Already-processed files are skipped automatically via `.processed` markers in `markers/<adapter>/`; markers older than `retention_days` (default 90) are pruned at each poll start.

---

## Requirements

- **Java 24+** (built and tested on JDK 24; CI pins 24, local dev on 26)
- No other runtime dependencies — the fat JAR bundles DuckDB, univocity, JToon, and the rest.
