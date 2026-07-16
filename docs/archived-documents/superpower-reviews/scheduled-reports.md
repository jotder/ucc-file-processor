# C6 — Scheduled reports — SHIPPED

**Date:** 2026-07-04 · **Pane:** extends `modules/admin/kpi-reports/` · **No new nav item, no new entity**

## Owner decisions (made directly — plan text + Jobs precedent were unambiguous)
1. **Model:** a scheduled export IS a Job (`type: 'report'`, existing cron/manual triggers, run
   history, live-tail reused wholesale) — `params: {reportKind:'dashboard', dashboardId, format,
   recipients}` carries the export shape. No new entity, no new mock collection for the schedule
   itself (only a small `report-artifact` collection for generated output).
2. **Pane:** extended the existing **KPI & Reports** gallery — each dashboard card gets a "Schedule
   export" action + an inline list of its scheduled exports (run now / download / edit / delete).
   No new nav item.
3. **Export:** CSV is real (serializes the dashboard's actual tiles); PDF/PNG are mock placeholders
   (no new rendering-engine dependency — jspdf/html2canvas would violate the "no new deps" rule).
4. **Delivery:** a successful report-job run fans out **REPORT_EXPORTED** via the shared
   `mock/notify.ts` core (C4 reuse) — same chain as Alerts/Expectations.

## What shipped
- **`schedule-export-attributes.ts` + `schedule-export.dialog.ts`**: SchemaForm-driven (mirrors
  `job-form.dialog`'s shape exactly — cron/manual scheduleMode, id immutable on edit, inline dup-guard
  — **11th pane** on the product-wide rule). Submits a `JobUpsert` with `type:'report'`.
- **`jobs.handler.ts` extended, not forked**: `trigger()` now special-cases on **`params.dashboardId`
  presence**, not on `type==='report'` — the `type` value predates C6 and already covers other report
  jobs (the seeded `weekly_billing` placeholder), so keying on the type would have hijacked an
  unrelated existing job. `runReportExport()` looks up the dashboard via
  `componentCollection('dashboard')`, builds a real CSV from its tiles (or a PDF/PNG placeholder
  string), stores it in the new `report-artifact` collection keyed by run id, and fans out
  REPORT_EXPORTED. A missing dashboard FAILs the run cleanly instead of throwing.
- **New endpoint** `GET /jobs/{name}/runs/{runId}/artifact` + `JobsService.runArtifact()`.
- **`kpi-reports.component`**: extended (not replaced) with `reportJobs` (fetched via per-job
  `get()` since the list projection omits `params`), grouped per dashboard via `jobsFor()`,
  `runNow()`/`downloadLatest()` (blob download, mirrors the Events CSV-export client pattern)/
  `editSchedule()`/`removeSchedule()`. Authoring (schedule/edit/delete) gated on
  `canAuthorWorkbench`; Run now + Download are operational (every lens).
- **Seeds:** none added to the default space (no dashboard exists there by default — the pane's empty
  state already covers "no dashboards"); the vertical template packs already have dashboards, so
  scheduling naturally becomes available once a Space has one. `MOCK_STORE_KEY` **not bumped** — no
  existing collection's shape changed; `report-artifact` is additive.

## R8 verification (2026-07-04)
- `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **701 / 0 / 5** (+9: jobs-handler report-export cases,
  KPI&Reports component incl. capability-seam + download-guard, schedule dialog incl. edit-prefill).
- **Live smoke** (:4204, Builder lens): created a dashboard component directly (default space has
  none by default) → `/kpi-reports` shows the card → "Schedule export" dialog → created
  `daily_cdr_export` (CSV, cron) → "Run now" → verified in localStorage: job `lastStatus: SUCCESS`,
  a `report-artifact` row with the **real** tile data (`tile_index,widget_id,span / 0,w1,1 / 1,w2,2`),
  and exactly one REPORT_EXPORTED notification. 0 console errors.
- **Regression caught during verification**: the pre-existing seeded `weekly_billing` job (`type:
  'report'`, old-style `params.report`) was being swallowed by the new export special-case and its
  trigger test started failing. Fixed by keying the dispatch on `params.dashboardId` instead of
  `type` — see the handler comment.

## Follow-ups
- PDF/PNG remain placeholder text, not real renders — acceptable for a mock-first track; a real
  render pass is backend/Standard-edition scope.
- `dashboardsApi`/`jobsApi` double round-trip on load (`list()` + per-job `get()`) is a minor
  inefficiency; fine at this data scale, revisit if the Jobs list grows large.
- This closes P2 item **C6**; **C5 (Link Analysis studio)** remains the last plan P2 item.
