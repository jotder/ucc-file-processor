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

## Data Browser

The per-space raw table browser (`modules/admin/data-browser/`, nav item under Catalog): store/table
list (left, resizable via `InspectoSplitDirective`) + column schema, paginated/sorted rows in an
`<inspecto-data-table>`, and an ad-hoc read-only SQL console — over both the business Parquet stores and
(when DB-backed) the operational tables via the backend's `/db/catalog|table|query` routes. Ops-table
reads go through each store's own live connection (`BrowsableStore` — DuckDB files are single-writer);
SQL is `SqlGuard`-checked server-side. Offline via the `db-browser` mock handler.
