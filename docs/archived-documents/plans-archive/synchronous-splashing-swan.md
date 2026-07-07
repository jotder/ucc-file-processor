# Plan — Acquisition / Sources UI (inspecto-ui) + supporting backend

## Progress (in flight — approved plan, continuing)
- **Backend DONE + reactor green** (core +6 new tests / agent 157 / hosted 4 / connectors 32): `GET /sources`
  (`SourceService.sources()` + route), `GET /metrics/acquisition` (`MetricRegistry.snapshot()` + route),
  connections CRUD (`POST/PUT/DELETE /connections`, write-root gated, secret-mask preservation, in-use 409),
  CORS methods extended, `put`/`delete` route helpers, `ControlApiSourcesAndConnectionsTest` (6 tests).
- **Frontend DONE + `ng build` green**: `SourcesService`, `AcquisitionMetricsService`, `ConnectionsService`
  create/update/remove; Sources pane (`modules/admin/sources/`: component/html, metrics strip + chart, detail
  dialog, routes); Connections editing (`connection-form.dialog.ts` + New/Edit/Delete + 503 notice); `sources`
  nav item + lazy route.
- **REMAINING:** (1) e2e verify via preview servers (load `/sources`, source detail, connections create→edit→
  test→delete; screenshots); (2) `graphify update .`; (3) commit/push — only on explicit ask (standing rule).
- Note (frontend agent deviation, acceptable): no "writes enabled" capability endpoint exists, so the
  connections mutate actions stay visible until the first 503 is observed, then hide + show the notice.

## Context

Data Acquisition & File Collection is feature-complete for this release (backend connectors, dedup, watermarks,
retry/circuit-breaker, post-actions). None of it is yet surfaced in the SPA as a coherent operator view. The user
wants an **Acquisition / Sources** experience: a list of configured sources with their acquisition settings
(connector, connection, dedup mode, incremental/row-level watermark, fetch parallelism/rate, guarantee), the
**live acquisition metrics** (files discovered/downloaded/failed, watermark-skipped, bytes, active connections),
and **editable connection profiles**.

**Decisions (confirmed with the user 2026-06-15):**
- **Metrics depth:** global JSON metrics + Chart.js panels (no per-source metric labelling this cut).
- **Connections:** add full create/edit/delete (UI-driven config write-back) — new for this system.

### What exists today (from exploration)
- **Backend** (`com.gamma.control.ControlApi`): `GET /connections`, `GET /connections/{id}`,
  `POST /connections/{id}/test` (CONTROL scope). `GET /pipelines`, `GET /pipelines/{name}/pending`
  (`InboxStatus{pipeline,inbox,pending,running,current}`), trigger/pause/resume, batches/files/quarantine/report.
  **Config write-back already exists** — `writeConfig` (`ControlApi.java:592`, `POST /config/write`) and
  `createPipeline` (`:688`) write `.toon` atomically (temp+move) via `ConfigCodec.toToon`, gated behind
  `-Dassist.write.root`, with spec+safety validation; tests `ControlApiConfigWriteTest`/`ControlApiPipelineCreateTest`.
  Metrics are **Prometheus text only** — `MetricRegistry.scrape()` (`MetricRegistry.java:84`), `GET /metrics` (PUBLIC).
  **Missing:** any `/sources` endpoint, a JSON metrics endpoint, and connection create/update/delete.
- **Frontend** (`inspecto-ui/`): card-grid Connections pane (`modules/admin/connections/`, list + Test, renders
  options/tunnel) on `ConnectionsService` (`inspecto/api/connections.service.ts`). Canonical ag-Grid list pane =
  `modules/admin/jobs/` (uses `app/inspecto/grid`: `INSPECTO_DEFAULT_COL_DEF`, `actionsColumn`, `fmtDateTime`,
  `refreshActionsCells`, `InspectoGridThemeService`; the `(firstDataRendered)`/`(rowDataUpdated)`→refresh quirk).
  Tabbed detail = `modules/admin/pipeline-detail/`. Charts = `dashboard/` via `inspecto/components/chart.component.ts`
  (`InspectoChartComponent`) + `inspecto/theme/chart-tokens.ts` (`CHART_SERIES`). API pattern in `inspecto/api/`
  (`api-base.ts` `apiUrl`/`toParams`, barrel `index.ts`, `@Injectable` + `inject(HttpClient)` + `Observable<T>`).
  Nav = `mock-api/common/navigation/data.ts`; routes = `app.routes.ts` (lazy `loadChildren` under `inspectoAuthGuard`).

## Backend changes (`inspecto/`)

**1. `GET /sources` (CONTROL)** — flatten each loaded pipeline's source config into a JSON list. Add
`SourceService.sources()` iterating loaded `PipelineConfig`s (via the same registry `pipelines()` uses) and a
`ControlApi` route. Each row: `{pipeline, id, connector, connection, includes, excludes, duplicateMode,
duplicateOnChange, guarantee, incrementalWatermark, fetchParallel, fetchRateLimit, postAction, dbWatermarkCurrent}`.
For `db` sources with row-level watermarking, fill `dbWatermarkCurrent` from
`AcquisitionLedgers.shared().dbWatermark(connectionId)` (the method shipped in `4e40a76`). Pure config read — no I/O.

**2. `GET /metrics/acquisition` (CONTROL)** — add `MetricRegistry.snapshot(Predicate<String> nameFilter)` next to
`scrape()`: runs collectors, then returns structured `Map<String,Object>` (counters/gauges → name → list of
`{labels, value}`; histograms → name → `{sum, count, buckets}`). `ControlApi` route filters to the acquisition
names (`inspecto_files_discovered_total`, `_files_downloaded_total`, `_downloads_failed_total`,
`_post_actions_failed_total`, `_watermark_skipped_total`, `_bytes_transferred_total`, `_fetch_seconds`,
`_active_connections`) and returns JSON. (Per-source labelling is explicitly out of scope; values are global /
connector-labelled as already emitted.)

**3. Connections CRUD (CONTROL), mirroring `writeConfig`/`createPipeline`** — gated behind `-Dassist.write.root`
(503 when unset, so it's safe-by-default and the UI degrades). Reuse `ConnectionProfile.fromMap`/`toMap`,
`ConfigCodec.toToon`, the atomic temp+move write, and `ConnectionRegistry`:
- `POST /connections` — body = connection map; validate `id` (safe-token regex like writeConfig) + required
  fields per connector; write `<id>_connection.toon` under the write root; `ConnectionRegistry.register`; 409 if id
  already exists.
- `PUT /connections/{id}` — overwrite the file + re-register. **Secret preservation:** secrets are references only
  (`${ENV:…}`); if an incoming secret-ish value is the mask sentinel `***`, keep the stored value rather than
  clobbering it (reuse the `toMap` secret-key heuristic).
- `DELETE /connections/{id}` — refuse with **409** if any loaded pipeline source's `connection` references the id;
  else delete the file + `ConnectionRegistry.remove`.

## Frontend changes (`inspecto-ui/`)

**4. API clients** (`src/app/inspecto/api/`, export from `index.ts`):
- `SourcesService.list(): Observable<SourceView[]>` → `GET /sources`.
- `AcquisitionMetricsService.get(): Observable<AcquisitionMetrics>` → `GET /metrics/acquisition`.
- Extend `ConnectionsService` with `create(p)`, `update(id,p)`, `remove(id)` (reuse existing `list/get/test`).

**5. Sources pane** (`src/app/modules/admin/sources/`) — copy the `jobs/` ag-Grid skeleton. Columns: pipeline,
source id, connector, connection, dedup mode, watermark (incremental + `dbWatermarkCurrent`), fetch parallel/rate,
guarantee; `actionsColumn` = Run now (→ `PipelinesService.trigger(pipeline)`), View detail. Above the grid, an
**acquisition-metrics strip**: summary cards + 1–2 `InspectoChartComponent` panels (e.g. discovered vs downloaded
vs failed; bytes transferred) fed from `AcquisitionMetricsService` using `CHART_SERIES`/`canvasTheme`. A source
**detail dialog** (MatDialog, like the existing `*.dialog.ts`) shows full source config + bound connection (link to
`/connections`) + current watermark + live `PipelinesService.pending(pipeline)` status. Honour `(firstDataRendered)`
/`(rowDataUpdated)`→`refreshActions`.

**6. Connections editing** — extend `modules/admin/connections/`: a **connection form dialog** (id, connector
dropdown [`local`/`sftp`/`ftp`/`ftps`/`db`], host/port/database/basePath/username, password as a `${ENV:…}`
reference with a hint, an options key/value editor, optional tunnel sub-form) + per-card Edit/Delete and a "New
connection" button wired to the CRUD methods. Validate that secret fields are references, not raw secrets. When the
backend reports the write root is disabled (503), hide the mutate actions and show a notice (read-only fallback).

**7. Nav + routes** — add a `sources` nav entry (`navigation/data.ts`, e.g. `heroicons_outline:inbox-arrow-down`)
and a lazy route in `app.routes.ts` (`sources/sources.routes.ts`, mirroring `jobs.routes.ts`). Connections route
already exists.

## Tests

- **Backend** (mirror `ControlApiTest`/`ControlApiConfigWriteTest`): `GET /sources` shape (incl. `dbWatermarkCurrent`
  for a db source); `GET /metrics/acquisition` JSON after recording a couple of metrics; connections CRUD round-trip
  (create→list→get→update→delete) under a temp write root, `DELETE` in-use → 409, secret-mask preservation on update,
  CRUD → 503 when the write root is unset. Add `MetricRegistry.snapshot` unit coverage.
- **Frontend:** `ng build --configuration development` (the project's standard type-check). Keep components thin so
  the type-check is the gate.

## Verification

- Backend reactor: `JAVA_HOME=… mvn -o clean test` (expect green; new ControlApi/MetricRegistry tests pass).
- End-to-end via the preview servers in `.claude/launch.json` (backend ControlApi :8080 token `dev`, config
  `inspecto/config`, with `-Dassist.write.root` set inside that tree; UI `ng serve` :4204, proxy `/api`→:8080).
  Use the `preview_*` MCP tools: load `/sources` (grid + metrics panel render), open a source detail dialog, and
  on `/connections` create → edit → test → delete a profile; screenshot the grid + metrics + connection form.
  Paste the server token on `/connect` (sessionStorage key `inspector.control.token`).

## Suggested staging (two commits)

1. **Backend API:** `GET /sources` + `GET /metrics/acquisition` (`MetricRegistry.snapshot`) + connections CRUD + tests.
2. **UI:** SourcesService/AcquisitionMetricsService, Sources pane + metrics panel + detail dialog, Connections
   create/edit/delete, nav/routes.

## Guardrails

- Never stage `inspecto/pom.xml`; never commit `SESSION_STATUS.local.md`/`*.local.md`. No commit/push without an
  explicit ask. Connections CRUD stays gated behind `-Dassist.write.root` (safe-by-default). Secrets remain
  references only — the UI must never round-trip a raw secret. Keep core lean; no new deps (Chart.js, ag-Grid,
  Material already present). `graphify update .` after code changes.
