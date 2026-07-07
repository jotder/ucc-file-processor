---
type: Feature
title: Pipelines (Authoring)
description: The NiFi-style Pipeline graph editor (AntV G6) with node config, a rich parser-config dialog, and per-processor test.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts
tags: [feature, pipelines, authoring, g6, graph, parser, workbench]
timestamp: 2026-07-07T00:00:00Z
---

# Pipelines (Authoring)

Route `/pipelines` (Workbench nav group), dir `modules/admin/pipelines/`. An **AntV G6** interactive graph
editor for authoring **Pipelines** (the DAG artifact — never "flow"): gliffy icon nodes, hover preview,
click → a node-config dialog, and per-processor **Test**. Gesture model: click=select,
double-click=configure, plain drag=move, **Shift+drag=draw edge** (two-click Connect also available). The
editor keeps a persistent `Graph` and mutates in place (see the G6 patterns in the
[architecture](../architecture.md)).

A rich **parser-config dialog** configures PARSE nodes across 9 formats (ASN.1 · DSV · HTML · JSON · Other ·
Parquet · TXT · XLSX · XML) with a typed property sheet and ag-Grid/tree test output; the DSV property set
mirrors the backend `CsvSettings`. Parsers persist as reusable `grammar`
[components](components.md). Backed by `PipelinesService` / `ComponentsService`; offline via the
`mockFlows`-gated handler of the unified [mock backend](../conventions/mock-backends.md).
