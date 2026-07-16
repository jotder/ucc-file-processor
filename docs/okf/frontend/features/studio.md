---
type: Feature
title: Studio
description: The Builder-lens BI authoring hub — Datasets, Query Library, Viz Library / Widget Builder, Dashboard Builder — with real persistence via the widened component store.
resource: inspecto-ui/src/app/modules/admin/studio/
tags: [feature, studio, bi, dataset, query, widget, dashboard]
timestamp: 2026-07-07T00:00:00Z
---

# Studio

The Builder surface for BI authoring under `/studio`. Vocabulary is Type→Instance throughout
([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Visualization Type** is the template; a **Widget** is the
configured instance bound to a Dataset's Result Set; a **Dashboard** is a layout of Widgets.

* **Panes** — **Datasets** (define Tables/Derived Tables/Views the BI layer binds to), the
  **Query Library** (`/studio/queries` — author SQL + `$`-Parameters, preview the Result Set offline),
  the **Viz Library** (searchable Widget gallery) with the **Widget Builder**, and the
  **Dashboard Builder** (quick-filter bar, drill-through drawer, time grain, PNG export). The
  investigation studios live alongside: [Geo Map Analysis](geo-map.md) and [Link Analysis](link-analysis.md).
* **Visualization Types** come from the `VizPlugin` registry (`src/app/inspecto/viz/`) — charts, tables,
  scatter, funnel, …; **Measures** (never "metrics" in the BI sense) drive aggregations in Explore.
* **Persistence is real** — datasets/widgets/dashboards/queries are writable component kinds since W3/W4
  (`/components` + ETag/If-Match; [backend registry](../../backend/components/component-registry.md));
  query execution runs on DuckDB via [`POST /queries/{id}/run`](../../backend/control-plane/queries.md).
  Offline, the same surface runs against the mock store ([mock backends](../conventions/mock-backends.md)).
* **Widgets are library citizens** — identity + tags, the browsable Viz Library gallery, a standalone
  `WidgetHost` render path, and one shared `DatasetResultService` result layer: live it runs
  `POST /bi/query` (DuckDB), offline the same specs run byte-identically on AlaSQL; unmappable specs
  (named-Measure SQL, OR filters) fail honestly. Sharing/RBAC stays gated on the security module.
* **Forms** follow ask-the-minimum + `uniqueNameValidator` on create
  ([forms & state](../conventions/forms-and-state.md)).

Design of record (archived):
[`report-builder-design.md`](../../../archived-documents/plans-archive/report-builder-design.md) ·
[`widget-library-spec.md`](../../../archived-documents/plans-archive/widget-library-spec.md) ·
[`studio-implementation-plan.md`](../../../archived-documents/plans-archive/studio-implementation-plan.md) ·
[`studio-bi-improvements-plan.md`](../../../archived-documents/plans-archive/studio-bi-improvements-plan.md).
