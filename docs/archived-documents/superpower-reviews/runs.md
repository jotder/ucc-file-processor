# Review sheet — Runs + Run detail (Wave 3, Business)

**Wave:** 3 (Business) · **Date:** 2026-07-03 · **Files:**
`modules/admin/runs/{runs.component.ts,.html,.spec.ts}` ·
`modules/admin/run-detail/{run-detail.component.ts,.spec.ts}`.

Every configured ingest run with lifecycle actions (trigger/pause/resume/reprocess, "Run all") and a
tabbed audit drill-down (batches/files/lineage/quarantine/commits/report) per run. The plan lists this
pane **twice** with different access per lens: **Business → "Runs (read-only observe)"**, **Ops/Builder →
full**. Unlike Jobs (Wave 1), where Run-now/Enable-toggle were classified operational and left available
to every lens, this pane's plan entry uses the explicit words "read-only observe" for Business — a
stronger, pane-specific rule.

## Product-owner clarification (resolved before starting)

Asked directly whether Trigger/Pause/Resume/Reprocess should follow the Jobs precedent (operational,
ungated) or the plan's literal "read-only observe" wording. **Confirmed 2026-07-03: gate for Business** —
this pane's mutating actions are blocked in the Business lens, unlike Jobs' run-now/toggle.

## R1 — Glossary

**Run** (an ingest execution unit), **Batch**, **Reprocess**, **Inbox** (pending-files status) — all
canonical. No change.

## R2 — Attribute audit

Read-only column/field audits (`RunView`, `AuditRow`, `InboxStatus`, `BatchAuditReport`) — no editable
attribute set; the mutating actions (trigger/pause/reprocess) take no config, just a target name.

## R3 — UX pass

**Runs:** `<h1>`, auto-refresh toggle, icon Refresh, "Run all", a grid with 4 row actions. **Run detail:**
breadcrumb-style back link, a 6-tab drill-down, per-tab loading, a Files sub-view with live inbox status +
a status filter, a Report tab with a date range + percentile stats. Both already toolbar-first and
progressive. No structural change beyond the gating below.

## R4 — Reuse pass — **gating applied, no design-system violations found**

Both panes were already fully compliant (`<inspecto-data-table>`, no hardcoded colors, `optimisticMutate`
for the pause/resume toggle). The one substantive change this review makes is **read-only gating for
Business**, applied at both the list and detail level for consistency:

- **Runs**: "Run all" toolbar button hidden; the row-action list drops Trigger/Pause-Resume/Reprocess,
  keeping only "Open detail" (view-only, unaffected).
- **Run detail**: the Batches tab's "Reprocess this batch" row action is hidden (same underlying mutation,
  different entry point); "Lineage & details" stays available (opens the read-only batch-detail dialog).
- **Defense-in-depth** (per the W4 Phase-1b lesson — don't trust hidden buttons alone): `trigger`,
  `runAll`, `togglePause`, `openReprocess` (Runs) and `reprocessRow` (run detail) all guard on
  `lens.readOnly()` at the top of the method, independent of whether their button happens to be visible.

## R5 — Logic extraction

Already well factored — `optimisticMutate` (pause/resume), the tab-load dispatch, and the files
stats/filter getters are all small and pane-specific. No further extraction warranted.

## R6 — Mock contract

Runs on the unified `MockStore` via `RunsService`; every read degrades independently on error. No new
endpoint — gating is purely client-side.

## R7 — Interview / decisions made

1. **Runs' mutating actions gated for Business, unlike Jobs'** — see the clarification above. This is a
   deliberate, pane-specific exception to the "operational actions stay available everywhere" heuristic
   established for Jobs, justified by the plan's explicit "read-only observe" wording for this specific
   pane. Future panes should default to the Jobs heuristic (operational ≠ authoring) unless the plan says
   otherwise as explicitly as it does here.
2. **`BatchDetailDialog` needs no changes** — confirmed read-only (three audit fetches, no mutation).
3. **`ReprocessDialog` needs no changes** — it only collects a batch id; the actual `reprocess()` call
   happens in the caller (`RunsComponent.openReprocess`), which is already gated.

## R8 — Verify (evidence)

- **Gaps closed:** neither `runs.component.ts` nor `run-detail.component.ts` had a spec — added
  `runs.component.spec.ts` (load, default-lens actions present, Business-lens actions reduced to "Open
  detail", direct-call guards on all four mutating methods, a11y) and
  `run-detail.component.spec.ts` (batches-tab load, default/Business-lens `auditRowActions`, a direct-call
  guard on `reprocessRow`, a11y).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **536 passed / 0 failed / 5 skipped**
  (baseline 526/0/5; +10 new cases across the two new spec files).
- **Live smoke** (`:4204`): switching to Business hides "Run all" and every Runs row action but "Open
  detail"; run detail's Batches tab drops "Reprocess this batch"; switching back to Builder restores
  everything. No console errors.

**Definition of Done: met** — both panes were already design-system compliant; this review's substance is
the read-only gating (resolved via product-owner clarification) and closing two a11y-gate gaps.
