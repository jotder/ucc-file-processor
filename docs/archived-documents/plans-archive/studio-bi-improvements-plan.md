# BI UI Improvement Plan — inspecto-ui Studio (UI-only, mock-backed)

> Per repo rule, on approval this plan is persisted to `docs/superpower/studio-bi-improvements-plan.md`.

## Context

Inspecto's BI surface (Studio: Datasets → Widget Builder → Dashboards, plus the viz plugin registry,
Query Core, and data-table family) shipped P0–P1 and is solid as a foundation: 8 viz plugins, Show-Me
recommender, auto channel mapping, drag-drop dashboards, cross-filter with drill-down, AlaSQL offline
execution, a11y/token-gated design system.

Analysis (2026-07-02) found the gaps that matter for the chosen goal — **internal analytics / demo
quality, UI-only against mock interceptors** (backend P4 work explicitly deferred per the 2026-06-30
directive). This plan closes the highest-value UX gaps without touching Java.

## Current-state assessment (summary)

**Strong:** Studio flow end-to-end (dataset → recommended viz → mapped fields → live preview → widget
→ dashboard), cross-filtering, catalog with lineage, 4-tier data-table with SQL editor, design-system
consistency (status-badge/empty-state/skeleton, no-hardcoded-colors CI gate, WCAG 2.2 AA specs).

**Weak for BI use (UI-addressable):**
1. No time-grain control on charts (bucketing exists in `transformProps` but no user control).
2. ~~No calculated columns / named-Measure builder UI~~ — **RESOLVED**: `DatasetMeasuresComponent`
   (named-Measure editor) already existed; `DatasetCalculatedComponent` (row-level calculated columns,
   DAT-5) shipped 2026-07-10 — see `calculated-columns-design.md` §0. *(Note: UI term is **Measure** per
   GLOSSARY — the model type `NamedMetric` is a deferred backend-rename touchpoint; UI labels say Measure.)*
3. Export is CSV-only — no PNG export of a chart, no dashboard snapshot.
4. Drill-down only toggles cross-filter; no drill-through ("click value → detail table/view").
5. No dashboard-level filter bar (viewer-facing quick filters vs the editor's condition builder).
6. Viz set caps out at 8 Chart.js types — no heatmap/scatter-matrix/pivot; gauge exists, funnel doesn't.
7. Charts lack text alternatives (canvas a11y gap acknowledged in report-builder-design.md §14).
8. Chart responsiveness incomplete (desktop-sized; tiles don't adapt).
9. "KPI & Reports" nav item is an empty placeholder — dashboards can't be surfaced there.
10. AlaSQL is single-table; multi-source joins impossible offline (accepted constraint — out of scope).

## Plan — three phases, each independently shippable & verifiable

### Phase A — Viewer experience (highest demo value)
Files: `inspecto-ui/src/app/modules/admin/studio/dashboards/` (editor + a viewer split if not present),
`inspecto-ui/src/app/inspecto/viz/`.

1. **Dashboard filter bar**: viewer-facing quick-filter chips derived from the dashboard's cross-filter
   ConditionGroup (reuse Query Core `ConditionGroup` + existing condition-builder types; render with
   existing chip/badge components). Editor picks which columns are exposed.
2. **Drill-through**: on tile value click, offer "Filter" (existing) or "View rows" — opens a drawer with
   `<inspecto-data-table tier="standard">` showing the underlying rows (AlaSQL `runSpec` with the drill
   condition applied). Pure reuse of the data-table family.
3. **Time-grain control**: per-widget grain selector (auto/day/week/month) for temporal x-channels; wire
   to the existing offline bucketing in `transformProps`. Persist in widget config.

### Phase B — Authoring depth
Files: `modules/admin/studio/widgets/explore.component.ts`, `modules/admin/studio/datasets/`,
`inspecto/viz/` plugin registry.

4. **Measure builder UI** (canonical term: Measure, never "Metric" for BI): dataset-level editor for
   named measures (aggregation + expression over columns), stored in the dataset's `NamedMetric[]` model
   field via the studio mock interceptor; surfaced in Explore's field list alongside raw columns.
5. **Calculated columns**: simple expression column editor on the virtual dataset (evaluated by AlaSQL);
   validate with a live preview using the existing QueryPanel.
6. **2–3 new viz plugins** via the existing `VizPlugin` seam (no new charting lib): **heatmap** (Chart.js
   matrix via bar-stack trick or plugin), **scatter**, **funnel** (horizontal bar transform). Register
   `meta.fit` so Show-Me ranks them.

### Phase C — Polish & reach
7. **Chart export**: PNG download per tile (`canvas.toDataURL`) + "export dashboard" that downloads each
   tile PNG; CSV of a widget's result set (reuse data-table CSV writer).
8. **Chart a11y**: generated text summary (`aria-label` + visually-hidden table of the top-N series
   values) on `chart.component.ts` — closes the acknowledged §14 gap and keeps axe specs green.
9. **Responsive tiles**: ResizeObserver-driven chart re-render; 1-col stacking under a breakpoint.
10. **KPI & Reports page**: replace the placeholder with a read-only gallery of saved dashboards
    (list from the studio mock `/components/dashboard`) so demos have a natural landing page.

## Explicitly out of scope (deferred backend / P4)
Server-side QuerySpec→DuckDB execution, real ComponentStore persistence (closed enum), scheduling/email
delivery, sharing/RBAC, materialization/caching, multi-source joins, semantic-layer governance.

## Constraints & conventions
- Apply the **angular-ui skill** before any component work (mandatory per repo).
- Canonical vocabulary is binding: Measure (not BI Metric), Pipeline, Dataset, Incident, Widget.
- All new UI: standalone components, signals, design tokens only (CI gate), empty-state/skeleton usage,
  axe specs per component.
- Everything mock-backed via `studio-mock.interceptor.ts` (`environment.mockStudio`).
- Run `graphify update .` after code changes.

## Verification
- Per phase: `npm run lint:tokens` + prod `build` + `test:ci` (baseline 335/0/5 — keep green, add specs
  for each new component/plugin).
- Live smoke via preview server: create dataset → measure → widget (new viz type) → dashboard → filter
  bar → drill-through drawer → PNG export; 0 console errors.
- a11y: axe specs on every new component; verify chart text-alternative renders.

## Suggested order
Phase A (1→3) first — biggest visible win for demos; then B, then C. Each item is a separate
conventional commit on a feature branch, merged to `master` per release-workflow.
