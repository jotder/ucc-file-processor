# Design System

Shared, reusable UI under `src/app/inspecto/`. **Never re-roll** a status pill, inline alert/banner, empty
state, skeleton, grid theme, confirm dialog, or connectivity banner — reuse these. The living gallery is the
in-app [`/design` route](../features/design-system-gallery.md).

# Presentational components

* [Status badge](status-badge.md) - the only place status/severity/level → color. `<inspecto-status-badge>` / `statusBadgeHtml()`.
* [Alert](alert.md) - inline per-screen notice banner (`<inspecto-alert variant=…>`).
* [Empty state](empty-state.md) - `<inspecto-empty-state>` for "nothing here yet".
* [Skeleton](skeleton.md) - `<inspecto-skeleton>` loading placeholders.
* [Connectivity banner](connectivity-banner.md) - the app-wide offline/backend-down strip (mounted in the layout).
* [Chart](chart.md) - the Chart.js wrapper; canvas colors come from `chart-tokens.ts`.

# Modules

* [Grid](grid.md) - the ag-Grid 35 theme + cell/column helpers.
* [Data table](data-table.md) - the tiered `<inspecto-data-table [tier]>` family (mini/standard/pro/proMax) that consolidates every grid host.
* [Query](query.md) - the framework-free query AST: compile-to-SQL, in-browser eval, the filter-builder component.
* [Rule](rule.md) - Pro Max rule templates (parameterized `:fieldValue` binds) + the save dialog.
* [Tree table](tree-table.md) - the expandable tree-grid primitive (hosts: Access matrix, catalog lineage; keeps expand state across refreshes).
