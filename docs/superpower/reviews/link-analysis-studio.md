# C5 — Link Analysis Studio (P3) — SHIPPED (MVP)

**Date:** 2026-07-04 · **Pane:** `modules/admin/studio/link-analysis/` (`/studio/link-analysis`, Studio group) · **Lens:** Builder authoring; investigation available in every lens

Plan: [`../link-analysis-studio-plan.md`](../link-analysis-studio-plan.md) (feature spec + P0–P4 + MoSCoW release phasing §6). Design: [`../link-analysis-and-graphsource.md`](../link-analysis-and-graphsource.md).

## Owner decisions (AskUserQuestion, 2026-07-04)
1. **Scope: FULL** — include the business Entity/Link graph (`entity-projection` over Datasets, mock-backed), not just the system planes. The design doc's "P3 reserved, not built" is superseded for the frontend; the backend projection stays open.
2. **Analysis ops:** all four (path · neighborhood/explain · centrality · communities) **plus** node search / kind filtering / result highlighting.
3. MoSCoW catalog supplied by owner → sliced MVP / V1 / V2 / Backlog in the plan §6; this sheet is the MVP.

## What shipped

### P0 — the GraphSource seam (behavior-preserving)
- `inspecto/graph/graph-source.ts` (types only, framework-free): `GraphSourceId` (the four §11 planes), `GraphSourceQuery` (the lineage `GraphQuery` generalized + `counts` + `projection`), `EntityProjection`, `GraphSource`.
- `studio/link-analysis/graph-sources.ts`: `LineageGraphSource` (wraps `CatalogService.graph` → `toG6Data`), `ComponentRegistryGraphSource` (`ComponentsDataProvider` over `REGISTRY_KINDS` → `deriveComponentGraph`; a failing kind degrades), `PipelineGraphSource` (renamed from the design doc's `FlowGraphSource` per GLOSSARY; wraps `pipelines.graph` → `toPipelineG6Data`, `counts:true` folds the latest provenance batch) + root `GraphSourcesService`. **Contract tests prove each `query()` deep-equal to its existing mapper** — no mapper logic moved. Existing screens untouched (opt-in later).
- GLOSSARY §11: added **Entity Projection**, **Link Analysis Studio**, Link-Analysis View; P3 row no longer "deferred".

### P1 — pure analysis library (`inspecto/graph/graph-analysis.ts`)
`shortestPath` (BFS, directional) · `allPaths` (simple paths, limit+hop capped) · `neighborhood` + `explainNode` (text summary by edge kind/direction) · `degreeCentrality` · `betweennessCentrality` (Brandes, `ANALYSIS_NODE_CAP` 2000 guard) · `detectCommunities` (deterministic label propagation, smallest-member community ids) · `connectedComponents` · `searchNodes` · `filterByKinds` (edges survive only with both endpoints). All pure over `G6GraphData`; barrel-exported.

### P2 — entity projection (`studio/link-analysis/entity-projection.ts`)
- `projectEntities(rows, mapping)`: distinct source/target values → Entities, rows → Links deduped per (source,target,kind) with folded `count` label; blank endpoints skipped; bad mapping ⇒ **typed `ProjectionError`** (not a throw); `PROJECTION_NODE_CAP` 500 with a `truncated` flag.
- `EntityProjectionGraphSource`: Dataset by id (`DatasetsService`) → rows via the **same offline seam the editor uses** (`evaluateRows`/`inferColumns` over `SAMPLE_SOURCES`) → fold. No new mock endpoints; the W5 Link-Analysis template's seeded `links`/`entities` sample sources demo it out of the box.

### P3 — the pane
- Route `/studio/link-analysis` (lazy, nested under `studio.routes.ts`) + Studio nav item (`share` icon).
- **Three-zone layout:** left = source select + per-source query controls (Dataset + column mapping selects / lineage root+depth+direction / pipeline select + counts toggle) + saved views; center = existing `GraphViewComponent` with search box, per-kind checkboxes, clear-filters, node/link counts, empty-state/skeleton/`inspecto-alert` states; right = analysis rail (Path / Explain / Centrality / Communities toggles), result lists (hops, explain text, top-20 ranking table, community list), click ⇒ focus.
- **`GraphEmphasis` seam on the shared G6 host** (`graph-view.component.ts`): optional `@Input emphasis {nodeIds, edgeIds?, groups?}` — non-listed elements dim (0.25/0.2 opacity), `groups` colour per community via `ICON_COLOR_SWATCHES`. No renderer change otherwise; existing callers unaffected (input optional).
- **Saved views:** `LinkAnalysisView {sourceId, query}` persisted as the **`link-analysis-view` component kind** (`link-analysis.service.ts`; `ComponentType` union + mock `STUDIO_KINDS` widened). Ask-the-minimum save form (name + optional description) with the inline duplicate-name guard (**12th pane**); load re-runs the persisted query. Mock-only persistence — the backend `ComponentStore` enum stays closed (known constraint).

### Export
PNG (via a new `GraphViewComponent.exportPng()` — G6 `toDataURL`) + JSON (the displayed `G6GraphData`) from the canvas toolbar.

## Deliberate scope cuts (in the plan §6 for V1)
Multi-mapping (multiple entity types) · incremental expansion · all-paths surfacing in the UI (`allPaths` is implemented and tested, not yet surfaced) · collapse/pin/hide/minimap/fullscreen · property/time filters · layout+analysis persisted with the view · Widget/dashboard binding · SVG/GraphML/report export.

## Workspace refinement (2026-07-04, same day — canvas-first pass)
Owner ask: bigger canvas, room for many controls (tool/toolbox/tool-group), collapsible panes, smart forms
that collapse to a selected-values status with an edit affordance, an icon per control.
- **Full-height studio layout** (`h-full min-h-0` flex, pattern of the pipeline editor): the canvas now grows
  into all remaining space; `GraphViewComponent` gains an opt-in `fill` input (default byte-identical `62vh`
  for the 4 existing hosts) + a `ResizeObserver` → `graph.resize()` so collapsing a pane resizes the canvas live.
- **Both rails collapse to an 11-wide icon strip** (chevron-double toggles): left strip = query
  (`adjustments-horizontal`) + saved views (`folder-open`); right strip = one icon per analysis tool. Clicking a
  strip icon expands the pane straight onto that tool (`openTool`).
- **Smart query form:** auto-collapses after a successful run to a selected-values summary (icon · label ·
  value rows + Re-run); a failing run reopens it. A **top status bar** over the canvas shows the active query as
  chips with a pencil (`editQuery`) — works even with the left pane collapsed. `lastRun`/`querySummary` signals.
- **Analysis rail → accordion toolbox:** the four tools are collapsible tool groups (icon + header + a result
  chip: `3 hops` / node label / `top 20` / `n found`), one open at a time (`tab: AnalysisTab | null`).
- **Canvas toolbar:** kind checkboxes moved into a `funnel` mat-menu (button tints primary when a filter is
  active), conditional `x-mark` clear, search + PNG/JSON exports; save-view form collapsed behind the bookmark.
- +3 pane specs (auto-collapse/edit-reopen, openTool/toggleTool + failure-keeps-form, header result chips).

## Example graphs for user testing (2026-07-04)
Four sample link tables at rising complexity (`SAMPLE_SOURCES.graph_{simple,moderate,mindmap,complex}`),
each seeded in the **default space** as a dataset + a pre-wired saved view (`default-space.seed.ts`;
`MOCK_STORE_KEY` → **v7** so existing browsers reseed). Testers open `/studio/link-analysis` and one-click
load under Saved views:
1. **Example 1 — Simple star** (6 nodes / 5 links, one type) — first contact.
2. **Example 2 — Two clusters** (11/13, 3 types; ring + chain + ONE bridge) — shortest path, type filter, 2 communities.
3. **Example 3 — Mind map** (20/19; "Data Quality" root → 5 branches → 14 leaves) — hierarchy layout, Explain node.
4. **Example 4 — Fraud network** (41/57, generated: 3 rings + shared devices + mule accounts → one cash-out
   hub + bridges + background chatter) — centrality hubs and community detection at scale.
Projection shapes pinned by a spec in `entity-projection.spec.ts` so seed edits can't silently break a view.

## Graph visualization options + data panel (2026-07-04, third pass)
Owner ask: bottom data table, rich canvas options (labels, per-type styling saved with views,
collapse/expand branches, hover/click details, fit, fullscreen).
- **Bottom collapsible data panel** under the canvas: the displayed graph as an
  `<inspecto-data-table tier="standard">` (Links ⇄ Nodes toggle, search-narrowed to match the canvas,
  CSV export free from the tier); row click focuses the element. Query form stays in the left pane.
- **Display menu** (`paint-brush`, tints when customized): node/link label toggles + per-node-kind and
  per-relationship-kind colour swatches (`ICON_COLOR_SWATCHES`); **persisted as `display` on the saved
  view and re-applied on load** (`GraphDisplayOptions` on the shared host; edge kinds match on the base
  kind — `calls · 2` styles as `calls`).
- **Canvas tools:** fit-to-screen (`GraphViewComponent.fitView`), **fullscreen** for the whole studio
  (header) and for the canvas zone (toolbar) via the Fullscreen API, hover **tooltips** (G6 tooltip
  plugin: node label/kind/degree, edge kind + endpoints), node/edge **click → detail popup**
  (`element-detail.dialog.ts`: full rows + Focus + Collapse/Expand branch).
- **Collapse/expand branches:** pure `descendants`/`collapseBranches` in `graph-analysis.ts` (BFS on
  outgoing edges; roots stay visible); an "n collapsed" toolbar chip expands all.
- Shared-host additions are all opt-in (`display`/`tooltips` default off; the 4 existing hosts unchanged).
- +3 pane specs (collapse/expand, display save/load round-trip, table rows + search narrowing) and
  +2 analysis-lib specs.

## R8 verification (2026-07-04)
- Third pass re-verified: `lint:tokens` ✓ · prod `build` ✓ (no new warnings) · `test:ci` **744 / 0 / 5**
  (the only 2 failures were the two pre-known intermittent flakes — `simulator.spec.ts` stale-RUNNING and
  `widget.kind.spec.ts` registry isolation — not regressions). Live smoke: Example 4 (fraud network,
  41 nodes · 57 links) via saved-view load → data panel renders the graph in an ag-grid with Links ⇄ Nodes
  toggle, display menu shows label toggles + per-kind colour swatches, fit/fullscreen/PNG/JSON toolbar
  present, collapsed-query status chips over the canvas; 0 console errors.
- MVP pass: `lint:tokens` ✓ · prod `build` ✓ (lazy `link-analysis-routes` chunk 30 kB) · `test:ci` **737 / 0 / 5**
  (+30 specs: 6 source-contract, 15 analysis, 6 projection, 5 pane incl. axe + duplicate-guard + failing-source
  degradation). One real bug caught by the tests: LPA's smallest-label tie-break flooded across bridge edges and
  merged clusters — fixed to strict-improvement switching + a deterministic singleton-absorb pass (oscillating
  2-node components).
- **Live smoke** (:4204): pane renders (h1, query form, saved-views rail, empty state) → entity projection over
  the seeded `cdr_sample` Dataset (msisdn → cell_id, kind = tariff) → 8 entities / 5 typed links, canvas mounted;
  shortest path, degree ranking (CELL-101 top, score 2), 3 communities (3/3/2), explain text («CELL-101 (entity) —
  ← premium: …»), emphasis set; view "Subscriber-cell map" saved → **survives a full reload** and re-runs from
  localStorage; 0 console errors.
