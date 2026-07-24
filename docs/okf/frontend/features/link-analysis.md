---
type: Feature
title: Link Analysis
description: The graph investigation studio — Entity Projection over Datasets rendered on the shared G6 host, with layout/algorithm toolboxes and saved Link-Analysis Views.
resource: inspecto-ui/src/app/modules/admin/studio/link-analysis/
tags: [feature, studio, graph, entity, link, g6, investigation]
timestamp: 2026-07-07T00:00:00Z
---

# Link Analysis

The Builder-lens studio at `/studio/link-analysis` for graph investigation. Keep the four graph planes
distinct ([`GLOSSARY.md`](../../../GLOSSARY.md) §11): this studio works on **P3 — Entity/Link graphs**
(records as business entities), never on artifact/lineage graphs.

* **Sources** — a **GraphSource** feeds one renderer through one query seam; the P3 source is
  **Entity Projection**: a mapping (not a store) that folds a Dataset's rows into Entities + Links
  (column → source/target Entity, optional columns → Link type/attributes).
* **Rendering** — the shared G6 host (`src/app/inspecto/graph/`), reused by the Catalog graph and the
  Geo co-location bridge. Nodes are canvas-drawn — verify inspector logic in unit tests, not preview clicks.
* **Toolboxes** — Layout (11 G6 layouts; tree shapes gated to acyclic data) and Algorithm, plus
  paths/neighborhood/centrality analysis. The **V2 algorithm depth** (2026-07-24) lives in the pure,
  framework-free `inspecto/graph/graph-analysis.ts` library (the extension seam — a new algorithm is a
  pure `(g: G6GraphData, …) ⇒ result` drop-in) and is surfaced as accordion groups in
  `link-analysis-toolbox.component`:
  * *Advanced traversal* — `weightedShortestPath` (Dijkstra by tie strength, `edgeWeight` = folded
    count), `findCycles` (canonicalized directed cycles), `articulationPoints`/`bridges` (Tarjan),
    `egoNetwork`.
  * *Algorithm library* — `pageRank`, closeness/eigenvector/katz centrality, `hits`, `kCore`,
    `triangleCount`, `cliques` (Bron–Kerbosch), `maxFlow`+min-cut (Edmonds–Karp),
    `maximumSpanningForest`, `jaccardSimilarity`, `linkPrediction`.
  * *Suspicion scoring* — `suspicionScore`, an explainable 0–100 composite (degree/betweenness/
    PageRank/k-core/triangles) with a per-node factor breakdown; the toolbox highlights the top decile.
  * *Pattern packs* — a picker (`pattern-packs.ts`) that pre-fills the motif builder from parameterized
    starter templates (layering chain, pass-through, inbound collector, forwarding relay, circular flow,
    shared associates); packs whose shape isn't a path motif hint at the fitter tool (cycles/similarity).
  Guarded by `ANALYSIS_NODE_CAP` (2000) where super-linear; 53 pure unit tests + 11 toolbox specs.
* **Saved investigations** — a **Link-Analysis View** (Component kind `link-analysis-view`) via the
  shared `inspecto/investigation` lib; when its source is `entity-projection` it is renderable as a
  **Widget** (a Graph Visualization Type bound to a Dataset).
* **Status** — UI shipped mock-first; the backend Entity Projection over real Datasets shipped
  (REQUIREMENTS INV-1, `POST /inv/projection`), including the full V1 slice (multi-mapping, multi-root,
  incremental expand, SVG/GraphML export, undo/redo, `attrCols` — the last is fully implemented both
  backend (`InvRoutes`) and UI (`entity-projection.ts`), not open despite an earlier stale note here).
  **2026-07-20 shipped the schema-relationship model**, §7's other deferred half: `GET
  /inv/schema/relationships` infers naming-convention FK suggestions across Datasets (`<base>_id` column
  → a Dataset named `<base>`, linked to its `id` column or a same-named column), so the Studio can
  pre-fill multi-mapping projections instead of requiring every column pair hand-picked. Self-references
  (e.g. `manager_id`) are included; unusable Datasets are skipped, not fatal.
  **2026-07-24 shipped four V2 tracks** (see Toolboxes above): advanced traversal, the algorithm
  library, suspicion scoring, and pattern packs. **2026-07-24 also shipped the timeline**: a pure
  `filterByTime(g, attrCol, cutoff)` in `graph-analysis.ts` (edges only — an edge survives only when its
  `attrs[attrCol]` parses as a date on or before the cutoff; nodes are untouched, same non-mutating
  contract as `filterByKinds`) plus a toolbar "Timeline" menu (column picker over every `attrs` key seen
  in the loaded graph + a `mat-slider` cutoff, rail bounds from that column's parseable date extent) in
  `link-analysis.component`. It slots into the existing filter pipeline — kind-filter → time-filter →
  `collapseBranches` → the shared `displayed()` graph-view binding — so no new filtering mechanism was
  needed; resets on a fresh query and via "Clear search & filters", and participates in undo/redo like
  the other presentation filters. Remaining V2 (BACKLOG): **collaboration** — of which **version history + sharing are
  frontend-only wiring** (backend `/components/{type}/{id}/versions` + `restore` and RBAC component
  shares already exist; `ComponentsService.versions/restore` are wired), while **per-view comments need a
  new backend** (no Component-attached note model — `ObjectNote` is keyed to Incidents/Cases, so a
  saved-view comment path would re-key that model by component `type`+`id`).
* **Investigation pivot** (ui-design-review R8, 2026-07-20) — a node resolving an `objectRef` offers
  "View on map" (pivots to Geo Map Analysis with the same record); see
  [Investigation Pivot](investigation-pivot.md) for the shared contract.

Design (archived):
[`link-analysis-and-graphsource.md`](../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
§7 (schema-relationship model, now shipped) ·
plans: [`link-analysis-studio-plan.md`](../../../archived-documents/plans-archive/link-analysis-studio-plan.md)
(§6–7, V1 now fully shipped; V2+ remains open backlog),
[`link-analysis-toolboxes-plan.md`](../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).
