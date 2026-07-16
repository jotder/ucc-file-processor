# C5 — Link Analysis Studio: feature spec + implementation plan

> Status: **planned** (2026-07-04, scope confirmed with owner). Builds on the approved design
> [`link-analysis-and-graphsource.md`](link-analysis-and-graphsource.md) (GraphSource seam, 2026-06-30).
> Track: frontend-mock-first ([`frontend-review-and-completion-plan.md`](frontend-review-and-completion-plan.md) §5, item C5;
> feature-matrix S2). Vocabulary: [`../GLOSSARY.md`](../GLOSSARY.md) §11 is binding.
>
> **Owner decisions (2026-07-04):** FULL scope — include the business **Entity/Link graph**
> (`entity-projection` over Datasets, mock-backed), not just system graphs. Analysis ops: **all four**
> (path, neighborhood/explain, centrality, community) **plus node search / kind filtering / result
> highlighting** on the canvas.

## 1. Feature spec

### What it is

A Builder-lens Studio pane (`/studio/link-analysis`) for **graph investigation** over both **system
metadata** (lineage, pipeline provenance, component reuse) and **business data** (records projected as
Entities/Links from a Dataset). The user:

1. **Picks a GraphSource** — `lineage` · `component-registry` · `provenance` · `entity-projection`.
2. **Shapes a GraphQuery** — root node, depth, direction, node/edge-kind filters; for
   `entity-projection`, a Dataset + column→entity/link mapping.
3. **Sees the result** in the existing `GraphViewComponent` (G6 v5/Dagre host — no new renderer).
4. **Runs analysis** — pure functions over the returned `G6GraphData`:
   - **Shortest path** between two picked nodes (highlight the path on canvas).
   - **Neighborhood / explain** — N-hop subgraph around a node + a text summary of its links.
   - **Centrality** — degree (+ betweenness) ranking, top-N table, size/em-highlight on canvas.
   - **Community detection** — label propagation; color-group nodes by community.
5. **Searches & filters** — node search box (jump/zoom to match), kind checkboxes, analysis-result
   highlighting; a results side panel lists path hops / neighbors / rankings / communities, click ⇒
   focus on canvas.
6. **Saves a view** — a saved Link-Analysis view is a **Component**; when the source is
   `entity-projection` it is a **Widget** (Graph Visualization Type bound to a Dataset), placeable on
   Dashboards later (placement itself is out of scope here).

### Why (positioning)

Feature-matrix S2 calls Entity/Link pivoting "the defining gap" for the **xDR-style investigation**
positioning: pivot records as business entities (caller→callee, account→device…) inside
investigations. System-graph analysis (which pipeline feeds what; which component is over-reused) is
the same UI for the Builder/Ops persona.

### UX shape (one screen, three zones)

- **Left rail — Query builder:** source select · per-source query controls (root autocomplete, depth
  slider, direction, kind chips) · for `entity-projection`: Dataset select + mapping form
  (`sourceEntity`, `targetEntity`, `linkKind?`, `linkAttrs?` — column selects from the Dataset's
  columns) · Run button. Ask-the-minimum: mapping needs only source+target columns.
- **Center — canvas:** existing `GraphViewComponent` (`@Input data: G6GraphData`), plus a thin
  overlay API for highlight/dim (see §3.4).
- **Right rail — Analysis:** tabs *Path / Explain / Centrality / Communities*, each with its picker,
  a result list, and canvas highlighting. Collapsed by default until a graph is loaded.
- Empty state (design-system `empty-state`) before the first run; skeleton while querying;
  connectivity-banner rules as everywhere else.

### Glossary & naming (binding)

- Canonical terms only: **Pipeline** (not Flow), **Dataset**, **Source**, **Widget**. New terms to add
  to `GLOSSARY.md` §11 in Phase 0: **Link Analysis Studio**, **GraphSource**, **Entity**, **Link**,
  **Entity Projection** (a mapping, not a store). "Saved view" = a **Link-Analysis View** (Component
  kind `link-analysis-view`).

## 2. Grounded starting point (verified 2026-07-04)

| Piece | State |
|---|---|
| `GraphSource` seam (`graph-source.ts`) | **NOT built** — design only |
| Renderer `GraphViewComponent` | exists — `app/modules/admin/catalog/graph-view.component.ts`, single `@Input data: G6GraphData` |
| Shared types | `app/inspecto/graph/graph-types.ts` (`G6Node/G6Edge/G6GraphData`, token colors only) |
| Lineage mapper + query API | `catalog/catalog-graph.ts::toG6Data` over `GET /catalog/graph?from&depth&direction&kinds&edgeKinds&overlay` |
| Pipeline topology mapper | `pipelines/pipeline-graph.ts` (+ `node-attributes.ts`, icon map) |
| Component reuse mapper | `app/inspecto/component-model/component-graph.ts::deriveComponentGraph` (pure, client-side) |
| Dataset rows for entity-projection | Datasets carry `query?: QueryModel` + columns; sample rows in `studio/datasets/dataset-sources.ts::SAMPLE_SOURCES`; client eval via `app/inspecto/query/query-eval.ts::evaluateRows` and `runSql` (AlaSQL) |
| Mock backend | unified per-Space mock store (`MOCK_STORE_KEY` v6), handler-per-area pattern |
| Studio routing | `app/modules/admin/studio/studio.routes.ts` (datasets/widgets/dashboards) |

## 3. Implementation plan (phases, each independently verifiable)

### Phase 0 — Vocabulary + seam (behavior-preserving refactor)

The design doc's §6 adoption, executed:

1. `GLOSSARY.md` §11 additions (above) + touchpoint-table rows.
2. NEW `app/inspecto/graph/graph-source.ts` — `GraphQuery` + `GraphSource` interfaces exactly as
   designed (add the now-unreserved entity-projection params: `datasetId, sourceCol, targetCol,
   linkKindCol?, attrCols?`).
3. Wrap the three existing mappers as `LineageGraphSource`, `ComponentRegistryGraphSource`,
   `PipelineGraphSource` (naming: *Pipeline*, not Flow — the design doc's `FlowGraphSource` name is
   corrected here per glossary). Mappers are called verbatim inside `query()`; **no mapper logic
   moves**; existing screens keep their direct wiring (opt-in later, not in C5).

**Verify:** unit tests per source proving `query()` output is deep-equal to the current mapper's
output for the same fixture input (the design doc's byte-identical contract). `ng test` green.

### Phase 1 — Analysis library (pure, source-agnostic)

NEW `app/inspecto/graph/graph-analysis.ts` — framework-free functions over `G6GraphData`:

- `shortestPath(g, fromId, toId, direction): { nodeIds, edgeIds } | null` (BFS).
- `neighborhood(g, nodeId, hops, direction): G6GraphData` + `explainNode(g, nodeId): string` (label,
  kind, in/out link summary by edge kind).
- `degreeCentrality(g)` / `betweennessCentrality(g)` → `{ id, score }[]` (Brandes for betweenness;
  fine at mock scale, guard with a node-count cap ~2 000).
- `detectCommunities(g): Map<nodeId, communityId>` (label propagation, deterministic seed for tests).
- `searchNodes(g, text)` / `filterByKinds(g, nodeKinds, edgeKinds): G6GraphData`.

**Verify:** pure unit tests on small hand-built fixture graphs (path with cycle, disconnected pair
returns null, communities on a two-cluster fixture, filter preserves edge validity).

### Phase 2 — Entity-projection source (the new capability)

1. NEW `EntityProjectionGraphSource`: load the Dataset (existing `datasets.service`), obtain rows
   (Dataset `query` via `evaluateRows`/`runSql` over `SAMPLE_SOURCES` — the exact seam Measures
   already uses), then fold rows into `G6GraphData`: distinct `sourceCol`/`targetCol` values →
   `G6Node` (kind `entity`), each row → `G6Edge` (kind = `linkKindCol` value or `link`), duplicate
   edges collapsed with a `count` kept in the edge data. Cap nodes (~500) with a "truncated" flag
   surfaced in the UI.
2. Mock contract stays client-side (this source never hits HTTP — same pattern as
   `component-registry`); **no new mock-store entities needed for querying**.
3. Seed: extend one existing seed pack with a small call-record-shaped sample source
   (caller/callee/callType) so the RA/FMS story demos out of the box.

**Verify:** unit tests — projection over a fixture Dataset yields expected nodes/edges/counts;
truncation flag at cap; missing mapping column ⇒ typed error surfaced (not a throw).

### Phase 3 — The Studio pane

1. Route `studio/link-analysis` in `studio.routes.ts`; nav entry under Studio (Builder lens tag —
   nav itself stays lens-identical per D1; only actions gate).
2. `link-analysis.component` (three-zone layout above) + child `query-builder` and `analysis-panel`
   components. Signals state; form rules per angular-ui SKILL §Forms (ask-the-minimum,
   `uniqueNameValidator` on save-view create).
3. Canvas highlight/dim: smallest viable seam on `GraphViewComponent` — an optional
   `@Input emphasis?: { nodeIds: string[]; edgeIds?: string[]; groups?: Map<string,string> } | null`
   that re-styles (dim non-matches; color by group for communities). No renderer replacement.
4. Saved views: Component kind `link-analysis-view` (config = source id + `GraphQuery` + mapping) via
   the existing components/registry seam — ~~mock-only persistence (backend `ComponentStore` enum is
   closed)~~ **closed 2026-07-08 (INV-1): `link-analysis-view` + `geo-map-view` joined the backend
   `ComponentStore.WRITABLE_TYPES`; saved views persist server-side with no client change.**

**Verify:** component specs (query→render→analyze flow with mocked sources; a11y — axe clean, labels
per W4 conventions); live smoke: run dev server, load each of the 4 sources, run each analysis op,
save/reload a view.

### Phase 4 — Review sheet + hardening + ship

- Review sheet `docs/superpower/reviews/link-analysis-studio.md` (Wave-4 sheet format).
- Feature-matrix row S2 → shipped-in-mock status; completion-plan §5 C5 → done.
- GAUNTLET (UI lint + test:ci + build) + SMOKE; commit as
  `feat(ui): Link Analysis studio (C5, P3) — GraphSource seam + Entity/Link projection` on `master`.

## 4. Out of scope (unchanged from design)

- No new renderer, no graph store, no generic query language, no per-record provenance.
- ~~No backend: entity-projection is client/mock~~ **Closed 2026-07-08 (INV-1): the real backend
  projection shipped — `POST /inv/projection` (DuckDB-side fold over the query sandbox, heaviest-first
  + truncation); `EntityProjectionGraphSource` is backend-first with the client sample fold as the
  offline fallback (the mock answers 501 by design). Still open from design §7: the `attrCols`
  mapping surface and the schema-relationship model.**
- No migration of the three existing screens onto their GraphSources (opt-in later).
- Dashboard placement of saved Link-Analysis Widgets (wire-up is a small follow-on).

## 5. Estimate & sequencing

L overall: P0 ~½ day · P1 ~½ day · P2 ~½–1 day · P3 ~1–1½ days · P4 ~½ day. Phases are strictly
ordered; each ends with green tests before the next starts.

## 6. Release phasing — MoSCoW → MVP / V1 / V2 / Backlog (owner input 2026-07-04)

The owner supplied a full MoSCoW catalog for a **Graph-based Investigation Module**. This section
maps it onto releases grounded in our constraints: **mock-first track**, the existing **G6 v5
renderer**, **sample-row-scale data** (hundreds–thousands of nodes, not millions), and the
**unbuilt security module** (RBAC/permissions/collaboration are backend-blocked). Principle: MVP =
one investigable end-to-end loop (project → query → see → analyze → save); V1 completes the
MUST list; V2 = SHOULD; Backlog = COULD; WON'T unchanged.

### MVP — the investigable core (== phases P0–P4 above, one release)

- **Source & projection:** select GraphSource; entity projection from Dataset (source/target/
  linkKind/attr column mapping ⇒ one entity kind + N relationship kinds from data values).
- **Query builder:** single root, depth, direction, node-kind + relationship-kind filters, execute,
  inline validation (required fields, cap warnings).
- **Canvas** (mostly free from the existing `GraphViewComponent`/G6): interactive canvas, pan/zoom,
  fit-to-screen, node dragging, node labels, edge labels, per-kind icons + colors.
- **Investigation:** shortest path · N-hop neighborhood · degree + betweenness centrality ·
  community detection · explain relationships · highlight results · focus selected node.
- **Search & filter:** node search, filter by entity/relationship kind, clear filters, result
  highlighting.
- **Results panel:** path details, neighbor list, ranking tables, community membership, node/
  relationship details, click ⇒ focus.
- **Workflow:** save/reload investigation (query + filters as a `link-analysis-view` Component,
  mock persistence); save as Component.
- **Export:** PNG (canvas snapshot) + JSON (`G6GraphData`).

### V1 — completes the MUST list (fast follow)

- **Projection:** multiple entity **types** per investigation (multi-mapping: N column-pair mappings
  over one or more Datasets, merged into one graph).
- **Query:** multi-root seeds (moved up from SHOULD — cheap once the query model exists),
  incremental loading / neighbor expansion (click-to-expand a node re-queries and merges).
- **Investigation:** all paths (limit-capped), common neighbors, connected components.
- **Canvas:** collapse/expand branches, pin nodes, hide nodes, remove isolated nodes, minimap,
  full-screen, edge styling per relationship kind.
- **Search & filter:** relationship search, property-value filters, time filter (when the mapped
  Dataset has a time column).
- **Workflow:** save graph **layout** + analysis results with the view; **Widget** + Dataset
  binding + parameter support; dashboard placement.
- **Export:** SVG, GraphML, investigation report (reuses the C6 scheduled-reports export seam).
- **Cross-cutting:** keyboard shortcuts, undo/redo for graph ops.

### V2 — professional investigation depth (SHOULD)

- **Advanced traversal:** relationship weights, cost-based shortest path, cyclic path detection,
  bridge/articulation discovery, ego network.
- **Timeline:** time slider, validity periods, historical graph, playback, temporal paths,
  before/after comparison.
- **Assistance:** suspicious-node scoring, risk visualization, bookmarks, investigator notes, tags,
  evidence attachment, checklist. (Notes/evidence pair naturally with Incidents/Cases — reuse that
  model, don't invent a parallel one.)
- **Visual analysis:** heat maps, edge thickness by weight, node sizing by score, layout switching
  (force/radial/hierarchical/circular), auto-clustering.
- **Graph operations:** merge/split nodes, manual relationships, annotations, virtual nodes.
- **Collaboration** (⚠️ backend-blocked): shared investigations, comments, version history, audit
  log, permissions — requires `inspecto-security` + real Component persistence (the closed
  `ComponentStore` enum must be widened first).

### Backlog (COULD — pull individually on demand)

- **Algorithm library:** PageRank, closeness/eigenvector/Katz, HITS, k-core, triangles, cliques,
  max-flow/min-cut, spanning tree, Jaccard/similarity, link prediction. The `graph-analysis.ts`
  registry (§3 P1) is the extension seam — each is a pure function drop-in.
- **AI assistance:** pattern explanation, suggested expansion/next node, NL graph search, auto
  reports — routes through the existing Assist seam.
- **Pattern detection packs** (fraud rings, SIM box, circular flow, device/identity sharing, AML
  chains, call-forwarding loops, subscription fraud, revenue leakage): each is a saved,
  parameterized investigation template shipped in Space Template seed packs (RA/FMS) — build the
  *mechanism* in V1 (parameterized saved views), the *packs* here.
- **Geospatial:** geo layout, map overlay, distance, region clustering.
- **Automation:** scheduled investigations (reuses C6 Trigger seam), saved alerts, auto refresh,
  trigger-on-new-data.
- **Performance at scale:** progressive rendering, lazy loading, caching, background analytics,
  millions-of-nodes — irrelevant at mock scale; revisit with the real backend projection.

### WON'T (this release) — unchanged from owner list

Distributed graph DBs, streaming graph analytics, live co-editing, 3D/VR/AR, GPU compute, external
evidence submission, federation, blockchain, social/OSINT/dark-web ingestion, voice/image/video
extraction, forensics, cyber attack graphs, >100M-node interactive viz.

### Cross-cutting requirements — status mapping

| Requirement | Where it lands |
|---|---|
| Theme (light/dark), responsive UI, a11y | MVP — house standard (design system + axe gate) |
| API-first, stateless algorithms, metadata separate from source data | MVP by construction — `GraphSource.query()` contract, pure `graph-analysis.ts`, views are Components |
| Parameterized queries, extensible/plugin algorithms | seam in MVP (§3 P1 registry), surfaced in V1 (parameters) / Backlog (plugins) |
| Undo/redo, keyboard shortcuts | V1 |
| Reproducible investigations | V1 (saved view = query + filters + layout + seed) |
| RBAC, dataset-level permissions, audit trail | V2+ — blocked on `inspecto-security`; lens gating is the interim |
| Large-graph performance | Backlog — with the real backend |
