# UCC File Processor

A small, high-throughput, **configuration-driven ETL platform** built on an embedded DuckDB
engine. Its core is an **M..N multiplexer**: it ingests **M** CSV or binary input files,
applies light per-record transformations, and demultiplexes them into **N** Hive-partitioned
Parquet or CSV output files (not one-to-one). On top of that engine sit a **Stage-2 enrichment
engine** (joins/aggregations over the partitioned output), a **control plane** (HTTP API,
scheduler, metrics, audit), a machine-readable **Smart Config** model, a queryable **Metadata
Graph**, and an **optional embedded AI assist agent**.

Onboard a new CSV source with a single config file; plug in a custom Java parser for proprietary
or binary formats that emit multiple event types. Stage-1 ingest deliberately does **not** do
heavy joins, lookups, or cross-record aggregation — those run in Stage-2, downstream over the
Parquet output.

- **Current line:** v3.x · repo at `3.10.0-SNAPSHOT` · latest release **v3.9.0**
- **Runtime:** Java 24+, zero external runtime dependencies (everything bundled in the fat-JAR)

---

## Table of contents

- [What it does (full functionality)](#what-it-does-full-functionality)
- [Repository layout](#repository-layout)
- [Design philosophy in one paragraph](#design-philosophy-in-one-paragraph)
- [Feature matrix](#feature-matrix)
- [**User guide**](#user-guide)
  - [1. Install & build](#1-install--build)
  - [2. Onboard a new source](#2-onboard-a-new-source)
  - [3. Run a pipeline](#3-run-a-pipeline)
  - [4. Drop files & run (the steady state)](#4-drop-files--run-the-steady-state)
  - [5. Run many sources at once](#5-run-many-sources-at-once)
  - [6. Pre-ETL utilities](#6-pre-etl-utilities)
  - [7. Stage-2 enrichment (joins & aggregation)](#7-stage-2-enrichment-joins--aggregation)
  - [8. Schedule jobs](#8-schedule-jobs)
  - [9. Operate via the Control API](#9-operate-via-the-control-api)
  - [10. Explore the Metadata Catalog](#10-explore-the-metadata-catalog)
  - [11. The optional AI assist agent](#11-the-optional-ai-assist-agent)
  - [12. Output, audit & troubleshooting](#12-output-audit--troubleshooting)
- [Requirements](#requirements)
- [Documentation index](#documentation-index)

---

## What it does (full functionality)

The platform is organized into two **data stages** under one **control plane**, with three
**optional layers** (Smart Config, Metadata Graph, Assist Agent) that ship in the same build.

| Layer | What it gives you |
|---|---|
| **Stage-1 ingest** (`com.gamma.etl`, `com.gamma.inspector`) | Poll an inbox → plan batches → ingest CSV (native DuckDB or Java parser) or a custom binary plugin → type-coerce & derive partition keys → write Hive-partitioned Parquet/CSV with atomic rename → markers/quarantine → fsync'd commit log + audit CSVs → emit a `BatchEvent`. |
| **Stage-2 enrichment** (`com.gamma.enrich`) | Register reference + Stage-1 partitions as views on an ephemeral DuckDB, run a `transform` SQL (joins / aggregations / derivations), write an idempotent partitioned result. Event- and schedule-driven, per-job locked, self-chaining via the event bus. |
| **Control plane** (`com.gamma.service` / `.control` / `.report` / `.job` / `.metrics`) | `SourceService` hub, `BatchEventBus` pub/sub, cron + fixed-delay `Scheduler`, `ControlApi` (JDK `HttpServer`, ~30 routes, bearer auth), dependency-free Prometheus metrics, job runner, reporting, and a pluggable `StatusStore` (file or DuckDB). |
| **Smart Config** (`com.gamma.config.spec` / `.io` / `.safety`) | A machine-readable `ConfigSpec` per config type (one source of truth for the loader, the AI, and a future UI), a pure parse→validate→prepare pipeline, structured `Finding`s, a canonical `.toon` serializer, a stable-id `ConfigRegistry`, and a hard-fail config **safety validator** (path jail / numeric bounds / output allow-list). |
| **Metadata Graph** (`com.gamma.catalog`) | A typed, traversable graph: sources → schemas → columns → emitted event tables → Stage-2 transforms → KPIs/reports, with a lazy operational overlay (status/lineage/completeness). Fed by `description`/`unit`/`classification` columns in the schema `.toon` plus a `*_meta.toon` KPI catalog. Served at `/catalog*`. |
| **Assist Agent** (optional `file-processor-agent` module) | An in-JVM, scoped **Assist API** backed by a skill registry over local Ollama (or, in connected builds, a hosted model). Seven skills turn a sentence into a *validated, confirm-first draft*. All AI deps live in this module only — the core fat-JAR stays zero-new-dependency. |

### The assist skill catalog (all shipped, all draft-only / confirm-first)

| Skill | Since | Does | Replaces |
|---|---|---|---|
| `explain-entity` | v3.3.0 | Explain any entity (pipeline/batch/run/report/error) grounded on the catalog + Control API reads + docs. Read-only. | Multi-screen drill-down to diagnose. |
| `nl-to-schedule` | v3.4.0 | "every weekday 6am after adjustment_etl" → a validated JobConfig draft with `nextRuns[]`. | The cron-builder widget. |
| `suggest-config` | v3.5.0 | Sample + partial config → pre-filled fields with rationale, validated by loader + safety validator. | A long config form + docs hunt. |
| `kpi-to-sql` *(the hero)* | v3.6.0 | A KPI in business terms → Stage-2 transformation SQL, validated in a **sandboxed** DuckDB (EXPLAIN/LIMIT-0), surfacing chosen join keys & interpretation. | Hand-writing transform SQL. |
| `diagnose-and-alert` | v3.7.0 | Event-driven: on a FAILED batch → severity + root-cause draft; and NL → alert-rule draft. | Manual log triage + alert config. |
| `report-sql` | v3.8.0 | NL → a read-only query over the operational ledgers (batches/files/lineage/runs), sandbox-validated. | A report-builder UI. |
| `report-narrative` | v3.8.0 | A report JSON → a short, strictly-extractive plain-language narrative (abstain-safe). | Reading dense report JSON by hand. |

The agent **proposes; tested endpoints dispose** — every state-changing suggestion is a draft
applied with the human's credential, confirm-first. Generated SQL is validated in a locked-down
sandbox (no filesystem/extension access, statement allow-list). Air-gapped builds omit hosted
SDKs entirely, so "local-only" is a packaging guarantee, not a flag. Full design rationale:
[v3 agent MVP](../docs/v3-agent-mvp.md).

---

## Repository layout

A two-module Maven reactor (parent POM at the repo root):

| Module | Role |
|---|---|
| `file-processor/` | The lean, deployable ETL engine + control plane (this README). The fat-JAR; **stays zero-new-dependency**. |
| `file-processor-agent/` | **Optional** embedded assist agent. All AI/LLM dependencies (LangChain4j, Ollama, hosted SDKs) live here only. Loaded in-process by `SourceService` via `ServiceLoader` when present. |

```powershell
cd file-processor && mvn clean package   # builds just the lean core (parent resolved by relativePath)
mvn clean package                        # at repo root: builds the whole reactor (core + agent)
```

---

## Design philosophy in one paragraph

Stage-1 is an **M..N multiplexer**: a batch of **M** input files is demultiplexed and routed into
**N** partitioned output files — decoupled from the input file count. Records are routed by
**partition key** (e.g. `year/month/day`) and, on the plugin path, by **segment type** (one file →
many typed streams). Stage-1 transformations are **stateless and per-record** — type coercion,
column selection, partition-key derivation, light date composition — which keeps every batch an
embarrassingly parallel, crash-isolated unit. Heavy work (joins, aggregation, cross-record logic)
is the job of **Stage-2 enrichment**, which runs over the committed Parquet output. Full rationale:
[Architecture & Design](../docs/architecture.md#design-philosophy--scope).

---

## Feature matrix

| Feature | Detail |
|---|---|
| **Generic onboarding** | Onboard any CSV source with one hand-authored generation config |
| **Three-tier config** | Generation → Schema → Pipeline; each layer has a single responsibility |
| **Smart Config model** | Machine-readable `ConfigSpec` per type; structured validation findings; canonical `.toon` serializer; stable-id registry — one source of truth for loader, AI, and UI |
| **Config safety validator** | Hard-fail path jail, numeric bounds, output-format/codec allow-list (R6) |
| **Pre-ETL utilities** | 6 independent commands to search, stage, extract, and archive raw deliveries |
| **Vectorized CSV ingest** | `csv_settings.engine: auto` uses DuckDB's native `read_csv` for clean files (4–5× faster); falls back to the Java parser for messy SQL\*Plus dumps |
| **Adaptive junk detection** | Skips SQL\*Plus preamble lines (fixed + variable, e.g. ORA-28002 password expiry) |
| **Multi-format dates** | `COALESCE(TRY_STRPTIME(...))` chains handle multiple Oracle date formats per column |
| **Typed output** | DATE, TIMESTAMP, DOUBLE, VARCHAR — all cast safely; bad values land as NULL, not crashes |
| **Hive partitioning** | `year=YYYY/month=MM/day=DD` directory structure, partition key from config |
| **CSV or Parquet output** | Switched per pipeline via `output.format`; Snappy/zstd/gzip compression for Parquet |
| **Controllable parallelism** | Virtual-thread executors with semaphore caps at both the batch and multi-source levels; per-connection DuckDB thread cap |
| **Large-file handling** | Engine scratch (temp DB + DuckDB spill) lives on the data volume (`dirs.temp`), never the system `/tmp`; single-pass streaming ingest (no 2–3× materialization); optional `processing.chunking` streams oversized files in bounded chunks so peak scratch stays small regardless of file size |
| **Multi-source orchestration** | `MultiSourceProcessor` runs many sources concurrently in one JVM, failure-isolated |
| **Idempotency** | Marker files (`.processed`) prevent re-ingestion; stale markers pruned by `retention_days` |
| **Multi-schema dispatch** | `schemas[]` routes files to schemas by filename pattern or column-count probe |
| **Plugin ingester** | `processing.ingester:` loads a custom `FileIngester`; one input file can emit multiple event-type segments into separate partitioned tables. For TB-scale custom formats, implement `StreamingFileIngester` instead — emit records and the framework bounds heap/scratch automatically |
| **Stage-2 enrichment** | DuckDB-backed joins/aggregations over Stage-1 output; event- and schedule-driven, idempotent, self-chaining |
| **Scheduler & jobs** | Cron + fixed-delay job runner with per-job locking and run history |
| **Control API** | ~30-route JDK HTTP server: lifecycle, audit, reports, enrichment, catalog, config-spec, validate, assist |
| **Metadata Graph** | Queryable catalog of sources → tables → columns → KPIs with a lazy operational overlay (`/catalog*`) |
| **Embedded AI assist** | Optional in-JVM agent with 7 skills (NL→cron, NL→SQL, config suggest, diagnose, explain, report) — local-first, confirm-first, sandboxed |
| **Full audit log** | Per-file status CSV, per-batch summary, and an input→output lineage matrix |
| **Quarantine** | Wrong-schema and unreadable files are automatically isolated — never retried |
| **Metrics** | Dependency-free Prometheus exposition at `/metrics` |
| **DuckLake registration** | Optional: registers written Parquet files into a DuckLake catalog backed by PostgreSQL |
| **Scoped security** | Separate `control` / `assist.read` / `assist.write` token tiers; fail-closed (no open-by-default) |
| **Fat JAR deployment** | Single `mvn clean package` produces a fully self-contained deployable JAR |

---

# User guide

This guide walks from a clean checkout to a running, observable pipeline — then into the optional
layers. Each step links to the deep-dive doc when you need more.

## 1. Install & build

**Prerequisites:** Java 24+ (built/tested on JDK 24; local dev on 26) and Maven 3.9+. No other
runtime dependencies — the fat JAR bundles DuckDB, univocity, JToon, and the rest.

```powershell
cd file-processor
mvn clean package
# Produces: target/file-processor-<version>.jar  (~90 MB, all deps bundled)
```

To build the whole reactor (core **plus** the optional assist agent), run `mvn clean package`
from the repository root instead.

> **Toolchain note (this workstation):** JDK at `C:\.jdks\openjdk-26.0.1`, Maven at
> `C:\maven\apache-maven-3.9.16\bin`. Neither is on `PATH` — set them per shell:
> ```powershell
> $env:JAVA_HOME='C:\.jdks\openjdk-26.0.1'
> $env:PATH="$env:JAVA_HOME\bin;C:\maven\apache-maven-3.9.16\bin;$env:PATH"
> ```

## 2. Onboard a new source

A source is described by **three config files** under `file-processor/config/<source>/`. Only the
first is hand-authored; the other two are generated and then tuned.

| File | Authored | Purpose |
|---|---|---|
| `<source>_gen.toon` | by hand | Tells `SchemaExtractor` how to read the sample (delimiter, junk/tail skipping, which columns are dates/timestamps). |
| `<source>_schema.toon` | generated | Source of truth for the transformation: field selectors, types, partition keys, mapping rules. |
| `<source>_pipeline.toon` | generated | Runtime settings: directories, output format, threads, dedup, and the pre-ETL utility sections. |

Generate the schema + pipeline from a sample file:

```powershell
# Windows (from file-processor/)
ura.bat create-schema <source> path\to\sample.csv config\<source>\<source>_gen.toon

# Linux / Mac
./ura.sh create-schema <source> path/to/sample.csv config/<source>/<source>_gen.toon
```

Then review the generated files. The full field reference — every `csv_settings` knob,
`transformType` (`DIRECT` / `CONCAT_DT` / `FILENAME_DATE`), multi-schema dispatch, and the type
mapping — is in the [Configuration Reference](../docs/configuration.md). For proprietary/binary
formats, write a `FileIngester` plugin instead of a schema; see [Plugin Ingester](../docs/plugins.md).

> **`.toon` gotchas:** no `#` comments (parsing stops at the first one); quote any value
> containing `:` (Windows paths, JDBC URLs); the map-vs-tabular array choice is load-bearing.
> The Smart Config serializer handles these for you when configs are written programmatically.

## 3. Run a pipeline

From the **repository root**, the bundled sample scripts run a pipeline end-to-end:

```powershell
run-adjustment.bat       # Windows — runs config/adjustment/adjustment_pipeline.toon
run-voucher.bat          # Windows — runs config/voucher/voucher_unknown_pipeline.toon
bash run-adjustment.sh   # Linux / Mac
```

Or run any pipeline config directly (the scripts just wrap this):

```powershell
java -jar file-processor/target/file-processor-<version>.jar config/<source>/<source>_pipeline.toon
```

> The deploy bundle produced by `package.ps1` ships a generic `run.sh <adapter>` /
> `run.bat <adapter>` that resolves `config/<adapter>/*_pipeline.toon` automatically.

## 4. Drop files & run (the steady state)

Place `.csv` or `.csv.gz` files under `inbox/<adapter>/` (in date sub-folders) and run the
pipeline. Already-processed files are skipped automatically via `.processed` markers in
`markers/<adapter>/`; markers older than `retention_days` (default 90) are pruned at each poll
start. Wrong-schema or unreadable files are moved to `quarantine/<adapter>/` and never retried.

## 5. Run many sources at once

```bash
java -cp target/file-processor-<version>.jar com.gamma.inspector.MultiSourceProcessor \
     -Dsources.max=4 config/
```

`MultiSourceProcessor` runs every `*_pipeline.toon` it finds (files or directories), bounded by
`-Dsources.max`, with each source failure-isolated in its own virtual-thread lane. See
[Operations → Multiple sources in one process](../docs/operations.md#multiple-sources-in-one-process).

## 6. Pre-ETL utilities

`MainApp` exposes a suite of independent commands for sourcing, staging, extracting, and archiving
raw deliveries *before* the pipeline picks them up — driven by the `search`, `copy_tars`, and
`backup` sections of the same pipeline `.toon`. Run `ura.bat` / `ura.sh` with no arguments to list
them. Details: [Operations → Pre-ETL utilities](../docs/operations.md).

## 7. Stage-2 enrichment (joins & aggregation)

Stage-1 deliberately avoids joins and aggregation. When you need them, define an **enrichment job**
(`*_enrich.toon`): it registers reference tables and Stage-1 partitions as DuckDB views, runs a
`transform` SQL, and writes an idempotent partitioned result. Jobs are **event-driven** (fire when
an upstream batch commits) or **schedule-driven**, hold a per-job lock, and can self-chain. Inspect
runs and lineage via `/enrichment*` (below). Reference: [Architecture](../docs/architecture.md)
and [Operations](../docs/operations.md).

If you don't want to hand-write the SQL, the optional `kpi-to-sql` skill (step 11) drafts and
sandbox-validates it from a business description.

## 8. Schedule jobs

Config-driven jobs run on **cron** or **fixed-delay** schedules via the built-in `Scheduler`, with
per-job locking and run history. Define a `JobConfig` (`.toon`), then list/trigger via `/jobs*`.
The `nl-to-schedule` skill (step 11) turns "every weekday 6am after adjustment_etl" into a
validated JobConfig draft with a preview of the next run times.

## 9. Operate via the Control API

When run as a service, the control plane exposes a JDK-`HttpServer` (Jackson JSON, bearer auth).
Routes by scope:

**Open (no token):**
```
GET  /health                              liveness
GET  /ready                               readiness
GET  /metrics                             Prometheus exposition
```

**`control` scope (bearer token):**
```
GET  /pipelines                           list pipelines + state
POST /pipelines/{name}/trigger            run one pipeline once
POST /pipelines/{name}/pause | /resume    pause/resume in the poll cycle
GET  /pipelines/{name}/commits            committed batch ids
GET  /pipelines/{name}/batches            batch audit rows
GET  /pipelines/{name}/files              per-file audit rows
GET  /pipelines/{name}/lineage[?batchId=] input→output lineage rows
GET  /pipelines/{name}/quarantine         quarantined inputs + reason
POST /pipelines/{name}/reprocess          body {"batchId":"…"} — replay a batch
POST /trigger                             run all pipelines once
POST /validate                            body {"configPath":…} or {"type":…,"config":{…}[, "safety":true]}
GET  /status                              live status snapshot (all pipelines)
GET  /report[?from=&to=]                  service-wide batch-audit report
GET  /jobs · /jobs/{name}/runs            jobs + run history
POST /jobs/{name}/trigger                 run a job once now
GET  /enrichment · /enrichment/{job}/runs · /lineage · /report   Stage-2 audit
```

**`assist.read` scope (satisfied by `control`):**
```
GET  /catalog · /catalog/tables/{id} · /catalog/kpis · /catalog/graph   metadata graph
GET  /config/spec/{type}                  declarative spec for a config type (UI form-render)
GET  /assist/diagnoses                    recent event-driven failure diagnoses
POST /assist/{intent}                     run an assist skill (delegates to the agent module)
```

Security is **fail-closed**: a scope with no configured token returns `401` (locked) rather than
running open. `/assist/*` returns `503` if the optional agent module isn't on the classpath,
leaving the core unchanged. Token tiers are `control` (superuser) / `assist.read` / `assist.write`.

## 10. Explore the Metadata Catalog

The Metadata Graph is a typed, queryable map of your data estate: sources → raw schemas → columns →
emitted event tables → Stage-2 transforms → KPIs/reports, with a **lazy operational overlay** that
pulls live status/lineage/completeness from the audit reads. Populate the business layer by adding
`description` / `unit` / `classification` columns to your schema `.toon` and a `*_meta.toon`
(KPI catalog + domain notes) alongside the enrichment config. Query it at `/catalog`,
`/catalog/tables/{id}`, `/catalog/kpis`, and `/catalog/graph`. This is what the `explain-entity`
and `kpi-to-sql` skills ground on.

## 11. The optional AI assist agent

The agent is a separate module loaded in-process when present — it never bloats the lean core.

**Enable it:**
1. Build the whole reactor (`mvn clean package` at the repo root) so `file-processor-agent` is on
   the classpath.
2. Provide a model: a local **Ollama** server (default; air-gapped-safe) or, in connected builds,
   a hosted provider (Gemini/Claude/ChatGPT). Tiers auto-select per hardware profile
   (dev-laptop / cpu-only / production) — see the [agent MVP](../docs/v3-agent-mvp.md#deployment-profiles-locked-v-8).
3. Configure the `assist.read` / `assist.write` tokens.

**Call a skill** (everything is confirm-first; state-changing skills return a *draft* `.toon`):
```bash
curl -X POST localhost:<port>/assist/nl-to-schedule \
     -H "Authorization: Bearer <assist-token>" \
     -d '{"userText":"every weekday 6am after adjustment_etl","knownPipelines":["adjustment_etl"]}'
```

The response carries `{ suggestions, rationale, confidence, validated, data, applyVia? }`. Drafts
pass a deterministic oracle (config parser / **sandboxed DuckDB**) + repair loop before you ever
see them — so they're crash-safe and parse-safe. "Valid" ≠ "correct", though: review the surfaced
interpretation (chosen join keys, KPI definition, sample rows) before applying. See the
[skill catalog](#the-assist-skill-catalog-all-shipped-all-draft-only--confirm-first) above and the
[security guardrails](../docs/v3-agent-mvp.md#non-negotiable-security-guardrails).

## 12. Output, audit & troubleshooting

**Output** lands as Hive-partitioned Parquet/CSV under `database/<source>/` (e.g.
`.../year=2020/month=04/day=03/<table>_out.parquet`). Optionally registered into a DuckLake
catalog — see [Integrations](../docs/integrations.md).

**Audit** is three-layered: a per-file status CSV (`status/<source>/`), a per-batch summary, and an
input→output **lineage matrix** — all also queryable via the Control API (`/pipelines/{name}/files`,
`/batches`, `/lineage`). Per-run logs land in `logs/<source>/` when `dirs.log_dir` is set.

**When something fails:** check the quarantine directory and reason, the status CSV, and
[Troubleshooting](../docs/troubleshooting.md) for common failures and fixes. The `explain-entity`
and `diagnose-and-alert` skills can synthesize a root-cause from the same audit data if the agent
is enabled.

**Very large files / `No space left on device`:** the engine writes its scratch (the per-batch temp
DB + DuckDB spill) to `dirs.temp` on your data volume, not the system `/tmp`. If a huge file still
exhausts that volume, set `processing.duckdb.temp_directory` to a roomier disk and/or enable
`processing.chunking` to stream the file in bounded chunks — see
[Configuration → Large files](../docs/configuration.md#large-files-scratch-location--auto-chunking).
For a huge **custom** (binary/ASN.1/proprietary) file, the CSV chunker doesn't apply — implement a
[`StreamingFileIngester`](../docs/plugins.md#streaming-ingester) so the framework bounds heap/scratch.

---

## Requirements

- **Java 24+** (built and tested on JDK 24; CI pins 24, local dev on 26)
- **Maven 3.9+** to build
- No other runtime dependencies for the core — the fat JAR bundles DuckDB, univocity, JToon, and
  the rest. The optional assist agent additionally needs a reachable model (local Ollama or a
  hosted provider) and ships its own isolated dependency tree.

---

## Documentation index

This README is the overview + user guide. Detailed topics live under [`../docs/`](../docs/):

| Doc | Covers |
|---|---|
| [Architecture & Design](../docs/architecture.md) | The two-stage engine (M..N multiplexer + enrichment), behavior-injection seams, directory layout, deliberate non-goals |
| [Configuration Reference](../docs/configuration.md) | The three config files, configuration by source format, multi-schema dispatch, type mapping |
| [Plugin Ingester](../docs/plugins.md) | The `FileIngester` interface, segment schemas, the `TypedRecordIngester` reference plugin |
| [Operations](../docs/operations.md) | Pre-ETL utilities, batch processing & concurrency, multi-source orchestration, output structure, audit logs, deployment |
| [Integrations](../docs/integrations.md) | DuckLake registration and the pg_duckdb warehouse query layer |
| [Troubleshooting](../docs/troubleshooting.md) | Common failures and fixes |
| [v3 Architecture & Redesign](../docs/v3-architecture.md) | The 3.x assessment, gaps (G1–G10), and the Smart Config / agent / UI-ready redesign |
| [v3 Agent MVP](../docs/v3-agent-mvp.md) | The assist-agent design: skills, model tiering, oracles, security guardrails, hardware profiles |
| [v3 Plan](../docs/v3-plan.md) | The sequenced milestone build (M1–M8) and current branch state |

Engineering notes (not user-facing): [design decisions & ADRs](../docs/design-notes.md) ·
[performance & bottleneck analysis](../docs/performance.md) · [test coverage](../docs/test-coverage.md) ·
[API stability policy](../docs/api-stability.md).
