---
type: Feature
title: Flows (Pipeline Authoring)
description: The NiFi-style flow graph editor (AntV G6) with node config, a rich parser-config dialog, and per-processor test.
resource: inspecto-ui/src/app/modules/admin/flows/flows.routes.ts
tags: [feature, flows, authoring, g6, graph, parser]
timestamp: 2026-06-28T00:00:00Z
---

# Flows (Pipeline Authoring)

Route under the Pipelines nav group. An **AntV G6** interactive graph editor for authoring flows: gliffy
icon nodes, hover preview, click → a node-config dialog, and per-processor **Test**. Gesture model:
click=select, double-click=configure, plain drag=move, **Shift+drag=draw edge** (two-click Connect also
available). The editor keeps a persistent `Graph` and mutates in place (see the G6 patterns in the
[architecture](../architecture.md)).

A rich **parser-config dialog** configures PARSE nodes across 9 formats (ASN.1 · DSV · HTML · JSON · Other ·
Parquet · TXT · XLSX · XML) with a typed property sheet and ag-Grid/tree test output; the DSV property set
mirrors the backend `CsvSettings`. Parsers persist as reusable `grammar`
[components](components.md). Backed by `FlowsService` / `ComponentsService`; offline via the `mockFlows`
[interceptor](../conventions/mock-backends.md).
