# Review sheet — Dashboard / home (Wave 3, Business)

**Wave:** 3 (Business) · **Date:** 2026-07-03 · **Files:**
`modules/admin/dashboard/{dashboard.component.ts,.html,.spec.ts}`.

The app's original single home page — service health, throughput, error-rate overview, acquisition
summary, recent-activity feed, and a per-pipeline status grid. Still reachable via nav for every lens
(nav is never filtered per the W4 decision); no longer any lens's *default* landing route since W4 Phase 2
(Business → `kpi-reports`, Builder → `pipelines`, Ops → `events`) — this pane itself, however, is the
natural home for a lens-agnostic operational snapshot and stays fully wired.

## R1 — Glossary

No new terms; "Service"/"Pipelines"/"Batch outcomes" are pre-existing operational vocabulary. No change.

## R2 — Attribute audit

Read-only aggregation view — a field audit, not an editable attribute set. KPI tiles (Service/Pipelines/
Paused/Committed/Quarantine/Error-rate), the acquisition summary, the outcome/latency charts, the
activity feed, and the per-pipeline grid all map directly to `ReadyStatus`/`StatusReport`/`ServiceReport`/
`AcquisitionMetrics`/`EventRow`. Nothing speculative.

## R3 — UX pass

Single `<h1>`, auto-refresh toggle + manual refresh icon button, a loading skeleton that mirrors the real
layout (no jump), and progressive sections that degrade independently (acquisition/activity feed hide
entirely when empty rather than showing empty placeholders — a deliberate choice for a dense home page,
not a bare-empty-state gap). No structural change.

## R4 — Reuse pass — **2 fixes applied**

1. **Raw `<ag-grid-angular>`** for the per-pipeline status grid — the exact anti-pattern the data-table
   consolidation (`data-table-family` track) was meant to eliminate; this pane predates or was missed by
   that sweep. Replaced with `<inspecto-data-table tier="mini">`. Also added `statusBadgeHtml()` renderers
   to the `paused`/`lastBatchStatus` columns (previously plain boolean/string text) — status is now
   conveyed via the shared pill, matching every other grid in the app.
2. **Hand-rolled status coloring** — the "Service" tile used
   `[class.ring-green-500]`/`[class.ring-amber-500]` keyed off `ready.status === 'READY'`, a direct
   instance of the "do NOT hand-roll status colors — classify with `statusTone`/render an
   `<inspecto-status-badge>`" rule (the doc comment on `status-badge.component.ts` names this exact
   pattern). Replaced the ring-tinted card + raw status text with an inline `<inspecto-status-badge
   [value]="ready.status">`. The other three conditionally-tinted tiles (Paused/Quarantine/Error-rate,
   `text-amber-600`/`text-red-600`) are left as-is: `text-*` tones are the token guard's documented
   carve-out for "legit inline emphasis," and these are numeric counts (not status tokens) tinted by a
   threshold, not classifiable by `statusTone` — a materially different case from the Service tile's
   literal status string.

Otherwise on the design system: `<inspecto-chart>` (series via `CHART_SERIES`), `<inspecto-skeleton>`,
`<inspecto-status-badge>` (activity feed), `visibleInterval` for the pause-when-hidden auto-refresh. No
other hardcoded colors; the `<pre>` raw-metrics dump uses neutral `bg-gray-100 dark:bg-gray-800`
(non-status, compliant).

## R5 — Logic extraction

`buildAcq`/`total` (metric-series summation) are small, pane-specific, and now covered by the new spec.
Nothing else warrants extraction — the component is orchestration (four independently-degrading fetches).

## R6 — Mock contract

Runs on the unified `MockStore` via `HealthService`/`ReportsService`/`AcquisitionMetricsService`/
`EventsService`; each of the four fetches degrades independently on error (forkJoin + two standalone
subscribes), matching the "one failing call must not blank the whole page" rule. No new endpoint.

## R7 — Interview / decisions made

1. **Read-only by design** — a pure operational snapshot; no authoring surface, so the Wave-1/W4 form and
   dup-guard rules don't apply.
2. **Only the literal-status tile was converted to a badge**, not the three threshold-tinted numeric
   tiles — see R4 #2 for the reasoning (status token vs. numeric-threshold coloring are different cases).
   Flag if you'd like the numeric tiles to route through a shared severity-color helper too; none exists
   today for "tint a number by a numeric threshold" (as opposed to "color a status token"), so building
   one would be new scope, not a fix.

## R8 — Verify (evidence)

- **Gap closed:** `dashboard.component.ts` had **no spec at all** — added `dashboard.component.spec.ts`
  (health/status/report load, acquisition-summary graceful-hide on 404, full-failure degradation +
  toast, a11y).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **526 passed / 0 failed / 5 skipped**
  (baseline 522/0/5; +4 new cases).
- **Live smoke** (`:4204`): Dashboard loads with the Service badge (READY, green), the pipeline grid
  renders via the shared data-table with status-badge cells, auto-refresh continues to work. No console
  errors.

**Definition of Done: met** — the raw ag-Grid host and the one genuine hand-rolled-status-color instance
are fixed; the a11y-gate gap is closed.
