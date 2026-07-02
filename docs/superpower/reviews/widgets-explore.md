# Review sheet — Widgets library + Explore workbench (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-02 · **Files:**
`modules/admin/studio/widgets/{widgets.component.ts,.html,.spec.ts, explore.component.ts,.html,.spec.ts,
widget-save.dialog.ts,.spec.ts, widget-host.component.ts, explore-controls.component.ts, widget-types.ts}`.

Two coupled panes: **Widgets** (the saved-widget library — search/tag gallery with live-render
thumbnails) and **Explore** (the widget builder — pick a dataset, Show-Me recommends a viz, the field
mapper auto-assigns channels, live re-render, save via `WidgetSaveDialog`).

## R1 — Glossary

**Widget** (Type→instance, canonical), **Dataset**, **Measure**, **Field/Channel** (viz mapping terms).
Show-Me is an internal recommender name, not a user-facing concept requiring glossary entry. No change.

## R2 — Attribute audit

Explore's authored state (`datasetId`, `vizType`, `controls` field-mapping, `options`) is structural
composition, not a form-fillable attribute set — same class as the dashboard editor. The one true scalar
form is the **save dialog** (id + tags + description), already following the ask-the-minimum pattern
(name-at-save, pre-filled `<datasetId>_<vizType>`, unique).

## R3 — UX pass

**Widgets:** `<h1>` + subtitle, icon Refresh, primary "New widget", a filter input with icon,
click-to-toggle tag chips (`aria-pressed`), per-card live thumbnail + status-badge (vizType) + icon actions
(open standalone / edit / delete). **Explore:** dataset picker → Show-Me-recommended viz type → live
`<inspecto-viz-render>` → field-mapper controls → cog options → Save. Both progressive and toolbar-first.
No structural change.

## R4 — Reuse pass — **2 fixes applied**

1. **"No widgets match "…"" was a bare `<div class="text-secondary mt-8">`** (`widgets.component.html`) —
   the same class of violation fixed twice already this wave (enrichment, dashboard-editor). Replaced with
   `<inspecto-empty-state icon="…magnifying-glass" [message]="...">`. The pane's *true* empty state
   (`widgets().length === 0`) already used `<inspecto-empty-state>` correctly — only the "0 results after
   filtering" branch was the leftover ad-hoc div.
2. **`WidgetSaveDialog` had no inline duplicate-id guard** (rule #1) — create allowed a name colliding with
   an existing widget id, relying on the server 409 → toast. Fixed with the same `uniqueNameValidator`
   pattern as jobs/dataset-editor/dashboard-editor, wired through `ExploreComponent` (which now also loads
   `widgetsApi.list()` on init to know the existing ids). Guard is **create-only**: on edit the id field is
   already `readonly`/locked, so the validator is skipped entirely (an editing widget legitimately "collides"
   with its own stored id).

Otherwise on the design system: `<inspecto-status-badge>` (vizType), the shared `<inspecto-viz-render>` /
`WidgetHostComponent` live-render thumbnails, `InspectoConfirmService` (destructive delete),
`apiErrorMessage` toasts. No hardcoded colors. The tag chips (`widgets.component.html`) use
`border-primary`/`text-primary` (theme tokens, not hardcoded) — compliant.

## R5 — Logic extraction

Already well factored: viz recommendation (`recommend`), channel auto-assignment
(`autoAssignChannels`), query execution (`runSpec`/`bucketRows`) all live in the framework-free
`inspecto/viz` lib with their own tests. `explore.component.ts` (now ~240 lines) is orchestration —
dataset/viz selection, live re-render on control changes, save flow. `widgets.component.ts` is a thin
gallery (search/tag filter as `computed()`s). No further extraction warranted.

## R6 — Mock contract

Both run on the unified `MockStore` via `ComponentsService`/`WidgetsService`/`DatasetsService`: list/get/
save/remove round-trip; 503 → writes-disabled alert; **409 on a duplicate widget id** now pre-empted
inline (R4 #2). No new endpoint.

## R7 — Interview / decisions made

1. **Dup-guard skipped entirely on edit** (not just disabled-but-present) since the id field is already
   locked/readonly when editing — consistent with jobs/dataset-editor/dashboard-editor's "immutable on
   edit" handling, just expressed as "don't attach the validator" instead of "attach then disable the
   control," because here the *field itself* (not just the control) is locked via `[readonly]`.
2. **Tag-filter chips remain hand-built** (not a shared "chip" design-system primitive) — same latent note
   as the Sources review (glob chips); no shared chip component exists yet. Deferred as a cross-pane
   consolidation opportunity, not a fix for this review.

## R8 — Verify (evidence)

- **Gap closed:** `WidgetSaveDialog` had **no spec** — added `widget-save.dialog.spec.ts` (dup-guard blocks
  then saves once unique; edit path skips the guard; `expectNoA11yViolations`).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **491 passed / 0 failed / 5 skipped**
  (baseline 488/0/5; +3 new widget-save.dialog cases; existing widgets/explore specs updated for the
  `WidgetsService.list()` stub, unchanged otherwise).
- **Live smoke** (`:4204`): Explore → pick dataset → Save → typed an existing widget id → inline "A widget
  with this id already exists"; unique id saves; Widgets gallery search-to-zero-results now shows the
  shared empty-state. No new console errors.

**Definition of Done: met** — two rule-consistency fixes (empty-state reuse + dup-guard) close the gaps;
panes otherwise already well-factored and compliant.
