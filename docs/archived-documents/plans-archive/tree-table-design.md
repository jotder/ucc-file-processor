# Aligned Tree-Table — design

> A generic **aligned tree-grid** for inspecto-ui: a left column that is an expandable, indented
> hierarchy, and aligned value columns on the right. One component, driven by configuration + pluggable
> cell renderers, so it serves **breakdown-by-dimensions, hierarchical comparison, N-way reconciliation,
> and (lazy) file-browser** by config rather than bespoke screens.

## Why not ag-Grid grouping

The project ships **ag-Grid Community only** — `treeData` / `rowGroup` / `masterDetail` /
`autoGroupColumnDef` are Enterprise and unlicensed (`grid/index.ts` registers `AllCommunityModule`). So
the tree is **hand-built**: flatten the forest into visible rows (honoring expand state) and render the
first column with a custom Angular cell renderer (indent + chevron + label). This mirrors how every other
"tree" in the app is hand-rolled (`connection-tree`, `parser-tree`, `menu-tree-node`).

## Model

```ts
interface TreeNode {
  id: string;                          // unique across the forest (row id)
  label: string;                       // shown in the tree column
  values?: Record<string, unknown>;    // aligned right-column values (field → value)
  children?: TreeNode[];
  icon?: string;                       // optional leading heroicon (data-type/status)
  expanded?: boolean;                  // initial-state hint
}
```

Flattening → `FlatTreeRow { __id, __depth, __hasChildren, __expanded, __label, __icon, ...values }`.
Expand state is a **parent-owned `Set<id>`** of expanded nodes (borrowed from `connection-tree`); a child
row is emitted only while all ancestors are expanded. `seedExpanded(nodes, depth)` seeds it from
`groupDefaultExpanded` (+ `node.expanded` hints); the component holds it in a `linkedSignal` whose
computation **keeps the previous expanded set** once one exists — so user toggles survive `nodes`
refreshes (hosts rebuild the forest on cell edits — the Access matrix — or after row actions —
reconciliation Resolve); only the first data delivery seeds. Vanished ids are inert in the set.

## Component — `<inspecto-tree-table>` (`app/inspecto/tree-table/`)

A sibling of `<inspecto-data-table>`, reusing the shared grid foundation verbatim
(`InspectoGridThemeService`, `INSPECTO_DEFAULT_COL_DEF`, `refreshAllCells`, `noRowsOverlay`,
`actionsColumn`). Standalone, `OnPush`, signals.

| Input | Purpose |
|---|---|
| `nodes: TreeNode[]` | the forest |
| `columns: ColDef[]` | the right value columns (ordinary ColDefs — use `statusBadgeHtml`, `varianceCell`, etc. as `cellRenderer`) |
| `treeHeader` / `treeMinWidth` | the synthesized tree column's header + width |
| `groupDefaultExpanded` | depth auto-expanded on load (`-1` = all) |
| `rowActions: InspectoRowAction[]` / `pinActions` | action-icon column (reuses `actionsColumn`) |
| `multiSelect` / `singleSelect` | ag-Grid row-selection checkboxes (the case/incident pattern) |
| `loading` / `height` / `autoHeight` / `noRowsTitle` / `noRowsHint` | mirror the data-table |

Outputs: `nodeClick`, `selectionChange`, `expandedChange`. Toolbar: **Expand all / Collapse all** + CSV
export (flattened rows, indented label). The **tree column** is synthesized as the first ColDef with
`cellRenderer: TreeGroupCell` and `cellRendererParams.toggle`; value columns follow; an actions column is
appended when `rowActions` is set. Grid sorting is off (it would break hierarchy order).

### Cell plugins (the extensibility the user asked for)

- **Tree/group cell** — `TreeGroupCell implements ICellRendererAngularComp` (indent = `0.25 + depth*1.1`
  rem, chevron `heroicons_outline:chevron-down|right`, optional leading `mat-icon`, label). The one place
  an interactive/icon cell is needed → an Angular-component renderer (only viable path per §4 of the map).
- **Action icons** — `[rowActions]` → `actionsColumn` + `InspectoActionsCell` (already the app's action idiom).
- **Checkbox** — `[multiSelect]` → ag-Grid multiRow selection (auto-rendered checkbox column).
- **Status/badge** — value columns set `cellRenderer: (p) => statusBadgeHtml(p.value)` (string renderer).
- **Variance (Δ)** — `varianceCell()` helper (string renderer): signed number + ▲/▼ + text-tone emphasis
  (text-* tones are allowed by the token guard; no status-tinted fills). For reconciliation, feed the
  signed `diff` from `reconciliation-types` into a Δ column.
- **Data-type icon** — `node.icon` (a heroicon) renders in the group cell; per-value icon columns can use
  a small Angular renderer later if needed (string renderers can't host `mat-icon`).

## Use-case mapping (all by configuration)

- **Breakdown by dimensions** — nodes = dimension hierarchy (Region ▸ Product ▸ SKU); value columns =
  measures; parents carry rollups.
- **Hierarchical comparison / multi-entry** — one value column per entry + a `varianceCell` Δ column.
- **N-way reconciliation** — one column per source + a Δ/status column driven by `reconciliation-types`
  (`withinTolerance`, signed `diff`); `node.icon`/a status badge flags matches vs breaks.
- **File browser** — nodes = paths; `values` = size/modified/type; lazy children by fetching on expand
  (a `(expandedChange)` host hook) — the split/lazy variant layered on the same component later.

## Testing / DoD

Pure `flattenTree`/`seedExpanded`/`varianceCell` unit tests (fixtures, no grid). Component spec: builds,
toggles expand (row count changes), a11y on the rendered grid (ag-Grid instantiates in jsdom for
Community). Live demo in the `/design` gallery (a breakdown + a reconciliation-style Δ example). Then the
angular-ui DoD: `lint:tokens` · `build` · `test:ci` · preview.
