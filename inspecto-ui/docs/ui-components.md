# Inspecto UI — shared components & conventions

The reusable Inspecto layer lives in `src/app/inspecto/`. Screens under
`src/app/modules/admin/` should compose these pieces rather than re-implement them.

## Design tokens

| Layer | Where | Use |
|-------|-------|-----|
| Tailwind utilities | `tailwind.config.js` + `src/@gamma/tailwind/` | All template/SCSS styling. Semantic classes: `text-default`, `text-secondary`, `text-hint`, `bg-card`. No arbitrary values (`w-[…]`) — extend the spacing scale instead. |
| CSS custom properties | `--gamma-*` / `--gamma-*-rgb`, emitted per theme by `src/@gamma/tailwind/plugins/theming.js` | SCSS that needs theme-aware colors, e.g. `rgba(var(--gamma-text-secondary-rgb), 1)`. |
| Canvas tokens | `src/app/inspecto/theme/chart-tokens.ts` | The **only** place hex colors may be hardcoded. Chart.js and AntV G6 paint to canvas and can't resolve CSS variables, so the Tailwind values are pinned there: `CHART_SERIES` (semantic series colors), `NODE_KIND_COLORS` (catalog legend), `canvasTheme(dark)` (text/grid/edge/surface per scheme). |

## Components

### `<inspecto-chart>` — `inspecto/components/chart.component.ts`
Theme-aware Chart.js host. Inputs: `type` (ChartType), `data` (ChartData), `options`
(ChartOptions, merged). Recreates the chart on data change and on light/dark flips
(via `GammaConfigService`, including `auto`). Dataset colors: use `CHART_SERIES`.

### `<inspecto-graph-view>` — `modules/admin/catalog/graph-view.component.ts`
Read-only AntV G6 host (dagre layout, pan/zoom). Inputs: `data` (`G6GraphData` from
`catalog-graph.ts` mappers). Output: `nodeClick(id)`. Node colors come from
`NODE_KIND_COLORS`; scheme colors from `canvasTheme`.

### `<inspecto-empty-state>` — `inspecto/components/empty-state.component.ts`
Standard "nothing here" panel. Inputs: `message` (required), `title`, `icon`
(default inbox), `actionLabel`. Output: `action`. Use instead of ad-hoc dashed
boxes or ag-Grid's default "No Rows" overlay when a screen-level state applies.

### `<inspecto-actions-cell>` + grid kit — `inspecto/grid/index.ts`
Every grid uses the shared kit:

- `INSPECTO_DEFAULT_COL_DEF` — sortable/resizable defaults; bind `[defaultColDef]`.
- `InspectoGridThemeService` — scheme-reactive ag-Grid theme; bind `[theme]="themeSvc.theme()"`.
- `actionsColumn(actions)` / `InspectoActionsCell` — mat-icon row actions with per-row
  `visible` / `disabled` predicates (`InspectoRowAction[]`).
- `autoColumns(rows)` — derive columns from row keys; `fmtDateTime` — epoch/ISO formatter;
  `refreshActionsCells(api)` — re-render workaround after row mutations.

### Assist — `inspecto/components/assist-panel.component.ts` / `assist.dialog.ts`
`<app-assist-panel>` renders any assist intent result (SQL/sample/draft/findings);
`AssistDialog` is the MatDialog host. Re-key the panel when the intent changes.

## Patterns

- **Toasts**: `ngx-toastr`, configured globally in `app.config.ts` (bottom-right).
  `toastr.error(...)` on failed actions; keep messages actionable.
- **Dialogs**: standalone components in `*.dialog.ts` files, opened directly with
  `MatDialog`. Confirmations go through `InspectoConfirmService`.
- **Auth/API**: services in `inspecto/api/`; guard Inspecto routes with
  `inspectoAuthGuard`; interceptors add `X-Api-Token` and bounce 401 → `/connect`.
- **Forms**: spec-driven dynamic forms (see `config` screen); Material fields use
  the `gamma-mat-dense` class.

## Naming

- Selectors: `inspecto-` for the shared Inspecto layer, `app-` for feature screens,
  `gamma-` for template components (don't edit `src/@gamma/`).
- Files: kebab-case `*.component.ts` / `*.dialog.ts` / `*.service.ts` / `*.types.ts`.
