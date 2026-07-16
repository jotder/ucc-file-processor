---
type: Component
title: Tree Table (aligned tree-grid)
description: <inspecto-tree-table> — an expandable indented hierarchy column plus aligned value columns, hand-built over ag-Grid Community.
resource: inspecto-ui/src/app/inspecto/tree-table/tree-table.component.ts
tags: [design-system, tree-table, ag-grid, hierarchy, reconciliation]
timestamp: 2026-07-16T00:00:00Z
---

# Tree Table

`<inspecto-tree-table>` is the generic **aligned tree-grid**: a left column that is an expandable,
indented hierarchy and aligned value columns on the right — one component driven by configuration +
pluggable cell renderers (breakdown-by-dimensions, hierarchical comparison, N-way reconciliation,
lazy file-browser).

* **Why hand-built**: the app ships **ag-Grid Community only** — `treeData`/`rowGroup`/`masterDetail`
  are Enterprise. So the forest is flattened into visible rows (honoring expand state) and the first
  column renders via an Angular `TreeGroupCell` (indent + chevron + optional icon).
* **Model**: `TreeNode { id, label, values?, children?, icon?, expanded? }` — pure `flattenTree`/
  `seedExpanded` helpers are unit-testable without the grid.
* **Cell plugins**: status-badge string renderers, `[rowActions]` → `InspectoActionsCell`,
  `[multiSelect]` checkboxes, and `varianceCell()` (signed Δ + ▲/▼, text-tone only — no status-tinted
  fills, per the token guard).
* **Expand state survives node refreshes** (keyed by node id), and `stateKey` persists grid state like
  the [data-table](data-table.md).
* **Hosts**: reconciliation detail + [Reconciliation Board](../features/reconciliation.md), the
  Settings ▸ Access lens matrix, and the `/design` gallery demo.

As-built design (archived):
[`tree-table-design.md`](../../../archived-documents/plans-archive/tree-table-design.md).
