---
type: Feature
title: Catalog
description: The data catalog — tables and KPIs in grids plus a relationship graph view.
resource: inspecto-ui/src/app/modules/admin/catalog/catalog.routes.ts
tags: [feature, catalog, acquisition, graph]
timestamp: 2026-06-28T00:00:00Z
---

# Catalog

Route `/catalog` (Acquisition nav group). Browses catalog tables and KPIs in **standard**
[data-tables](../design-system/data-table.md) (row-click opens a node-detail dialog), plus a **graph** tab
rendered with AntV G6 (read-only — rebuilds on data/scheme change; not a data-table). Backed by
`CatalogService`.
