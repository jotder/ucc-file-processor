---
type: Component
title: Data Table (tiered family)
description: One <inspecto-data-table [tier]> consolidating every grid host — mini/standard/pro/proMax with an offline SQL editor.
resource: inspecto-ui/src/app/inspecto/data-table/data-table.component.ts
tags: [design-system, data-table, ag-grid, sql, codemirror, alasql, tiers]
timestamp: 2026-06-28T00:00:00Z
---

# Data Table

`<inspecto-data-table [tier]>` is the single tiered component that consolidates **every** ag-Grid surface in
the app (~14 hosts migrated onto it). The component is a thin shell; reusable logic is framework-free under
`data-table/core/` and `data-table/sql/`, plus the [query](query.md) and [rule](rule.md) modules.

## Tiers (the "mobile version" analogy)

| Tier | Adds |
|---|---|
| **mini** | the themed grid only (rows · columns · empty · loading · row actions · single-select). |
| **standard** | + an **icon-only toolbar**: column chooser · search · CSV export. |
| **pro** | + an always-on **CodeMirror SQL editor** that runs real SQL offline (AlaSQL) and re-renders the grid, + an icon-toggled **filter builder** that regenerates the SQL. |
| **proMax** | + **save as rule** — a parameterized `:fieldValue` template (see [rule](rule.md)). |

## Column headers

Every column header carries an always-visible **sort glyph** (`unSortIcon` — neutral when unsorted) and a
**filter funnel** that opens a text search/filter popup. There is no floating-filter row.

## Pro SQL editor (`data-table/sql/`)

* `runSql(sql, source, rows)` — **lazy-imports AlaSQL** (dynamic `import()`, kept out of the main bundle) and runs the query in-browser. `toAlaSqlDialect` rewrites DuckDB double-quoted identifiers → AlaSQL backticks (string literals preserved). Errors are returned, not thrown.
* `SqlHistoryService` — per-source recent + favorites in `localStorage`; recent is appended **only on a successful run** (erroneous SQL is neither rendered nor recorded).
* `codemirror-setup.ts` — CM6 SQL extensions themed entirely with `--gamma-*` vars (guard-clean; 3 syntax colors). `sql-codemirror.component` is the wrapper (with a `syncing` guard against echo loops); `sql-editor.component` adds the history menu, favorite star, and Run button.
* The editor is **`@defer`-loaded**, so mini/standard hosts never pull CodeMirror. The builder's generated SQL seeds the editor one-way (`linkedSignal`); Run executes the editor draft.

## Public API (selected)

Inputs: `tier`, `rows`, `columns?` (explicit `ColDef[]`), `rowActions?`, `loading`, `pageSize`, `height`,
`autoHeight`, `singleSelect`, `noRowsTitle`/`noRowsHint`, `exportName`, `sourceName` (SQL `FROM` + rule id
seed), and per-capability overrides `searchable`/`exportable`/`queryable`/`savable`. Outputs: `(rowClick)`,
`(ruleSaved)`.

* **`stateKey`** (also on [tree-table](tree-table.md)) opts a host into grid-state persistence — column
  order/width/visibility + sort survive reloads per key (~8 hosts opted in).
* **Honest high-volume loading**: opt-in `[serverPage]` + `[hasMore]` + `(loadMore)` renders a
  "showing N — Load more" strip (mirrors `[serverRun]`); the host fetches the NEXT page
  (`?limit=<pageSize>&offset=<rows loaded>`) and appends it — true offset paging (R6, 2026-07-19; no
  refetch from 0). Adopted by object-mail, audit-logs, events — never silently cap a list. Full
  refetches (filter change, refresh, live-tail tick) reset to page 0; `hasMore` re-derives from
  `page.length >= pageSize`. The mock handlers (`ops.handler.ts` `pageSlice`) mirror the backend's
  `offset` semantics so offline paging behaves identically.
* **Keyboard layer** (document-level, review R3): **`/`** opens + focuses the first visible searchable
  table's quick filter; opt-in **`[keyNav]`** gives **j/k** row focus, **Enter** = `(rowClick)` (opens
  the host's detail), **x** = toggle selection — piloted on the incidents/cases mail list. Typed input
  and open overlays are exempt; arrow keys stay ag-Grid-native. Bindings are listed in the `?`
  shortcuts overlay.

## Tier assignments across hosts

* **pro**: [events](../features/events.md), [alerts](../features/alerts.md), [objects](../features/objects.md), [enrichment](../features/enrichment.md) detail.
* **standard**: [diagnoses](../features/diagnoses.md), [runs](../features/runs.md), [sources](../features/sources.md), [jobs](../features/jobs.md), [catalog](../features/catalog.md), [run-detail](../features/run-detail.md).
* **mini / single-select**: enrichment-jobs grid, batch-detail & node-detail dialogs.

# Examples

```html
<inspecto-data-table
  [tier]="'pro'"
  [rows]="rows"
  [columns]="columnDefs"
  [rowActions]="actions"
  sourceName="events"
  (rowClick)="open($event)"
  (ruleSaved)="onRuleSaved($event)" />  <!-- proMax -->
```

New deps (lazy, justified): `alasql` (+ `allowedCommonJsDependencies`), `codemirror` + `@codemirror/*` + `@lezer/highlight`.

The rule-builder north-star design is archived at
[`rule-builder-design.md`](../../../archived-documents/plans-archive/rule-builder-design.md) (Query Core shipped; the
aggregation builder and backend rule save remain unbuilt).
