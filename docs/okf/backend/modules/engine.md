---
type: Module
title: Core Module (inspecto/)
description: The composition root — control plane + application packages, ships the fat JAR. artifactId file-processor. The ingestion engine was extracted to sibling modules in the WS-D split.
resource: inspecto/
tags: [module, core, composition-root, file-processor]
timestamp: 2026-07-22T00:00:00Z
---

# Core Module (`inspecto/`)

The **composition root** (artifactId `file-processor`, jar `file-processor.jar`) — the module that wires
the object graph together and shades the single deployable fat JAR. It keeps **zero network/AI
dependencies**: remote protocols live in [connectors](connectors.md), hosted models in
[agent-hosted](agent-hosted.md).

> **Not the engine anymore.** Before the WS-D reactor split (2026-07-22) `inspecto/` held the whole
> engine; the engine was then extracted into the sibling modules below, and `inspecto/` now depends
> *down* on them. Authoritative build order, version management, and the extraction playbook live in
> [reactor.md](reactor.md).

## What lives here now

`inspecto/src/main/java/com/gamma/`: `service` (the always-on host — `CollectorService`, the
[control API](../control-plane/control-api.md) wiring, [`SpaceManager`](../control-plane/multi-space.md)),
`control` (the ~50 HTTP routes), `report`, `assist` (the assist SPI — [interface only](../agent/assist-agent.md)),
`exchange`, `expectation`, `intelligence`, and `model`.

## Extracted engine modules (WS-D) — `inspecto/` depends down on these

The concept docs are unchanged by the move; only the home module changed:

- **`inspecto-engine`** (`file-processor-engine`) — the engine cluster: `signal`, `query`,
  [`pipeline` / pipeline-graph](../pipeline-graph/design.md), `inspector` ([DuckDB](../engine/duckdb.md) sinks
  plus the fat-jar entry points `CollectorProcessor` and `MainApp`), `ingester`, `ops`,
  [`job`](../control-plane/jobs.md), `enrich`, `alert`, `notify`, `catalog`.
- **`inspecto-etl`** (`file-processor-etl`) — [`etl`](../engine/ingestion.md): `PipelineConfig`, the
  ingesters, batch planning, quarantine, lineage, [partitioned Parquet output](../engine/output-sinks.md).
- **`inspecto-event`** (`file-processor-event`) — [`event` + `metrics`](../control-plane/events-metrics.md):
  the Operational-Intelligence event store + metric registry; owns `logback.xml`.
- **`inspecto-acquire`** (`file-processor-acquire`) — the [acquisition framework](../acquisition/framework.md):
  connectors, connection profiles/registry/workbench, the fingerprint ledger, stability gate,
  retry/circuit-breaker/rate-limit policies.
- Foundation leaves **`inspecto-api`** (`@PublicApi`), **`inspecto-util`** (DuckDB access + CSV/file/tar
  helpers), **`inspecto-config`** ([TOON config](../config/toon-config.md) — spec/io/safety), and
  **`inspecto-sql`** (the sandboxed read-only DuckDB SQL surface).

Modularization is reactor-internal — the single `file-processor.jar` is unchanged (shade has no
include-list, so the sibling modules' classes and resources bundle automatically).

Build/run: see [build & test](../build-run/build-test.md).
