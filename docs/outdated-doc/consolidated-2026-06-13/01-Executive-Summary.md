---
metadata:
  document_id: 01-EXEC-SUMMARY
  title: Executive Summary
  last_updated_date: 2026-06-13
  sources_used:
    - inspecto/README.md
    - docs/v3-architecture.md
    - docs/v3-agent-mvp.md
    - docs/design_analysis.md
    - docs/v2-backlog.md
    - SESSION_STATUS.local.md
  open_questions:
    - Final product/console naming — see Conflict Report C2 (product is "Inspecto"; the web console is still "Inspector" in shipped docs, "Inspecto Console" proposed but not decided).
    - Whether to bump the consumed agent-kernel 1.0.0 → 1.1.0 (currently optional; no behaviour change).
  assumptions_made:
    - "Current truth" resolves naming/version conflicts in favour of the most recent, most specific sources (the engine README, operations.md, and the shipped v4.0 migration record) over the historical v2/v3 planning docs. See Conflict Report.
---

# Executive Summary

## Vision

**Inspecto** (formerly *UCC File Processor*) is a small, high-throughput,
**configuration-driven ETL platform** built on an embedded DuckDB engine. Its purpose is to
onboard arbitrary delimited or proprietary data feeds with a single config file, transform and
partition them deterministically, and make the result queryable, observable, and operable — all
from one self-contained, zero-runtime-dependency fat JAR.

The product bet that shapes the current line: **a smarter embedded AI assist agent removes UI
components and raises usability** — a cron-builder widget becomes "every weekday 6am after
adjustment_etl"; a 30-field config form becomes "ingest these CDR files daily" + accept/edit; a
SQL editor becomes "revenue per region, last 30 days" → validated SQL.

## What it is (in one paragraph)

The engine is an **M..N multiplexer**: it ingests **M** input files, applies light per-record
transformations, and demultiplexes them into **N** Hive-partitioned Parquet/CSV output files
(decoupled from the input file count). On top of that **Stage-1 ingest** sits a **Stage-2
enrichment engine** (joins/aggregations over the partitioned output), a **control plane** (HTTP
API, scheduler, metrics, audit), and three optional layers that ship in the same build: a
machine-readable **Smart Config** model, a queryable **Metadata Graph**, and an **embedded AI
assist agent**. An optional Angular **operator web console** (*Inspector*) is served from the same
process.

## Current phase

| Dimension | State |
|---|---|
| **Release line** | v4.x · repo at `4.1.0-SNAPSHOT` · latest release **v4.0.0** (shipped 2026-06-05) |
| **Branch** | `4.x` |
| **Runtime** | Java **25+** (CI pins 25; local dev on 26) · embedded **DuckDB** (bundled 1.5.2) · zero external runtime deps in the core fat JAR |
| **Build** | Two-module Maven reactor — `inspecto/` (lean core, artifactId `file-processor`) + `inspecto-agent/` (optional assist agent, artifactId `file-processor-agent`); plus optional `inspecto-agent-hosted/` and a standalone `inspecto-ui/` Angular SPA |
| **AI assist** | All **7 skills shipped** (draft-only / confirm-first); migrated onto the reusable **agent-kernel** library (consumed at 1.0.0) in v4.0 |
| **Operator UI** | Migrated off the commercial DevExtreme stack onto the gamma-analytics Angular/Material/Tailwind shell (ag-Grid, Chart.js, AntV G6); all 9 screens live |

> The v4.0.0 major was driven by two things, **not** an ETL behaviour change: (1) the Java 25
> runtime floor, and (2) reshaping the optional assist module onto the shared agent-kernel
> (`SyncOrchestrator`/`Capability`/confidence-escalation/`AuditSink` primitives). The core ETL
> `@PublicApi` surface and on-disk output are unchanged from 3.x. The one sanctioned break:
> `AssistResult.confidence` moved `String` → `double`.

## Value proposition

- **Generic onboarding.** Any delimited source onboards with one hand-authored generation config;
  proprietary/binary/multi-event-type formats plug in via a custom `StreamingFileIngester`.
- **Lean and self-contained.** A single `mvn clean package` produces a ~90 MB fat JAR that bundles
  DuckDB, the CSV parser, JToon, and the rest — no JVM classpath setup on the target.
- **Crash-isolated and idempotent.** Each batch is an embarrassingly parallel unit with its own
  ephemeral DuckDB connection; markers-last commit ordering + an fsync'd commit log make an
  interrupted run safe to re-process.
- **Observable and operable.** A ~30-route REST control plane, dependency-free Prometheus metrics,
  three-layer audit (per-file status, per-batch summary, input→output lineage), and an optional
  browser console cover monitoring, scheduling, enrichment, catalog, config authoring, and AI
  assist.
- **AI behind every screen, safely.** The agent *proposes; tested endpoints dispose* — every
  state-changing suggestion is a validated, confirm-first draft; generated SQL is validated in a
  locked-down DuckDB sandbox; air-gapped builds physically omit hosted model SDKs.

## Quick status

- **Engine & control plane:** mature; shipped across the 1.x/2.x lines and hardened through 3.x.
- **Smart Config + Metadata Graph:** shipped (v3.2.0 / v3.1.0) — the two "keystones" the agent and
  a future UI lean on.
- **Assist skill catalog:** complete (7 skills, v3.3.0 → v3.8.0); model routing now supports local
  Ollama **and** hosted providers (Anthropic/OpenAI/Gemini) via an optional `inspecto-agent-hosted`
  module.
- **Operator console:** DevExtreme migration **complete**; the console runs on the product-line
  house template.
- **In flight (uncommitted / recent):** a UI design-system pass and a code-tier `ucc → inspecto`
  rename; an engine-tier `ucc_* → inspecto_*` rename of metrics/tables/logger with a status-DB
  migration. See Implementation Status.

For the strategic redesign rationale see [03-Architecture and design](03-Architecture%20and%20design.md);
for what is built vs. pending see [04-Implementation-Status](04-Implementation-Status.md).
