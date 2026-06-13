---
metadata:
  document_id: 02-PRODUCT-REQUIREMENTS
  title: Product Requirements
  last_updated_date: 2026-06-13
  sources_used:
    - inspecto/README.md
    - docs/v3-agent-mvp.md
    - docs/v2-backlog.md
    - docs/operator-console.md
    - docs/v3-architecture.md
    - docs/configuration.md
  open_questions:
    - None blocking. (Console naming and agent-kernel bump are tracked in 01/05.)
  assumptions_made:
    - User personas are inferred from the operator-console guide, the agent "usability thesis", and the onboarding workflow; the source docs do not contain a formal persona definition.
---

# Product Requirements

## 1. User personas

The sources do not define formal personas; the following are synthesised from the operator
console guide, the assist-agent usability thesis, and the onboarding workflow.

| Persona | Goal | Primary surface |
|---|---|---|
| **Data engineer / onboarder** | Onboard a new source with minimal code; tune the transform; handle messy/large/binary files | CLI (`ura` / `create-schema`), the three `.toon` config files, the plugin SPI |
| **Platform operator** | Run pipelines continuously; monitor health; trigger/pause/reprocess; schedule jobs; investigate failures | Inspector web console + Control API |
| **Analyst / data consumer** | Query the partitioned output with familiar SQL tooling | DuckLake catalog / `pg_duckdb` warehouse layer via DBeaver |
| **Plugin author** | Ingest a proprietary/binary/multi-event-type format | `StreamingFileIngester` SPI in the fat JAR |
| **Embedder** | Drive the ETL from Java rather than the CLI | `@PublicApi` types (`SourceProcessor`, `MultiSourceProcessor`, `PipelineConfig`) |

## 2. Core features (functional)

### 2.1 Stage-1 ingest (the M..N multiplexer)
- Poll an inbox → plan batches → ingest files (native DuckDB `read_csv`, the Java parser, or a
  custom plugin) → type-coerce & derive partition keys → write Hive-partitioned Parquet/CSV with
  atomic rename → markers/quarantine → fsync'd commit log + audit CSVs → emit a `BatchEvent`.
- **Stateless, per-record transformations only:** type coercion, column selection/rename,
  partition-key derivation, `CONCAT_DT`, `FILENAME_DATE`, and arbitrary per-row DuckDB scalar
  expressions via `EXPR`.
- **Routing:** by partition key (`year/month/day`, optionally prefixed) and, on the plugin path,
  by segment type (one file → many typed output trees).

### 2.2 Stage-2 enrichment
- Register reference tables + Stage-1 partitions as views on an ephemeral DuckDB, run a `transform`
  SQL (joins/aggregations/derivations), write an idempotent partitioned result.
- Event-driven (fires on upstream batch commit) and/or schedule-driven; per-job locked;
  self-chaining via the event bus.

### 2.3 Control plane
- `SourceService` always-on host; `BatchEventBus` pub/sub; cron + fixed-delay `Scheduler`;
  `ControlApi` (~30-route JDK `HttpServer`, bearer auth); dependency-free Prometheus metrics; job
  runner; reporting; pluggable `StatusStore` (file or DuckDB/Postgres).

### 2.4 Smart Config (machine-readable model)
- A declarative `ConfigSpec` per config type (pipeline/enrichment/job/schema/meta) — one source of
  truth for the loader, the AI, and a future UI form-renderer.
- Pure parse → validate → prepare pipeline; structured `Finding{severity, fieldPath, message}`;
  canonical comment-free `.toon` serializer; stable-id `ConfigRegistry` (O(1) lookups); a hard-fail
  config **safety validator** (path jail / numeric bounds / output allow-list).

### 2.5 Metadata Graph
- A typed, traversable graph: sources → schemas → columns → emitted event tables → Stage-2
  transforms → KPIs/reports, with a lazy operational overlay (status/lineage/completeness). Fed by
  `description`/`unit`/`classification` schema columns + a `*_meta.toon` KPI catalog. Served at
  `/catalog*`.

### 2.6 Embedded AI assist agent (optional)
The skill catalog — all shipped, all draft-only / confirm-first:

| Skill | Since | Replaces |
|---|---|---|
| `explain-entity` | v3.3.0 | Multi-screen drill-down to diagnose |
| `nl-to-schedule` | v3.4.0 | The cron-builder widget |
| `suggest-config` | v3.5.0 | A long config form + docs hunt |
| `kpi-to-sql` *(hero)* | v3.6.0 | Hand-writing Stage-2 transform SQL |
| `diagnose-and-alert` | v3.7.0 | Manual log triage + alert config |
| `report-sql` | v3.8.0 | A report-builder UI |
| `report-narrative` | v3.8.0 | Reading dense report JSON by hand |

### 2.7 Operator web console (*Inspector*, optional)
- Browser UI over every Control API route + all 7 skills: dashboard, pipelines + detail
  (batches/files/lineage/quarantine/inbox-pending), jobs, enrichment, catalog graph, spec-driven
  config authoring, failure diagnoses, AI assist. Token-based connect (no login); served
  same-origin by `ControlApi`.

### 2.8 Pre-ETL utilities
- Independent `ura` commands to search, copy, stage (`copy-tars`/`extract`/`prepare-inbox`), back
  up, and reprocess raw deliveries before the pipeline picks them up.

## 3. Non-functional / scope-shaping requirements

- **Zero-new-dependency lean core** — all AI/hosted/heavy deps live in the optional agent
  module(s); CI enforces it.
- **`.toon` stays the canonical on-disk config**; JSON is only the API/UI wire form;
  backward-compatible.
- **Idempotent, crash-safe commits**; on-disk output format forward-compatible.
- **Air-gapped = local-only by packaging** — hosted SDKs are physically absent from the air-gapped
  artifact; sample data rows never leave the box for a hosted model.
- **Fail-closed security** — scoped `control` / `assist.read` / `assist.write` tokens; a scope with
  no configured token returns `401`, never runs open.
- **Confirm-first, propose-don't-dispose** — no autonomous writes; the agent holds no write token.
- **CPU-only testability** — every skill passes its golden tests without a GPU (CI has none).

## 4. Scope boundaries (explicit non-goals)

### 4.1 Stage-1 non-goals (by design, not omission)
The Stage-1 engine **does not** join against reference data, aggregate across records (`GROUP BY`,
windowing, cross-row dedup), or hold state across records/batches. These would force shared state,
serialise the batch model, and break clean M..N routing. They are the job of **Stage-2 enrichment**
(in-platform since the 2.x line) — so "do it downstream" now has a first-class, in-platform answer.

### 4.2 Platform-level deferrals
- Autonomous (non-confirm) agent actions.
- A fully bespoke "AI behind every screen" inline UX everywhere (the rich version effectively
  requires a GPU on air-gapped nodes; the Assist API + console is the shipped surface).
- Object storage (S3/GCS/Azure) and distributed/multi-node execution — explicitly out of scope and
  against the single-JVM, crash-isolated ethos; deferred indefinitely.
- Config CRUD-from-body was a fast-follow; live pipeline registration + config-write endpoints
  shipped in v4.1 (see Implementation Status).

## 5. The usability thesis (why the agent exists)

| Old UI component | Replaced by | Skill |
|---|---|---|
| Cron-builder widget | one sentence → validated schedule | `nl-to-schedule` |
| Long config form + docs hunt | sample → pre-filled fields + rationale | `suggest-config` |
| SQL editor / transform builder | KPI in business terms → validated SQL | `kpi-to-sql` |
| Report-builder / raw JSON reading | NL query → report, or auto-narrative | `report-sql` / `report-narrative` |
| Multi-screen drill-down to diagnose | ask in place → synthesized answer | `explain-entity` |
| Manual log triage + alert config | event → drafted diagnosis + alert rule | `diagnose-and-alert` |

The pattern: the screen keeps the data view; the agent absorbs the *authoring/diagnostic* controls.
Less form, more intent.
