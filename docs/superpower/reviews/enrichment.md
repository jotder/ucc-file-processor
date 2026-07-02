# Review sheet — Enrichment pane (Builder / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:**
`modules/admin/enrichment/{enrichment.component.ts,.html, enrichment.routes.ts}` + new
`enrichment.component.spec.ts`.

Stage-2 enrichment jobs: a jobs grid with a per-job detail panel of three tabs — **Runs**, **Lineage**
(filtered by runId), **Report** (date-range rollup with percentile stats). Generic audit rows render as a
dynamic-column `pro` data-table. **Read-only** (ported from inspector-ui) — no authoring, so the form rules
don't apply. This is the final Wave-1 Workbench pane.

## R1 — Glossary

Canonical: **Enrichment** (Stage-2), **Job**, **Run**, **Lineage**, **Pipeline** (`onPipeline`). No banned
synonyms. No GLOSSARY change.

## R2 — Attribute audit

Read-only projection — a column/field audit, not an editable `AttributeSpec` set. Job grid columns (name,
onPipeline, event/schedule triggered, runCount, lastRunStatus, lastRunTime) and the report table
(totalRuns / success / failed / errorRate / output rows+files / p50-p95-p99) cover the
`EnrichmentJobView` + `EnrichmentRunReport` shapes with nothing speculative. Audit rows are dynamic-column
(server-shaped), correctly left generic.

## R3 — UX pass

Single `<h1>`; icon-only Refresh with `aria-label`; a job grid (single-select, paged) → a detail panel with
a `mat-tab-group` (Runs / Lineage / Report), a lineage run-id filter, a from/to datepicker report form, a
loading spinner, and a percentile report table with `scope="row"` headers. Progressive disclosure is
natural (detail only after selecting a job). No change needed to the structure.

## R4 — Reuse pass — **2 fixes applied**

1. **Empty state was hand-rolled** — the "no enrichment jobs" case used a bespoke
   `border-2 border-dashed border-gray-300` div, a direct violation of the "never re-roll an empty state"
   rule. Replaced with `<inspecto-empty-state icon="…sparkles" message="…">`, matching every other pane.
2. **`lastRunStatus` rendered as bare text** — every other pane shows a run status via the shared badge.
   Added a `statusBadgeHtml(...)` cell renderer (falls back to `—` when absent), so status is conveyed by
   text + color consistently.

Otherwise on the design system: `<inspecto-data-table>` (standard for jobs, pro for audit rows —
CSV/SQL), `FmtPercentPipe` from the shared format lib, `fmtDateTime` from grid. No hardcoded colors.

## R5 — Logic extraction

`enrichment.component.ts` is 154 lines; only orchestration (list load, tab switching, three detail
fetches) — no extractable shaping. Left as-is.

## R6 — Mock contract

Runs on the unified `MockStore` enrichment handler: `list`, `runs`, `lineage`, `report` served; 404 on the
list drives the (now shared) empty state. No new endpoint.

## R7 — Interview / decisions made

1. **Read-only by design** — enrichment jobs are authored as jobs (`type: enrich`) in the Jobs pane /
   pipeline editor, not here; this pane is the runs/lineage/report lens. Consistent with the
   Business-read-only lens decision. Flag if authoring should surface here.
2. **Report percentile set** (p50/p95/p99 + error rate + output rows/files) is the ported inspector-ui set;
   flag if a different rollup is more useful.

## R8 — Verify (evidence)

- **Gaps closed:** hand-rolled empty state → `<inspecto-empty-state>`; bare status text → `status-badge`
  renderer; and the pane had **no spec** (no a11y gate, §12 miss) — added `enrichment.component.spec.ts`
  (list load, 404→unavailable, select-job→runs, `expectNoA11yViolations` on the no-selection state; the
  detail's `pro` grid `@defer` path is intentionally not mounted in the a11y test).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **482 passed / 0 failed / 5 skipped**
  (baseline 478/0/5; +4 new Enrichment cases).
- **Live smoke** (`:4204`): jobs grid renders with a status badge in Last status; selecting a job opens
  Runs/Lineage/Report; report date-range returns the percentile table; empty state on the no-jobs path.
  No new console errors.

**Definition of Done: met** — two design-system fixes + the a11y gate; read-only pane confirmed compliant.

---

**Wave 1 — COMPLETE.** All Builder/Workbench panes now have review sheets: connections (evidenced via
`087d0e9`), pipeline-editor, parser-config, node-config, jobs, sources, enrichment.
