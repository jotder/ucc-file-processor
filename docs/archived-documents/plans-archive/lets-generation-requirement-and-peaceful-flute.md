# Scheduler (Operations) — requirement + implementation plan

## Context
Operators need a single place to see and manage **every scheduled job**: what's scheduled, when it next
fires, its run history/results, and any logs/events — plus manage it (enable/disable, reschedule, edit,
delete, create, run-now). Today this lives half-built under **Pipelines → Jobs** (`/jobs`): it already lists
configured jobs (name/type/cron/on-pipeline/enabled/last-status/last-run/next-fire), shows run history in a
dialog, runs-now, and has a reporting/metrics mode — backed by a **real cron `Scheduler`** + `JobService` +
`GET /jobs`, `GET /jobs/{name}/runs`, `POST /jobs/{name}/trigger`. The gaps vs the ask are a **dedicated detail
view**, **per-run logs/events**, and the **edit/delete/reschedule/enable-disable/create** actions — none of
which exist server-side yet, and jobs are not mocked (they hit the real backend).

## Decisions (locked with user)
1. **Evolve & relocate** — the Scheduler IS the evolved Jobs feature. Move the nav entry from Pipelines →
   **Operations** (title **"Scheduler"**), keep the `modules/admin/jobs/` dir + `/jobs` route (internal name
   unchanged to avoid churn), add a `/jobs/:name` detail route, keep the existing list + run-history + reporting
   mode. Single source of truth; no duplication.
2. **Mock-first prototype** — add a `jobs-mock.interceptor.ts` (mirrors `studio-mock`/`flow-mock`/`ops-mock`)
   seeding configured jobs + runs + logs/events and serving the **new** write/logs endpoints. Build the full
   clickable UI now; real Java endpoints are a follow-on (the mock mirrors the intended contracts → swap = flag
   flip).
3. **Actions** — enable/disable, reschedule, edit params/config, delete, create (+ run-now already exists).

## Scope / file plan (all under `inspecto-ui/`, additive + small edits — mock-first)

### A. Backend-shaped mock + service (the contracts)
- **NEW `src/app/inspecto/api/jobs-mock.interceptor.ts`** — env-gated `mockJobs`, registered in
  `app.config.ts` (before any generic catch-all). In-memory store seeded with ~6 varied jobs (cron jobs,
  event-driven `on-pipeline`, enabled + disabled, mixed last-status) + per-job run history + per-run log lines.
  Serves, regex-on-path-suffix (space-prefix agnostic, same pattern as `studio-mock`):
  - existing: `GET /jobs`, `GET /jobs/{name}/runs`, `POST /jobs/{name}/trigger` (now also appends a synthetic run + logs).
  - **new**: `GET /jobs/{name}` (single JobView), `POST /jobs` (create), `PUT /jobs/{name}` (edit),
    `DELETE /jobs/{name}`, `POST /jobs/{name}/enable` + `/disable`, `POST /jobs/{name}/reschedule` (body
    `{cron}` → recompute a plausible `nextFire`), `GET /jobs/{name}/runs/{runId}/logs` →
    `{ logs: JobLogLine[], events: JobEvent[] }`.
- **EDIT `src/app/inspecto/api/jobs.service.ts`** — add methods + interfaces (declare inline, export via the
  `api/index.ts` barrel): `get(name)`, `create(body)`, `update(name, body)`, `remove(name)`,
  `setEnabled(name, enabled)`, `reschedule(name, cron)`, `runLogs(name, runId)`. New types `JobLogLine
  {ts, level, message}`, `JobEvent {ts, type, message}`, `JobUpsert` (editable shape: name/type/cron/onPipeline/
  enabled/params). Reuse existing `JobView`/`JobRun`/`JobRunRow`/`JobMetrics` (in `models.ts`).
- **EDIT `src/environments/environment.ts`** — `mockJobs: true`.

### B. Navigation + routes (relocate)
- **EDIT `mock-api/common/navigation/data.ts`** — remove the Jobs item from `pipelines-group`; add to
  `operations-group`: `{ id:'scheduler', title:'Scheduler', icon:'heroicons_outline:clock', link:'/jobs' }`.
- **EDIT `app.routes.ts`** — add `{ path:'jobs/:name', loadChildren: () => import('app/modules/admin/jobs/job-detail/job-detail.routes') }` after the existing `jobs` route.

### C. List enhancements — `modules/admin/jobs/jobs.component.ts`(+html) (evolve in place)
- Keep the Schedules/Reporting toggle + reporting mode untouched. In **Schedules** mode:
  - Clarify columns to answer "what is scheduled" + "schedule": **Job** (name) · **What's scheduled** (derived
    summary from type+params, e.g. `Report · daily_summary`, `Flow · cdr_ingest`) · **Schedule** (cron, or
    `on <pipeline>` for event-driven) · **Next fire** (`fmtDateTime`) · **Enabled** (`status-badge`) ·
    **Last result** (`status-badge`) · **Last run** (`fmtDateTime`).
  - Row click → `router.navigate(['/jobs', name])` (the new detail).
  - Row actions (`InspectoRowAction`, `inspecto/grid`): Run now (exists) · Reschedule · Edit · Enable/Disable
    (icon+label by state) · Delete (`InspectoConfirmService.confirmDestructive`).
  - Header: **New job** button → `JobFormDialog` (create).

### D. Detail view — NEW `modules/admin/jobs/job-detail/` (route `/jobs/:name`)
Follows the **pipeline-detail** pattern (breadcrumb + `MatTabs`, per-tab lazy load):
- `job-detail.component.ts`(+html) + `job-detail.routes.ts`.
- Header: breadcrumb `Scheduler / {name}`, `<h1>`, an `<inspecto-status-badge [value]="enabled?'enabled':'disabled'">`,
  and action buttons (Run now · Reschedule · Edit · Enable/Disable · Delete) — same handlers as the list.
- Tabs:
  1. **Schedule** — overview card: type, what's-scheduled, cron (+ raw + next-fire), on-pipeline trigger,
     enabled, catch-up, params (key/value list). `<inspecto-empty-state>`/`<inspecto-skeleton>` while loading.
  2. **Execution history** — `<inspecto-data-table tier="standard">` over `runs(name)` (cols: started · status
     badge · trigger · duration `fmtDuration` · message). Row click → select run (drives the Logs tab) or open
     the existing `JobRunDetailDialog`. Run-now here too.
  3. **Logs & events** — a run selector (default latest run) → `runLogs(name, runId)` rendered as a log list
     (`JobLogLine` level+message) + an events list; **live-tail** via `visibleInterval(5000)` +
     `takeUntilDestroyed` while the run is `RUNNING`.

### E. Create/Edit/Reschedule dialog — NEW `modules/admin/jobs/job-form.dialog.ts`(+html)
- Reactive form (mirrors `connections/connection-form.dialog.ts`): `name` (locked on edit), `type`
  (ingest/enrich/report/maintenance/flow), **schedule mode** (`cron` | `on-pipeline`) → `cron` text field
  (light validation: 5–6 space-separated fields; a few presets: hourly/daily/weekly) **or** `onPipeline`
  select, `enabled` toggle, `params` key/value editor. Create vs edit by presence of a job in `MAT_DIALOG_DATA`.
  **Reschedule** opens the same dialog (cron focused). Save → `create`/`update`/`reschedule` → toast → refresh.
  503 → `<inspecto-alert variant="warning">` "writes disabled" (consistent with other write dialogs).

### F. Specs (vitest + a11y, mock-served, no TestBed for pure bits)
- `jobs.component.spec` (extend), `job-detail.component.spec` (tabs render, a11y, mocked `JobsService`),
  `job-form.dialog.spec` (create/edit seeding, cron validation, a11y). `expectNoA11yViolations` on each new
  component spec.

## Reused components/utilities (do NOT re-roll)
- `DataTableComponent` (`inspecto/data-table`) — list + history + logs tables. `InspectoRowAction` +
  `actionsColumn` + `fmtDateTime` (`inspecto/grid`). `fmtDuration` (already in `jobs.component`).
- `inspecto/components/`: `status-badge` (enabled / run status), `empty-state`, `alert`, `skeleton`.
- `InspectoConfirmService.confirmDestructive` (`inspecto/confirm.service`) — delete.
- `visibleInterval` + `apiErrorMessage` (`inspecto/api`) — live-tail + error toasts.
- Existing `JobRunsDialog` / `JobRunDetailDialog` (`modules/admin/jobs/`) — reuse for the run detail.
- Patterns: `pipeline-detail.component` (breadcrumb+tabs+`:id` detail), `connection-form.dialog` (form dialog),
  `studio-mock.interceptor` (mock shape + registration), `dashboards`/`charts` (list→detail→actions).

## Cron / next-fire note
No client-side cron engine. The form validates field count + offers presets; **next-fire is server-computed**
(real `CronExpression`/`Scheduler` today; the mock returns a plausible `nextFire` on create/reschedule). A
human-readable cron description is **out of scope** for v1 (show the raw cron + next-fire).

## Verification
- DoD: `npm --prefix ./inspecto-ui run lint:tokens` · `run build` · `run test:ci` all green (+ new specs incl.
  a11y). (build-verify skill / verify-runner.)
- Live (own preview, `mockJobs:true`, SPA nav to keep the in-memory store): **Operations → Scheduler** lists
  seeded jobs with What's-scheduled/Schedule/Next-fire/Enabled/Last-result; row → detail with the 3 tabs;
  Run now appends a run + logs; Reschedule edits cron → next-fire updates; Edit changes params; Enable/Disable
  toggles the badge; Delete (confirm) removes it; New job creates one; Logs & events tab renders log lines +
  live-tails a RUNNING run. 0 console errors. Screenshot the detail page.

## Out of scope / follow-on (real backend)
Add the Java endpoints the mock mirrors — `JobRoutes`/`JobService`: `POST /jobs` (create), `PUT /jobs/{name}`
(edit), `DELETE /jobs/{name}`, `POST /jobs/{name}/{enable|disable}`, `POST /jobs/{name}/reschedule`, and
per-run logs (`GET /jobs/{name}/runs/{runId}/logs`) — incl. persisting config edits to TOON + re-arming the
live `Scheduler`. Flip `mockJobs:false` to cut over. Human-readable cron descriptions; per-run stdout capture.
