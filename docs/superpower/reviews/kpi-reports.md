# Review sheet — KPI & Reports (Wave 3, Business)

**Wave:** 3 (Business) · **Date:** 2026-07-03 · **Files:**
`modules/admin/kpi-reports/{kpi-reports.component.ts,.spec.ts}`.

The read-only landing gallery over Studio dashboards — every saved dashboard as a card (tile/quick-filter
count), opening in the Studio editor. Since W4 Phase 2 this is also the **Business lens's default home
route** (`LENS_HOME.business = 'kpi-reports'`), making this review higher-stakes than a typical pane.

## Product-owner clarification (resolved before starting)

This pane's empty-state offers a **"Create a dashboard"** action that jumps into Studio's dashboard
editor — a Builder authoring surface. The plan's original persona table lists "Studio explore (read)"
under Business, which reads as Business-should-be-read-only-in-Studio-too — but the W4 lens shell
(Phase 1/1b) only gated the literal Workbench (Connections/Sources/Pipelines/Jobs), leaving Studio's
dataset-editor/dashboard-editor/widget-save-dialog fully open to every lens. Asked directly: **confirmed
2026-07-03 — Studio stays open to Business; only the Workbench is read-only.** No gating change needed
here or on the Studio panes reviewed in Wave 2.

## R1 — Glossary

**KPI & Reports**, **Dashboard**, **Widget**, **Studio** — all canonical. No change.

## R2 — Attribute audit

A gallery card audit, not a form: dashboard name, tile count, quick-filter count — matches `Dashboard`'s
shape exactly (`tiles.length`, `exposedFields?.length`). Nothing speculative.

## R3 — UX pass

Single `<h1>` (icon + title), a loading skeleton, an empty state with a "Create a dashboard" CTA, and a
responsive card grid. Clean, minimal, appropriate for a landing gallery. No structural change.

## R4 — Reuse pass

Already fully compliant: `<inspecto-empty-state>`, `<inspecto-skeleton>`, `RouterLink` cards, `OnPush`.
No hardcoded colors (`text-primary` is a theme token, not a literal). No violations found.

## R5 — Logic extraction

Nothing to extract — the component is 21 lines of logic (a list-on-init + a create-navigation helper).

## R6 — Mock contract

Runs on the unified `MockStore` via `DashboardsService.list()`. No new endpoint.

## R7 — Interview / decisions made

1. **Studio gating declined for Business** — see clarification above; this pane's "Create a dashboard"
   shortcut is correct as-is.
2. **Error and empty-list render identically** ("No dashboards yet") — `ngOnInit`'s error handler sets
   `loading.set(false)` without distinguishing a genuine backend failure from a truly empty list, so a
   transient fetch error would show the same "No dashboards yet, create one" message as a fresh space
   with zero dashboards. A minor UX nuance, not a design-system violation (no bare div, no hardcoded
   color) — flagged, not fixed, since the pane already degrades gracefully rather than blanking, and
   distinguishing the two states would be new scope (an error variant of the empty state) rather than a
   fix to something broken.

## R8 — Verify (evidence)

- **Already compliant** — existing spec covers gallery-card rendering, the empty state, and a11y. No code
  changes were needed this review.
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **526 passed / 0 failed / 5 skipped**
  (unchanged from the Dashboard review's baseline — no new/modified test cases here).
- **Live smoke** (`:4204`): switching to the Business lens and reloading lands on KPI & Reports per the
  W4 home-page routing; dashboard cards render and open correctly in Studio.

**Definition of Done: met** — pane confirmed compliant with no code changes required.
