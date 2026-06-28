---
metadata:
  document_id: 03-ARCHITECTURE
  title: Architecture and Design
  last_updated_date: 2026-06-13
  sources_used:
    - docs/architecture.md
    - docs/design-notes.md
    - docs/v3-architecture.md
    - docs/design_analysis.md
    - docs/configuration.md
    - docs/plugins.md
    - docs/api-stability.md
    - docs/performance.md
    - docs/integrations.md
    - docs/refactor-blueprint-v4.md
    - docs/delimited-grammar-design.md
    - docs/parsing-options-reference.md
    - docs/v3-agent-mvp.md
    - inspecto-agent/docs/AGENT_ARCHITECTURE.md
    - inspecto-ui/docs/ui-components.md
    - docs/superpowers/specs/2026-05-27-batch-processing-design.md
  open_questions:
    - agent-kernel CredibilityTier: enum + tierLabel escape hatch was the 0.x decision (ADR-0004); promotion to an app-extensible interface was to be revisited at the kernel 1.0 freeze — final shape not recorded in these project-side mirrors.
  assumptions_made:
    - Where architecture.md / design-notes.md / design_analysis.md describe the older single-stage or "Java 24 / single-module" shape, the code-grounded two-stage, two-module, Java-25 description is treated as truth (per v3-architecture A0 "code is truth" and the v4.0 migration record).
---

# Architecture and Design

> **Naming/version note.** This document uses the current vocabulary: product **Inspecto**;
> module dirs `inspecto/`, `inspecto-agent/`, `inspecto-agent-hosted/`, `inspecto-ui/`; Maven
> artifactIds unchanged (`file-processor`, `file-processor-agent`); Java packages still
> `com.gamma.*`. Several historical sources predate this — see the Conflict Report.

## 1. Design philosophy & scope

Inspecto is a deliberately small ETL engine built around one idea: an **M..N multiplexer**. A
batch of **M** input files is demultiplexed and routed into **N** partitioned output files —
explicitly *not* one-to-one. Two mechanisms do the routing:

- **Partition fan-out (the "N").** Every surviving record is routed by its partition key
  (typically `year/month/day` from a date column, optionally prefixed by e.g. `event_type`).
- **Segment demultiplexing (plugin path).** One input file carrying multiple record types (CALL +
  SMS in one CDR file) is split into independent typed streams, each with its own schema and
  partitioned output tree.

Combined: **M input files → unioned per record type → N partitioned outputs.** The batch is the
unit of work; partition key and segment type are the routing keys.

**Stage-1 transformations are stateless and per-record** — type coercion, column
selection/rename, partition-key derivation, light date composition. That narrowness is what keeps
every batch an embarrassingly parallel, crash-isolated, single-pass unit.

### 1.1 The two-stage shape (current truth)

The platform has two data stages under one control plane. The older `architecture.md` /
`design-notes.md` text describes only Stage-1 and lists joins/aggregation as platform non-goals;
that is **stale** — it is correct only for Stage-1.

- **Stage-1 (`com.gamma.etl` + `com.gamma.inspector`)** — the multiplexer above. Non-goals (by
  design): no joins against reference data, no cross-record aggregation, no state held across
  records/batches.
- **Stage-2 enrichment (`com.gamma.enrich`)** — the sanctioned home for joins/aggregation, run as
  `transform` SQL over the committed Hive-partitioned output, idempotently and incrementally.

So "do it downstream" is now first-class and in-platform: Stage-1 stays a clean multiplexer;
Stage-2 owns the cross-record work.

## 2. System architecture

```
┌──────────── PRE-ETL: staging (MainApp / `ura` utility commands) ──────────────┐
│  base.dirs/ ─search→ available_files.csv   ─copy→ dirs.poll/<date>/            │
│  base.dirs/ ─copy-tars→ dirs.poll/   dirs.poll/ ─extract→ dirs.poll/<date>/    │
│  available files ─backup→ dirs.backup                                          │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 1: Bootstrap (once per source) ──────────────────────────┐
│  <source>_gen.toon + sample.csv ─create-schema→ <source>_schema.toon           │
│                                                  + <source>_pipeline.toon       │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 2: ETL Processing (continuous) ──────────────────────────┐
│  <source>_pipeline.toon + <source>_schema.toon + inbox/<date>/*.csv.gz          │
│        ──► SourceProcessor ──► Parquet / CSV  (+ DuckLake, optional)            │
└───────────────────────────────────────────────────────────────────────────────┘

┌──────────────── STEP 3: Analytics Query Layer (optional) ─────────────────────┐
│  database/**/*.parquet ─► pg_duckdb extension ─► PostgreSQL :5432 ─► DBeaver    │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Layer map

| Layer | Packages | Role |
|---|---|---|
| **Stage-1 ingest** | `com.gamma.etl`, `com.gamma.inspector` | The M..N multiplexer; batch fan-out, ingest, transform, partitioned write, audit, `BatchEvent` emission |
| **Stage-2 enrichment** | `com.gamma.enrich` | Joins/aggregations over Stage-1 output; event/schedule-driven; idempotent; self-chaining |
| **Control plane** | `com.gamma.service` / `.control` / `.report` / `.job` / `.metrics` | `SourceService` hub, `BatchEventBus`, `Scheduler`, `ControlApi`, Prometheus metrics, job runner, reporting, `StatusStore` |
| **Smart Config** | `com.gamma.config.spec` / `.io` / `.safety` | Machine-readable `ConfigSpec`, pure parse→validate→prepare, `Finding`s, canonical `.toon` codec, `ConfigRegistry`, safety validator |
| **Metadata Graph** | `com.gamma.catalog` (`.spi`) | Typed traversable graph + lazy operational overlay; `/catalog*` |
| **SQL guard/sandbox** | `com.gamma.sql` | `SqlGuard` (lexical allow-list) + `SqlSandbox` (register-then-seal) + `SqlOracle` (EXPLAIN/LIMIT-0) + `SqlViews` |
| **Assist SPI** | `com.gamma.assist`, `com.gamma.assist.spi` | `AssistAgent` SPI the core depends on; `AssistRequest`/`AssistResult`/`Diagnosis` DTOs |
| **Assist agent (optional)** | `com.gamma.agent.*` (in `inspecto-agent/`) | Skills/capabilities over the agent-kernel + LangChain4j + Ollama; hosted providers in `inspecto-agent-hosted/` |

### 2.2 Stage-1 package internals (key classes)

`SourceProcessor` is a pure orchestrator (creates the thread pool, drives the per-batch lifecycle,
holds zero business logic). Concerns are owned by single-responsibility classes:

- **Orchestration:** `MultiSourceProcessor`, `BatchProcessor` (thin coordinator), `BatchPlanner`
  (greedy `max_files`/`max_bytes` packing), `ReprocessCommand`.
- **Ingest seam (`BatchIngestStrategy`):** `CsvBatchStrategy` (delimited text) and
  `StreamingPluginBatchStrategy` (plugin); both return a typed `IngestOutcome`. `DuckDbRecordSink`
  bridges plugin `emit()` calls to DuckDB.
- **Engines:** `DuckDbCsvIngester` (native vectorized `read_csv`, default for clean configs),
  `CsvIngester` (Java line-by-line fallback for messy files), `FileChunker` (bounded chunks for
  oversized files).
- **Transform/write:** `DataTransformer` (assembles the `CREATE TABLE … AS SELECT`),
  `TransformCompiler` (per-column `transformType → ColumnRule` registry), `PartitionWriter`
  (`COPY … PARTITION_BY` + two-step atomic reveal), `OutputFormat` (enum-as-strategy).
- **Lifecycle/audit:** `MarkerManager`, `QuarantineManager`, `DuckLakeRegistrar`,
  `BatchAuditWriter`, `ManifestStore`, `LineageCollector`, `CommitLog`.
- **Plugin SPI:** `StreamingFileIngester` + `RecordSink`; reference impl `TypedRecordIngester`
  (`com.gamma.ingester`).

### 2.3 Behavior-injection seams (v3.9.0 modularity pass, D7)

Variant behaviour is injected, not branched inline: `BatchIngestStrategy`, `StreamingFileIngester`
(SPI by FQCN), `TransformCompiler`, `OutputFormat`, `StatusStore`
(`FileStatusStore`/`DbStatusStore`), and the `BatchEventBus`. These keep the data path lean while
making formats, ingest paths, transforms, and audit sinks independently extensible and testable.
A v4.x refactor (`refactor-blueprint-v4.md`) further consolidated cross-cutting mechanics
(`CsvLedger<T>`, `Csv`, `FileWalker`, `LockingRunner`, `BoundedHistory<T>`, `ParserSpec`) — see
Implementation Status for its phase state.

## 3. Data model

### 3.1 The three config files
| File | Owns | Authored |
|---|---|---|
| `<source>_gen.toon` (Generation) | how to *read the sample* — delimiter, junk/tail trimming, date/timestamp columns | by hand |
| `<source>_schema.toon` (Schema) | the transform itself — `raw.fields[]` (output field ← source column by zero-based `selector` + type), `mapping.rules[]`, `partitions[]` | generated, then tuned |
| `<source>_pipeline.toon` (Pipeline) | runtime — dirs, output format, threads, dedup, date/timestamp format lists, pre-ETL sections | generated, then tuned |

A mapping rule is a **scalar expression over a single row of one table** — `DIRECT`,
`EXPR` (any per-row DuckDB scalar), `CONCAT_DT`, `FILENAME_DATE`. Anything needing more than one
row is **Stage-2 enrichment**, not a transform.

### 3.2 The path every row takes
```
raw bytes ─(built-in delimited reader │ plugin ingester)→ VARCHAR staging row
          ─(one mapping rule per column → typed scalar expr)→ typed output row
          ─(partitions[] → year / month / day / …)→ Hive-partitioned Parquet/CSV
```
Everything is read as VARCHAR and typed later via `mapping.rules[]` + `TRY_STRPTIME`/`TRY_CAST`, so
a bad value becomes `NULL`, never a failed batch.

### 3.3 On-disk layout (per source)
`inbox/` → `database/` (partitioned Parquet/CSV) + `backup/` + `temp/` (scratch) + `errors/` +
`quarantine/` + `markers/` (`.processed` sentinels) + `status/` (audit CSVs) + `logs/`. All
`dirs.*` are relative to the sandbox root; managed dirs must not nest inside `poll`.

### 3.4 Audit & status data
Three per-run CSVs (`_status_`, `_batches_`, `_lineage_`), one persistent fsync'd
`_commits.log`, and per-batch JSON manifests. The pluggable `StatusStore` projects this into a
database when `-Dstatus.backend=db` (DuckDB default; tables `inspecto_status_{commits,batches,
files,lineage,quarantine}`, with pre-rebrand `ucc_status_*` migrated in place on first connect).

### 3.5 Metadata graph node/edge model
Node kinds: `SOURCE`, `RAW_SCHEMA`, `COLUMN`, `EVENT_TABLE`, `TRANSFORMED_TABLE`,
`REFERENCE_TABLE`, `KPI`, `REPORT`. Edges: `EMITS`, `DECLARES`, `DESCRIBES`, `MATERIALIZES`,
`FEEDS`, `JOINS_INTO`, `COMPUTED_FROM`, `USES`. Structural graph cached & rebuilt per poll cycle;
operational overlay fetched lazily per node from the audit reads.

## 4. Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java **25+** floor (CI 25, dev 26); virtual-thread executors |
| Transformation engine | embedded **DuckDB** (bundled JDBC; performance doc references 1.5.2) |
| Config format | **JToon** (`.toon`); Jackson for JSON wire form |
| CSV parsing | DuckDB native `read_csv` (default) + univocity Java parser (messy-file fallback) |
| HTTP control plane | JDK built-in `HttpServer` + Jackson — **no Javalin/Spring** (deliberately, ADR in 08) |
| Metrics | hand-rolled `MetricRegistry`, Prometheus text — **no Micrometer** |
| Scheduler | hand-rolled quartz-like `CronExpression` — **no Quartz** |
| Status DB | DuckDB (default, bundled); PostgreSQL bring-your-own-driver for a future distributed tier |
| Build | Maven 3.9+, `maven-shade` fat JAR; two-module reactor + optional modules |
| Assist models | LangChain4j + Ollama (local); optional hosted Anthropic/OpenAI/Gemini via `inspecto-agent-hosted` |
| Agent framework | the reusable **agent-kernel** library (separate repo), consumed at 1.0.0 |
| Operator UI | Angular 21 + Material + Tailwind (gamma-analytics template) · ag-Grid Community · Chart.js · AntV G6 |

## 5. The assist agent & the agent-kernel

The assist agent is an optional in-JVM module loaded by `SourceService` via a `ServiceLoader` SPI
(`AssistAgent`), registered before `start()` so it can subscribe to the bus. Its design principles:
the agent **proposes; tested endpoints dispose**; **validate before surfacing** ("valid" ≠
"correct" — pair the oracle with confirm-first + surfaced interpretation); **local-first, tiered,
environment-pluggable models**; **local-only guaranteed by packaging** (air-gapped artifact omits
hosted SDKs); **testable CPU-only**.

### 5.1 agent-kernel (reusable library, separate repo)
v4.0 reshaped the assist layer onto `com.gamma.agentkernel`, a framework-agnostic library with a
**three-ring** structure (ring-1 pure zero-dependency core; ring-2 opt-in companions like
`agent-provider-ollama` and `agent-orchestration`; ring-3 per-app bindings). The **lean ETL core
holds zero kernel dependencies** — kernel types live only in `inspecto-agent/` behind the
`AssistAgent` SPI (CI-guarded). Core kernel abstractions: `Capability`/`Tool`/`Evidence`/
`CredibilityTier`, `ConfidenceEstimator`/`EscalationPolicy`/`EscalationRung`, `GroundingGuard`,
`ModelProvider`/`ModelRouter`/`ModelTier`, `AuditSink`/`AgentEvent`. Inspecto wires only the
`Abstain` escalation rung. The full decision set is recorded in
[08-Decisions](08-Decisions.md) (ADR-0001 … ADR-0009).

### 5.2 Validation oracles (the safety net)
- **Config/job drafts:** `*.fromMap` parse + `ConfigSpecs` structured validation + the hard-fail
  `ConfigSafetyValidator`, driven by a `RepairLoop` (generate→validate→repair, cap 3).
- **SQL:** `SqlGuard` (lexical allow-list — single read-only `WITH`/`SELECT`, no DDL/DML, no
  file/extension/system functions, comment-smuggling defeated — run **before** any `EXPLAIN`
  because planning can evaluate smuggled functions) + `SqlSandbox` (register inputs, then `seal()`:
  `enable_external_access=false`, `lock_configuration=true`, extension auto-load off, resource caps)
  + `SqlOracle` (EXPLAIN + LIMIT-0; `columnsProduced` from `ResultSetMetaData`). M8 added an
  in-memory tabular-input mode (`SqlOracle.TableData`) for operational ledgers.

### 5.3 Model routing
Tiers SMALL/MEDIUM/LARGE resolve to a local Ollama model (default Qwen2.5-7B; 14B for `kpi-to-sql`
in prod; Gemma/Qwen 2–3B for narrow tasks) or a hosted provider. Three hardware profiles
(dev-laptop 4GB GPU / cpu-only test / production 16GB+ GPU) auto-select tiers. Hosted routing
(Anthropic/OpenAI/Gemini) lives in the optional `inspecto-agent-hosted` module and is configured
via `GET/PUT /assist/settings` + the `/settings/models` UI screen; API keys are referenced by env
var name, never stored on disk.

## 6. Parsing frontends (frontend/backend split)

Every format converges on the same typing/transform/partition **backend**; only the **frontend**
(bytes → rows) changes. Three frontends: DuckDB-native (`read_csv`/`read_json`/`read_text`), and
the Java **plugin** (`StreamingFileIngester`) for binary/grammar-driven formats. A unified
`parsing:` grammar (fixedwidth/json/text_regex/xml frontends) is **proposed** (see Roadmap); the
delimited slice's externalized **`*.grammar.toon`** + native SQL\*Plus routing + pass-through knobs
(`encoding`/`compression`/`strict_mode`/`null_strings`) + row filters shipped in **4.1** (see
`delimited-grammar-design.md`; note `parsing-options-reference.md` and `configuration.md` are not
yet updated to reflect this — Conflict Report C10).

## 7. Integrations & data flow out

- **DuckLake** — optional lakehouse registration (Parquet data + PostgreSQL catalog) so remote
  DuckDB clients query via the ducklake extension. Non-fatal: a registration failure logs and the
  Parquet output is unaffected.
- **`pg_duckdb` warehouse layer** — query the Parquet output from DBeaver/any PostgreSQL client;
  PostgreSQL handles the wire protocol, DuckDB does all I/O. Views in a `warehouse` schema with
  RBAC roles; partition pruning automatic. (Aliased columns required — see Operations
  troubleshooting.)

## 8. Operator console architecture

Angular SPA on the gamma-analytics ThemeForest template. The reusable layer at
`src/app/inspecto/` (API services + interceptors, auth, grid kit, assist panel, chart host) is
composed by per-screen modules under `src/app/modules/admin/`. Canvas tokens
(`inspecto/theme/chart-tokens.ts`) are the only place hex colors are hardcoded (Chart.js/G6 paint
to canvas). Selectors: `inspecto-` (shared layer), `app-` (feature screens), `gamma-` (template;
do not edit `src/@gamma/`). The HTTP/API layer is rendering-library-agnostic — a property that made
the DevExtreme → Material/ag-Grid/Chart.js/G6 migration a template-and-import swap, not a logic
rewrite.

## 9. Public API surface & stability

`@com.gamma.api.PublicApi` (CLASS-retained) marks the stable surface; within a major version it
follows SemVer. Plugin authors depend on `StreamingFileIngester`/`RecordSink`/`PipelineConfig`;
embedders on `SourceProcessor`/`MultiSourceProcessor`; the service line exposes `SourceService`,
`EnrichmentService`, `ControlApi`, `JobService`, `ReportService`, `DbStatusStore`, and the
`AssistAgent` SPI. **No `module-info.java`** (deliberate — fat shaded JAR with automatic-module
deps). Deliberate breaks: `FileIngester` removed in 3.11.0 (SPI unified on `StreamingFileIngester`);
`AssistResult.confidence` `String` → `double` in 4.0. The `.toon` config format and on-disk output
are stable user-facing contracts (additive, documented changes only).
