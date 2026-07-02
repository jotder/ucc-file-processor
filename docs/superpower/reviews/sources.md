# Review sheet — Acquisition & Sources pane (Builder / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:**
`modules/admin/sources/{sources.component.ts,.html, source-detail.dialog.ts, sources.routes.ts}`
+ new `sources.component.spec.ts`.

A **read-only** pane: every configured acquisition source across all pipelines (`GET /sources`) with an
acquisition-metrics strip (`GET /metrics/acquisition`), row actions run-now (triggers the owning pipeline)
and a details dialog (full source config + live inbox status via `GET /runs/{name}/pending`). No authoring
here, so the form rules (ask-the-minimum, dup-guard) don't apply.

## R1 — Glossary

Canonical: **Source** (a configured acquisition input — never "Collector"), **Connection** (the reusable
profile a source binds to), **Pipeline** (the owning unit), **Watermark** (incremental cursor). All correct;
the pane title "Acquisition & Sources" matches the nav group. No GLOSSARY change.

## R2 — Attribute audit

Sources are backend-derived (read-only projection of each pipeline's source block), so there is no editable
`AttributeSpec` set to author — the audit is instead a **column/field audit**. Grid columns (pipeline, id,
connector, connection, dedup, watermark, parallel, guarantee) and the detail dialog's field grid (adds
duplicateOnChange, guarantee, recursiveDepth, db-watermark, fetchParallel, rate-limit, post-action,
includes/excludes globs) cover the `SourceView` shape with nothing speculative. Left as-is.

## R3 — UX pass

Single `<h1>`; icon-only Refresh with `aria-label` + tooltip; a 6-up acquisition metric-card strip; a
discovered/downloaded/failed bar chart; the source grid with run-now + details row actions. Detail dialog
shows a live inbox-status card (spinner → Processing/Idle + pending count), a config grid, and include/exclude
glob chips, with the bound connection linked through to `/connections`. Empty state when no source is
configured (404). No change needed.

## R4 — Reuse pass

On the design system: `<inspecto-data-table tier="standard">`, `<inspecto-empty-state>`,
`<inspecto-chart>` (series via `CHART_SERIES` tokens), `InspectoConfirmService` (run-now confirm),
`apiErrorMessage` toasts, and `fmtBytes`/`fmtInt` from the shared `format` lib. No hardcoded colors.
**Minor note (not changed — surgical):** the include/exclude glob chips in `source-detail.dialog.ts` use
neutral `bg-gray-200 dark:bg-gray-700` inline (passes the token guard — grays are not status colors, so
`lint:tokens` is green). There is no shared "glob/tag chip" primitive today; if one lands (e.g. for
parser/pipeline tag lists) these should adopt it. Deferred as a cross-pane consolidation, not a Sources fix.

## R5 — Logic extraction

`sources.component.ts` is 175 lines; the only shaping logic is `buildMetrics`/`total` (summing metric
series into cards + chart data) — pane-specific, small, now covered by the new spec. Display formatting
already lives in the shared `format` lib. No extraction warranted.

## R6 — Mock contract

Runs on the unified `MockStore`: `/sources` list, `/metrics/acquisition`, `/runs/{name}/pending`, and
run-now (`/runs/{pipeline}/trigger`) are served; 404 on the list drives the empty state. No new endpoint.

## R7 — Interview / decisions made

1. **Read-only by design** — sources are authored inside the pipeline editor's source node, not here, so
   this pane deliberately has no create/edit. Consistent with the Business-read-only lens decision; here
   even Builder only runs/inspects. Flag if the product owner wants source authoring surfaced on this pane.
2. **Metric-card set is my selection** from the acquisition counters (discovered / downloaded / failed /
   watermark-skipped / bytes / active connections). Flag if a different six are more useful operationally.

## R8 — Verify (evidence)

- **Gap closed:** the pane had **no spec** (no a11y gate — a §12 DoD miss). Added `sources.component.spec.ts`:
  metric-card + chart build, the 404→unavailable path, and an `expectNoA11yViolations` assertion on the
  loaded state.
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **478 passed / 0 failed / 5 skipped**
  (baseline 475/0/5; +3 new Sources cases).
- **Live smoke** (`:4204`): Sources list renders with the metric strip + chart; details dialog opens with
  inbox status + connection link; run-now confirm fires. No new console errors.

**Definition of Done: met** (a11y gate added; read-only pane confirmed compliant).
