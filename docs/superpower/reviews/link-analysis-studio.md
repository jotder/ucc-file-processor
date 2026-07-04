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

## R8 verification (2026-07-04)
- `lint:tokens` ✓ · prod `build` ✓ (lazy `link-analysis-routes` chunk 30 kB) · `test:ci` **737 / 0 / 5**
  (+30 specs: 6 source-contract, 15 analysis, 6 projection, 5 pane incl. axe + duplicate-guard + failing-source
  degradation). One real bug caught by the tests: LPA's smallest-label tie-break flooded across bridge edges and
  merged clusters — fixed to strict-improvement switching + a deterministic singleton-absorb pass (oscillating
  2-node components).
- **Live smoke** (:4204): pane renders (h1, query form, saved-views rail, empty state) → entity projection over
  the seeded `cdr_sample` Dataset (msisdn → cell_id, kind = tariff) → 8 entities / 5 typed links, canvas mounted;
  shortest path, degree ranking (CELL-101 top, score 2), 3 communities (3/3/2), explain text («CELL-101 (entity) —
  ← premium: …»), emphasis set; view "Subscriber-cell map" saved → **survives a full reload** and re-runs from
  localStorage; 0 console errors.
