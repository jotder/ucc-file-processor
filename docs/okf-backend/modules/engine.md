---
type: Module
title: Engine Module (inspecto/)
description: The lean core — ingestion engine + HTTP control plane. artifactId file-processor.
resource: inspecto/
tags: [module, engine, core, file-processor]
timestamp: 2026-06-28T00:00:00Z
---

# Engine Module (`inspecto/`)

The lean core (artifactId `file-processor`, jar `file-processor.jar`). Contains the entire engine + control
plane with **zero network dependencies** — remote protocols live in [connectors](connectors.md).

Houses: the [ingestion engine](../engine/ingestion.md), [DuckDB](../engine/duckdb.md) integration,
[output/sinks](../engine/output-sinks.md), the [acquisition framework](../acquisition/framework.md) (the SPI +
local connector; remote connectors are a separate jar), the [HTTP control API](../control-plane/control-api.md),
[events/metrics](../control-plane/events-metrics.md), [jobs](../control-plane/jobs.md),
[multi-space](../control-plane/multi-space.md), the [flow-graph](../flow-graph/design.md), [TOON config](../config/toon-config.md),
the [assist SPI](../agent/assist-agent.md) (interface only), and the `com.gamma.util` CLI tool cluster
(`MainApp` + friends, wired into `package.ps1`).

Build/run: see [build & test](../build-run/build-test.md).
