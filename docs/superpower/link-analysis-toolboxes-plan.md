# Link Analysis — Toolboxes, Layouts & Algorithms (plan)

**Status:** Waves A–C SHIPPED (A `ab8b44c` 2026-07-04; B `de1a8e2`, C committed 2026-07-05); Wave D docs
done — see [`reviews/link-analysis-studio.md`](reviews/link-analysis-studio.md). **Date:** 2026-07-04.
**Feeds:** `docs/superpower/reviews/link-analysis-studio.md` (review sheet, appended per wave).
**Builds on:** the shipped C5 studio (`modules/admin/studio/link-analysis/`) + shared G6 host
(`modules/admin/catalog/graph-view.component.ts`) + pure lib (`inspecto/graph/graph-analysis.ts`).

## Owner decisions (AskUserQuestion, 2026-07-04)
1. **Layouts = G6 built-ins mapped.** Expose G6 v5's *real* layout types and map the requested names
   onto them; do **not** port the bespoke gallery demos (Fishbone, Arc, SubGraph, Cluster-sort,
   Information-density-as-demo). Tree layouts (Mindmap / Org-chart / Radial-tree) enable **only** when
   the current graph is tree/forest-shaped.
2. **Algorithms = include pattern matching now.** New **Algorithms** toolbox groups the existing
   shortest-path + LP clustering + centrality, **adds Louvain** community detection, **and adds Graph
   pattern matching** in this pass.
3. **Delivery = plan doc first, then build** wave by wave with GAUNTLET + live smoke + a commit per wave.

## Target UI (canvas-first, no side rails)
The current 3-zone layout (left query/views rail · canvas · right analysis rail) is replaced by a
**toolbar-driven, canvas-maximal** layout:

- **Left rail: removed.**
- **Header:** unchanged (title + studio fullscreen).
- **Canvas toolbar** (single wrap row): **Search** (icon → popover: the search field + live node
  matches) · **Filter** (kind funnel, unchanged) · **Layout** (icon → menu, Wave B) · **Algorithms**
  (icon → menu, Wave C) · **Display** (paint-brush, unchanged) · spacer · **Open views** (folder icon
  → menu listing saved views) · **Save view** (bookmark icon → save form) · Fit · Fullscreen · PNG ·
  JSON.
- **Bottom panel** (collapsible, under the canvas) becomes **sectioned/tabbed**: **Query** (the
  relocated query form + its collapsed summary) → **Results** (algorithm output: path hops, ranking,
  communities, pattern matches; each row focuses/emphasizes on the canvas) → **Data** (the existing
  `<inspecto-data-table tier="standard">`). Query sits **above** the data table, as asked.
- **Right rail: removed** — its analysis tools move into the **Algorithms** toolbox (inputs) + the
  **Results** section (output).

> **One open UI decision for approval:** results location. Recommended = a **Results** section in the
> bottom panel (keeps the canvas full-width, matches "remove the panels"). Alternative = keep a slim
> right rail as the combined Algorithms+Layout toolbox. Default to the bottom-panel Results unless you
> say otherwise.

## Wave A — UI restructure (+ migrate existing analysis, so no functionality gap)
Behaviour-preserving reflow of what already exists. **Execution refinement (approved 2026-07-05):**
Wave A also *migrates* the existing 4 analysis tools into the new Algorithms menu + Results section, so
every intermediate commit stays fully functional; Wave C then only *adds* Louvain + pattern matching.
1. Delete the left rail (`leftOpen` strip + query/views sections) and the right rail (`rightOpen`
   strip). → verify: pane still renders, dead signals removed.
2. The **bottom panel is always present** (so the first query is runnable with no graph yet) and gains
   **Query / Results / Data** tabs (`bottomTab`, default `query`, `bottomOpen` default true). Move the
   **Query** form + summary into the Query tab (above Data). `queryOpen`/`querySummary`/`editQuery`/
   `run` reused. → verify: run the first query from the Query tab; the top status-chip bar reflects it.
3. **Persistent slim toolbar** above the canvas: Open-views + Save are always visible (Save disabled
   without a graph); the graph-only tools (Search/Filter/Layout/Algorithms/Display/Fit/Fullscreen/
   Export) render only with a graph.
4. **Search** → toolbar search-icon popover; **Open views** (folder icon) → menu of `views()`; **Save
   view** (bookmark icon) → the existing save form (ask-the-minimum + `uniqueNameValidator`). → verify:
   search narrows canvas+table; save + reload + re-load a view.
5. **Migrate the existing analysis** (path/explain/centrality/communities): inputs → an **Algorithms**
   toolbar menu; results → the bottom **Results** tab (focus/emphasis behaviour unchanged). → verify:
   all 4 still run and paint emphasis.
6. Specs: update the pane spec for the relocated controls. → verify: `test:ci` green.

## Wave B — Layout toolbox (G6 built-ins mapped)
1. **Shared host:** add `@Input() layout: GraphLayoutId | null` to `GraphViewComponent` (today the
   layout is hardcoded `antv-dagre`). A `layoutConfig(id, dark)` maps the id → a G6 v5 layout options
   object. `null` = the current `antv-dagre` default, so the 4 existing hosts are byte-identical.
2. **Mapping table** (requested name → G6 built-in; kept vs. dropped):

   | Requested | G6 built-in | Notes |
   |---|---|---|
   | Grid Layout | `grid` | ✓ |
   | Fund Flow / Organization Chart (flow) | `antv-dagre` (LR/TB) | ✓ default; TB variant = org-flow |
   | Clustering Force Layout | `d3-force` (+ community grouping force) | ✓ |
   | (plain force) | `force` / `d3-force` | ✓ |
   | Radial | `radial` | ✓ |
   | Degree Ordered | `concentric` (by degree) | ✓ maps "degree ordered" |
   | (circular) | `circular` | ✓ |
   | Information Density | `mds` | ✓ closest built-in |
   | Mind Map / Auto Mindmap | `mindmap` | ⚠ tree-only |
   | Organization Chart (tree) | `compact-box` | ⚠ tree-only |
   | Radial Compact Tree | `compact-box` (radial) / `dendrogram` | ⚠ tree-only |
   | Anti-Procrastination Fishbone / Fishbone | — | ✗ dropped (bespoke demo) |
   | Arc Diagram | — | ✗ dropped (bespoke demo) |
   | Cluster Sort | — | ✗ dropped (bespoke demo; overlaps clustering-force + concentric) |
   | SubGraph Layout | — | ✗ dropped (bespoke demo) |
   | Switch Layout | — | = the picker itself, not a layout |

3. **Tree gating:** a pure `isForest(g)` (acyclic, each node ≤1 parent) in `graph-analysis.ts`; the 3
   tree layouts are disabled (greyed + tooltip "needs a tree-shaped graph") when `!isForest`. Root =
   the max-out-degree node per component.
4. **Persist** the chosen layout with the saved view (extend `LinkAnalysisView` with `layout?`).
   → verify: pick Grid/Radial/Force, canvas relayouts live; tree layouts greyed on the fraud graph,
   enabled on the Mind-map example; layout survives save+reload; `test:ci` green (host layout-map unit
   test + pane spec for gating/persist).

## Wave C — Algorithm toolbox (add Louvain + pattern matching)
*(Reuse/migration of the existing shortest-path / centrality / LP-clustering happened in Wave A.)*
1. Relabel the migrated tools to the requested names (LP = "LP automatic clustering", etc.).
2. **Louvain** — add `louvainCommunities(g)` to `graph-analysis.ts`: modularity-optimizing (local
   moving + aggregation), deterministic tie-breaks, `ANALYSIS_NODE_CAP` guarded, smallest-member
   community ids (same output contract as `detectCommunities` → reuses the community emphasis + list).
   Specs: known-modularity fixtures (two clear cliques → 2 communities; a ring → its natural split).
3. **Graph pattern matching** — add `matchPattern(g, pattern)` to `graph-analysis.ts`. **Pattern** = a
   small motif: an ordered list of steps `{ nodeKind?, edgeKind?, direction }` (a path motif, e.g.
   `account —transfer→ account —transfer→ merchant`), plus a "any node/edge kind = wildcard" option.
   Returns every matching node/edge id set → drives canvas emphasis + a Results list ("N matches";
   click a match to focus). Hop-capped + match-count-capped. **Deliberately a path-motif matcher, not
   full subgraph isomorphism** (that's the V2 line) — real, testable, and enough for the fraud-ring use
   case. UI: a compact motif builder in the Algorithms toolbox (kind selects per step, add/remove step).
   Specs: a seeded ring matches the transfer motif; a wildcard motif; no-match path.
4. Results render in the bottom **Results** section; each result type keeps its existing focus/emphasis
   behaviour. → verify: each of the 4 algorithms runs on a seeded example, emphasis paints, results
   list focuses; `test:ci` green (Louvain + pattern specs + pane wiring spec incl. axe).

## Wave D — Verify, docs, commit
- GAUNTLET (`lint:tokens` · prod `build` · `test:ci`) + live smoke on the 4 seeded examples.
- Update `reviews/link-analysis-studio.md` (append a "toolboxes/layouts/algorithms" pass) + the
  angular-ui SKILL `[layout]` note + `/design` if a shared primitive changed.
- Commit per wave (`feat(ui): …`), `master`-only propagation. Push only on explicit ask.

## Deliberate scope cuts (V1/backlog)
Full subgraph-isomorphism pattern matching (this pass = path motifs) · the dropped bespoke layouts
(Fishbone, Arc, SubGraph, Cluster-sort, Information-density-as-demo) · per-layout tuning UIs · layout
animation/transitions · pattern library/save · incremental match expansion.

## Constraints carried from C5
- Backend `ComponentStore` enum stays closed → views (now incl. `layout`) persist **mock-only**.
- G6 can't instantiate in jsdom → algorithms/layout-map are pure-tested in the lib; the pane is tested
  on the empty path + via component methods with the canvas not mounted.
- No hardcoded colours (community/emphasis swatches come from `ICON_COLOR_SWATCHES`); WCAG axe gate on
  new component states.
