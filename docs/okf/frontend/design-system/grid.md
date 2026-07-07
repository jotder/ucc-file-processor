---
type: Module
title: Grid (ag-Grid theme & helpers)
description: The ag-Grid 35 gamma theme plus shared column/cell/overlay helpers used inside the data-table.
resource: inspecto-ui/src/app/inspecto/grid/index.ts
tags: [design-system, ag-grid, grid, theme]
timestamp: 2026-06-28T00:00:00Z
---

# Grid

`app/inspecto/grid` is the low-level ag-Grid 35 layer the [data-table](data-table.md) builds on. It is rarely
used directly now (the data-table consolidates grid hosts), but it owns the theme and helpers:

* `InspectoGridThemeService.theme()` — a `themeQuartz` theme wired to the gamma `--gamma-*` tokens (light/dark/auto via `GammaConfigService`). The **only** sanctioned grid theme — never bare `themeQuartz`.
* `INSPECTO_DEFAULT_COL_DEF` — `{ sortable: true, resizable: true }`.
* `actionsColumn(actions)` + `InspectoActionsCell` / `InspectoRowAction` — per-row icon-button column; `refreshActionsCells($event)` on `(firstDataRendered)` + `(rowDataUpdated)` or icons don't render.
* `autoColumns(rows)` — derive `ColDef[]` from row keys. `fmtDateTime(value)` — date column formatter. `noRowsOverlay(title, hint)` — empty overlay (HTML-escaped, gamma-themed).

## Gotchas

* Bind **both** `(firstDataRendered)` and `(rowDataUpdated)` → `refreshActions` or action icons don't render.
* Static `rowData` present at first render also skips **string-returning** cell renderers — force `api.refreshCells({force:true, columns:[…]})`.

See [data-table](data-table.md) for how these are composed into the tiered component.
