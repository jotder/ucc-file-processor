# Review sheet — Catalog + lineage graph (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-03 · **Files:**
`modules/admin/catalog/{catalog.component.ts,.html,.spec.ts, store-lineage.component.ts,.spec.ts,
node-detail.dialog.ts, graph-view.component.ts,.spec.ts, catalog-graph.ts,.spec.ts}`.

The metadata catalog: **Tables**/**Streams**/**KPIs** grids, a **Lineage** traversal tool (AntV G6, the
shared read-only graph-host pattern), and a **Usage** tab that embeds the Registry pane (reviewed
separately, `reviews/registry.md`) — Catalog's own definition includes "usage." A node click opens
`NodeDetailDialog` (facts + neighbours + store lineage + an assist-panel "explain this entity"). **Fully
read-only** (ASSIST_READ scope, per the component doc comment) — no authoring, so the form rules don't
apply.

## R1 — Glossary

**Catalog**, **Lineage** (ingest file-lineage vs. flow step-provenance, bridged at the Store —
`docs/GLOSSARY.md` §11), **Store** (table-kind node), **Node/Edge Kind**. All canonical. No change.

## R2 — Attribute audit

Read-only projections — column/field audits, not editable attribute sets. Tables/Streams/KPIs grid
columns and the node-detail fact table match the `MetadataNode`/`KpiCatalogEntry`/`NodeDetail` API
shapes; nothing speculative.

## R3 — UX pass

Single `<h1>` + subtitle; a 5-tab `mat-tab-group` (Tables/Streams/KPIs/Lineage/Usage); the Lineage tab's
traversal form (from-node/depth/direction/kind filters + overlay toggle) followed by the graph + a
kind-color legend; node click opens the detail dialog which supports walking neighbours in place. Already
strong and toolbar-first. No structural change.

## R4 — Reuse pass — **1 fix applied**

**"No nodes matched — adjust the filters and traverse again." was a bare `<div class="text-secondary
mt-6">`** — the fourth instance of this exact violation class found this wave (enrichment, dashboard-editor
"no tiles", widgets "no results", now catalog's "no nodes matched"). Replaced with
`<inspecto-empty-state icon="…share" message="...">`.

Otherwise fully on the design system: `<inspecto-data-table tier="standard">` (tables/streams/KPIs) /
`tier="mini"` (neighbours, store-upstream), the shared `<inspecto-graph-view>` **read-only G6 host**
pattern (rebuilds on data/scheme change, per the angular-ui skill), `<app-assist-panel>` in the node
dialog. No hardcoded colors — node/edge coloring goes entirely through `canvasTheme()` /
`NODE_KIND_COLORS` (`catalog-graph.ts`, already its own pure, tested module). The node-detail dialog's
`<pre class="bg-gray-100 dark:bg-gray-800">` for the attrs JSON dump is a neutral code-block background
(not a status color) — compliant, matches the token guard's carve-out for non-status grays.

## R5 — Logic extraction

Already excellent: `catalog-graph.ts` is the pure, framework-free G6-data mapper (`toG6Data`, `legendFor`,
`nodeColor`/`nodeShape`, the icon-glyph library) with its own spec. `catalog.component.ts` (now ~186
lines) is per-tab load orchestration only. `GraphViewComponent` and `StoreLineageComponent` are each thin,
single-purpose hosts. No further extraction warranted.

## R6 — Mock contract

Runs on the unified `MockStore` via `CatalogService`/`LineageService`: tables/streams/kpis/graph/node all
served; a failing tab degrades independently (each `loadTab()` branch sets its own empty array on error,
matching "one failing call must not blank the whole page"). No new endpoint.

## R7 — Interview / decisions made

1. **Two DoD gaps closed:** `catalog.component.ts` had **no spec at all** (added
   `catalog.component.spec.ts`: per-tab load, graceful 404 degradation, graph traversal + legend
   derivation, and an a11y assertion on the empty-graph state). `GraphViewComponent` had no spec either
   (added `graph-view.component.spec.ts`: the empty/no-data and zero-node paths, per the angular-ui
   skill's own guidance that G6 hosts should be a11y-tested on the non-canvas path).
2. **`NodeDetailDialog` still has no spec** — flagged, not added this round. It's a heavier fixture
   (embeds `StoreLineageComponent` + `AssistPanelComponent`, needs `CatalogService.node()` +
   `LineageService` + assist stubs) and wasn't the pane named in the plan. Worth a follow-up review if
   the dialog gets touched again.
3. **Read-only by design** — consistent with every other Studio/Workbench lens this wave.

## R8 — Verify (evidence)

- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **498 passed / 0 failed / 5 skipped**
  (baseline 491/0/5; +7 new cases: 5 in `catalog.component.spec.ts`, 2 in `graph-view.component.spec.ts`).
  A second, unrelated intermittent failure surfaced mid-review — `studio/widgets/widget.kind.spec.ts`
  (`getKind('widget')` returned `undefined`, a global `ComponentKind`-registry test-isolation flake, not
  caused by anything in this pane) — it passed on rerun; flagging as a standing flaky test alongside the
  already-known `simulator.spec.ts` one, not something fixed here.
- **Live smoke** (`:4204`): Catalog → Lineage tab → traverse with no matching filters → shared empty-state
  renders; Tables/Streams/KPIs tabs load; a table row opens the node-detail dialog with facts, neighbours,
  store-lineage panel, and the assist panel. No new console errors.

**Definition of Done: met** — empty-state fix + two new a11y-gated specs close the gaps; `NodeDetailDialog`
spec flagged as a deferred follow-up.
