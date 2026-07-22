---
type: Architecture
title: Backend Architecture
description: Framework-free design — JDK HttpServer, manual DI, ServiceLoader SPIs, virtual threads, embedded DuckDB.
resource: inspecto/src/main/java/com/gamma
tags: [architecture, framework-free, spi, serviceloader, virtual-threads]
timestamp: 2026-07-07T00:00:00Z
---

# Architecture

Inspecto is deliberately **framework-free**: no Spring, no web framework, no IoC container. The whole engine
+ control plane runs on the JDK plus DuckDB.

## Pillars

* **JDK `HttpServer`** — the control plane is `com.sun.net.httpserver.HttpServer` with a single catch-all
  dispatch context. Each request runs on a fresh **virtual thread**
  (`Executors.newVirtualThreadPerTaskExecutor()`). See [Control API](./control-plane/control-api.md).
* **Manual dependency injection** — collaborators (`SpaceManager`, `SourceService`, `JobService`,
  `EventLog`, `MetricRegistry`) are constructed directly and passed as constructor args or reached via
  `static global()` singletons. No annotations, no container.
* **`ServiceLoader` SPIs** — extension points are plain `META-INF/services` SPIs: the ingestion SPI
  ([`StreamingFileIngester`](./engine/ingestion.md)), source [connectors](./acquisition/connectors.md)
  (`SourceConnectorFactory`), pipeline [node types](./pipeline-graph/design.md) (`PipelineNodeType`), the
  [assist agent](./agent/assist-agent.md) (`AssistAgent`), and the `Authenticator` / `Subject` / `TokenRelay`
  trio implemented by `inspecto-security` on Standard ([auth](./editions/auth-security.md)). An absent module
  simply isn't discovered — the no-op path wins. This is what makes
  [editions build flavors](./editions/editions-model.md).
* **Embedded DuckDB** — bulk ingest via the native Appender API; see [DuckDB](./engine/duckdb.md). Requires the
  `--enable-native-access=ALL-UNNAMED` JVM flag (see [build & run](./build-run/build-test.md)).
* **Virtual threads everywhere** — HTTP requests, the [job](./control-plane/jobs.md) executor, batch
  processing, and multi-source orchestration all run on bounded virtual-thread pools.

## Layered view

* **Acquisition** ([framework](./acquisition/framework.md)) discovers + retrieves files (local or via remote
  [connectors](./acquisition/connectors.md)), with dedup/watermark ledgers and a stability gate.
* **Engine** ([ingestion](./engine/ingestion.md) → [DuckDB](./engine/duckdb.md) →
  [output](./engine/output-sinks.md)) parses, transforms, and writes partitioned output per batch.
* **Control plane** ([API](./control-plane/control-api.md), [events/metrics](./control-plane/events-metrics.md),
  [jobs](./control-plane/jobs.md), [multi-space](./control-plane/multi-space.md)) exposes everything over HTTP
  and schedules work.
* **Pipeline graph** ([design](./pipeline-graph/design.md), [live execution](./pipeline-graph/live-execution.md))
  is the authored-Pipeline IR + executor layered on top of the engine.

## Code geography

Since the WS-D reactor split the code spans several Maven modules (authoritative map:
[reactor.md](./modules/reactor.md)). The **core / composition root** [`inspecto/`](./modules/engine.md)
holds `control/` (HTTP API), `service/` (spaces + host), `assist/spi/`, `report/`, `exchange/`,
`expectation/`, `intelligence/`, `model/` and ships the fat JAR. The **engine** was extracted below it:
`etl/` (ingest/transform/output) → `inspecto-etl`; `event/` + `metrics/` → `inspecto-event`; `acquire/`
(acquisition) → `inspecto-acquire`; and `inspector/` (batch coordination), `pipeline/` (pipeline graph +
components), `query/` (query catalog), `job/`, `signal/`, `enrich/`, `ops/`, `catalog/`, `alert/`,
`notify/`, `ingester/` → `inspecto-engine`. Foundation leaves: `api/` → `inspecto-api`, `util/` (DuckDB
access + I/O helpers) → `inspecto-util`, `config/` → `inspecto-config`, the SQL sandbox → `inspecto-sql`.
(The `ura` CLI `MainApp` moved out of `util/` to `inspector/`, so it now ships in `inspecto-engine`.)
