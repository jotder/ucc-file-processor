# Review sheet — Dashboard editor (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-02 · **Files:**
`modules/admin/studio/dashboards/{dashboard-editor.component.ts,.html,.spec.ts, dashboard-types.ts,
dashboards.service.ts, dashboard.kind.ts}`.

Compose saved **Widgets** into a dashboard grid: drag-to-reorder (CDK), per-tile width toggle, a
dashboard-level **cross-filter** (Query Core condition group over the union of tiled datasets' columns)
that live-updates every tile, per-viewer quick-filter bar, drill-through drawer, and PNG export. The
`dashboard` component kind is the first consumer of the `layout` wiring variant in the component model.

## R1 — Glossary

**Dashboard**, **Widget** (Type→instance, tiles reference widget ids), **Tile** (a placed widget +
layout span), **Cross-filter** (dashboard-level condition group), **Quick filter** (viewer-facing exposed
fields). All canonical. No GLOSSARY change.

## R2 — Attribute audit

Identity is a single scalar (`name`/id) — everything else (`tiles[]`, `filter`, `exposedFields[]`) is
structural composition state, not a form-fillable attribute set. `DashboardConfig` matches what the editor
actually authors; nothing speculative.

## R3 — UX pass

Breadcrumb + `<h1>`; toolbar actions (Export PNGs, Cancel, Save) with icon+label; add-widget select;
cross-filter condition-group builder; exposed-fields multi-select feeding a viewer quick-filter bar;
drag-reorder with a labelled drag handle; per-tile icon actions (drill/widen/remove) all with
`aria-label`+tooltip; a drill-through drawer. Already strong, toolbar-first, icon-led. No structural change.

## R4 — Reuse pass — **1 fix applied**

**"No tiles yet" was a bare `<div class="text-secondary mt-8">`** — the same class of violation as the
Wave-1 Enrichment empty-div (never re-roll an empty state). Replaced with
`<inspecto-empty-state icon="…squares-2x2" message="No tiles yet — add a saved widget above.">`.

Otherwise fully on the design system: `<inspecto-alert>` (writes-disabled), the shared Query Core
condition-group component, `dashboard-tile`/`dashboard-filter-bar`/`dashboard-drill-drawer` sub-components,
CDK drag-drop (not a design-system concern). No hardcoded colors. `exportPngs()` reads `<canvas>` directly
off already-rendered tiles — an intentional, narrowly-scoped DOM query (not a query-selector anti-pattern;
no reusable primitive exists for "export every rendered chart," and one isn't warranted for a single call
site).

## R5 — Logic extraction

Already well factored: `dashboard-types.ts` (pure `buildDashboard`), `dashboard.kind.ts` (kind
registration + `validateDashboardConfig`, framework-free, its own spec), and three extracted
presentational sub-components (tile, filter-bar, drill-drawer). The 243-line (now ~250) editor is
orchestration + the computed column-union/exposed-values/drill-view derivations — legitimate signal-driven
composition, not extractable shaping.

## R6 — Mock contract

Runs on the unified `MockStore` via `ComponentsService` (`dashboard` kind): list/get/create/remove
round-trip; 503 → writes-disabled alert; **409 on a duplicate id** now pre-empted inline (R7).

## R7 — Interview / decisions made

1. **Inline duplicate-id guard added** (this review) per the confirmed product-wide rule #1 — identical
   shape to jobs/dataset-editor: on **create**, the editor fetches existing dashboard ids and the `name`
   control rejects a duplicate (case-insensitive) with an inline `mat-error`, instead of relying on the
   server 409 → toast. Edit keeps the id disabled (immutable), so the guard is create-only.
2. **PNG export is chart-tiles-only** (pre-existing, not changed) — table/KPI tiles have no canvas and are
   noted as exporting via their own surfaces. Confirmed still accurate; no action.

## R8 — Verify (evidence)

- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **488 passed / 0 failed / 5 skipped**
  (baseline 487/0/5; +1 dup-guard case). Existing add/span/remove/cross-filter/save/drill/a11y cases
  unchanged.
- **Live smoke** (`:4204`): New dashboard → typed an existing id → inline "A dashboard with this id
  already exists", save no-ops; empty-tiles state renders as the shared empty-state; unique id + a tile
  saves and returns to the list. No new console errors.

**Definition of Done: met** — empty-state fix + dup-guard close the two rule gaps; pane otherwise already
compliant and well-factored.
