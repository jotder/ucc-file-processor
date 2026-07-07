# UCC File Processor — Operator UI (DevExtreme Angular)

## Context

The UCC File Processor (`C:/sandbox/ucc-file-processor`, v4.1.0-SNAPSHOT) is a backend-only ETL
platform with a complete REST control plane (~30 routes on the JDK `HttpServer` in
`com.gamma.control.ControlApi`, no Spring) but **no operator UI**. Operators today drive it with
`curl`/CLI. The goal is a web console — built on the **DevExtreme Angular template**
(`https://github.com/DevExpress/devextreme-angular-template.git`) with **devextreme-angular**
components — that exposes **every endpoint** and covers **all features required to operate the
platform**: monitor pipelines/batches, trigger/pause/resume/reprocess, schedule jobs, watch
enrichment, browse the data catalog + lineage graph, author & validate configs, review failure
diagnoses, and drive the AI assist skills.

Two integration facts (confirmed) shape the plan:
1. The core serves **JSON** (Jackson) and **no CORS / no static files** today. We add both to
   `ControlApi` using **pure JDK APIs only** (no new runtime deps — preserves lean-core).
2. Auth is **scoped bearer tokens** (`PUBLIC` / `CONTROL` / `ASSIST_READ` / `ASSIST_WRITE`),
   fail-closed, supplied via `-D` system properties. There is no login endpoint — the UI "login"
   is the operator pasting their token(s), attached to API calls via an HTTP interceptor.

## Decisions locked (from user)
- **Platform:** UCC File Processor (not CVVE).
- **Stack:** DevExtreme Angular template as the base shell + devextreme-angular components.
- **Integration:** add zero-dep backend support — CORS (for local dev) + static SPA serving (for
  prod) inside `ControlApi`. One deploy bundle serves API + UI.
- **Location:** new top-level **`ui/`** folder in the monorepo, alongside `file-processor` /
  `file-processor-agent`.
- **Coverage:** all endpoints, all operator features.

## Guardrails (standing)
- **No commit / push / tag without an explicit ask.** Each phase ends on green builds.
- **Lean core:** `file-processor` stays free of Spring/kernel/heavyweight deps. Backend changes use
  only `com.sun.net.httpserver.*` + `java.nio` (already imported in `ControlApi`). The CI lean-core
  guard must still pass.
- The Angular toolchain (Node/npm) is **not** pulled into the Maven reactor or the CI test job —
  the UI is its own npm build, bundled into the deploy zip by `package.ps1` and served from disk.
- Never stage `run-adjustment.bat`; leave the user's in-flight files untouched.

## Architecture & integration model

```
 ┌────────────────────────┐        dev: ng serve :4200 ──proxy──▶ :8080
 │  Angular SPA (ui/)      │        prod: ControlApi serves /ui dist (static, PUBLIC)
 │  DevExtreme components  │ ───────────────────────────────────────────────┐
 └────────────────────────┘   HTTP + Bearer token (interceptor)             │
                                                                            ▼
                                            ┌──────────────────────────────────────┐
                                            │ ControlApi (JDK HttpServer, :8080)     │
                                            │  + CORS (dev, prop-gated)              │
                                            │  + static SPA fallback (prod)          │
                                            │  ~30 scoped JSON routes (unchanged)    │
                                            └──────────────────────────────────────┘
```

- **Dev:** `ng serve` on :4200 with `proxy.conf.json` forwarding the API route paths to
  `http://localhost:8080` → same-origin, no CORS needed. CORS support is still added for the case
  of a separately-hosted dev SPA.
- **Prod:** `package.ps1` runs `ng build` and drops `dist/` next to the jar; `ControlApi` serves it
  (resolved via `-Dui.dir`, default `./ui` beside the jar; optional classpath `static/` fallback).
  Static assets are **PUBLIC** so the app shell loads before the operator supplies a token.

## Phase 0 — Backend enablers (Java, lean-core, zero new deps)

**File:** `file-processor/src/main/java/com/gamma/control/ControlApi.java`

1. **CORS (prop-gated, default off → prod unchanged).** Read `-Dcontrol.cors` (e.g.
   `http://localhost:4200`, or `*`). In `dispatch()`, before route matching: if an `Origin` header
   is present and CORS is enabled, set `Access-Control-Allow-Origin/-Methods/-Headers` on the
   response; if the method is `OPTIONS`, short-circuit with `204` (preflight). Add the same ACAO
   header inside `respond()`/`respondText()` so it rides every response. When `-Dcontrol.cors` is
   unset, behavior is byte-for-byte identical to today.
2. **Static SPA serving (PUBLIC fallback).** Add a final fallback in `dispatch()` reached only when
   no API route matched **and** method is `GET`: resolve `-Dui.dir` (or classpath `static/`); serve
   the requested file with a correct `Content-Type` (small extension→MIME map: html/js/css/svg/json/
   woff2/png/ico); for a path with no file + no extension (SPA deep link) serve `index.html`;
   otherwise `404` JSON. Guard against path traversal (normalize + confine under the root). API
   404s (paths under known prefixes like `/pipelines`, `/assist`, …) keep returning JSON, not
   index.html — distinguish by "did any route pattern match the path" (the existing `pathMatched`
   flag already separates 404 vs 405; extend so an unmatched **GET** tries static before the JSON
   404).
3. **Tests:** `ControlApiStaticAndCorsTest` (new) — (a) OPTIONS preflight returns 204 + ACAO when
   CORS enabled and is absent when disabled; (b) a known route still returns its JSON; (c) GET `/`
   serves index.html from a temp `-Dui.dir`; (d) GET an unknown extensionless path serves
   index.html (SPA fallback); (e) GET `/pipelines/...` for an unknown pipeline still returns JSON
   404, not HTML; (f) static assets need no token (PUBLIC) while `/pipelines` still 401s without one.

**Build/deploy:** update `file-processor/package.ps1` to `ng build --configuration production` in
`ui/` and copy `ui/dist/<app>/` into the deploy bundle as `ui/`, and have the generated run script
pass `-Dui.dir=./ui`. (No change to the Maven reactor; no Node in the `mvn test` CI job.)

## Phase 1 — UI foundation (`ui/`)

- **Scaffold:** clone the DevExtreme Angular template into `ui/`; adopt its pinned Angular +
  DevExtreme versions, side-nav layout, theming, and auth scaffold. Strip demo pages. (DevExtreme is
  commercial — the template runs on the trial; a license is required for production.)
- **Environments:** `environment.ts` with `apiBaseUrl` (dev `''` behind proxy; prod relative);
  `proxy.conf.json` → `:8080`.
- **Token auth (replaces the template's user/pass login):** a "Connect" screen where the operator
  pastes a **Control** token and/or **Assist** token; store in `sessionStorage`; an
  `HttpInterceptor` attaches `Authorization: Bearer <token>`; a `401` interceptor routes back to
  Connect with a message. A small `AuthService` tracks which scopes are held (so the UI can disable
  CONTROL-only actions when only an assist token is present).
- **Typed API client layer:** one Angular service per endpoint group
  (`PipelinesApi`, `ReportsApi`, `JobsApi`, `EnrichmentApi`, `CatalogApi`, `ConfigApi`,
  `AssistApi`, `HealthApi`) + TypeScript interfaces mirroring the backend DTOs
  (`PipelineView`, `StatusReport`, `ServiceReport`, `BatchAuditReport`, `JobView`, `JobRun`,
  `EnrichmentJobView`, `EnrichmentRunReport`, `MetadataNode`, `MetadataEdge`, `MetadataGraph`,
  `ConfigSpec`, `FieldSpec`, `Finding`, `Diagnosis`, `AssistRequest`, `AssistResult`).
- **Shell:** side-nav grouping the sections below; a global "agent unavailable" awareness (assist
  routes may 503 when `file-processor-agent` is absent → assist UI degrades gracefully).

## Phase 2 — Monitoring core

| Screen | Endpoints | DevExtreme components |
|---|---|---|
| **Dashboard** | `GET /ready`, `/status`, `/report`, `/metrics` | Tiles + `dxChart`/`dxCircularGauge`: ready + pipeline count, paused count, totals, error rate, p50/p95/p99; per-pipeline status cards |
| **Pipelines** | `GET /pipelines`; `POST /pipelines/{n}/trigger`, `/pause`, `/resume`, `/reprocess`; `POST /trigger` | `dxDataGrid` (name, configPath, paused, committedBatches) with row-action buttons; "Run all" toolbar; reprocess dialog (batchId) |
| **Pipeline detail** | `GET /pipelines/{n}/{commits,batches,files,lineage,quarantine,report}` | Tabbed (`dxTabPanel`): Batches / Files / Lineage (filter by `batchId`) / Quarantine / Commits grids; **Report** tab with `from`/`to` date range → percentiles + charts; drill batch→files/lineage; reprocess from a batch row |

Metrics: parse the Prometheus text exposition client-side into a few series for the dashboard
charts (lightweight parser; raw view available as a fallback).

## Phase 3 — Scheduling & enrichment

| Screen | Endpoints | Notes |
|---|---|---|
| **Jobs** | `GET /jobs`, `/jobs/{n}/runs`; `POST /jobs/{n}/trigger` | Grid (type, cron, onPipeline, enabled, lastStatus, nextFire); run-history detail; trigger-now. "New schedule" launches the `nl-to-schedule` assist flow (draft `.toon` + `nextRuns` + findings) |
| **Enrichment** | `GET /enrichment`, `/enrichment/{j}/runs`, `/lineage`, `/report` | Grid of Stage-2 jobs; per-job runs, lineage (filter by `runId`), report (date range + percentiles) |

## Phase 4 — Catalog & configuration

| Screen | Endpoints | Components |
|---|---|---|
| **Catalog — tables** | `GET /catalog`, `/catalog/tables/{id}` | Grid of `MetadataNode`s (kind, label, overlay freshness/rowCount/completeness); node-detail drawer (node + 2-hop neighbors) |
| **Catalog — KPIs** | `GET /catalog/kpis` | Grid (definition, grain, joinKeys, inputs) |
| **Catalog — graph** | `GET /catalog/graph?from=&depth=&direction=&kinds=&edgeKinds=&overlay=` | Interactive graph (`dxDiagram` or a graph lib): traverse from a node; click → detail |
| **Config authoring** | `GET /config/spec/{type}`, `POST /validate` | **Spec-driven `dxForm`**: render fields from `FieldSpec` (type/required/options/min/max/description) per type (pipeline/enrichment/job/schema/meta); cross-field rules as hints; **Validate** (draft: `{type,config,safety?}`; saved file: `{configPath}`) → render `Finding`s inline (severity/fieldPath/message); `.toon` preview (Monaco/textarea, read-only export). **Note:** no write-to-disk endpoint today, so authoring is draft → validate → copy/download; the operator commits the file manually (backend gap below). |

## Phase 5 — Diagnostics & AI assist

| Screen | Endpoints | Notes |
|---|---|---|
| **Diagnoses** | `GET /assist/diagnoses?limit=` | Grid (batchId, pipeline, severity, rootCause, heuristicOnly, time, citations); detail shows suggested alert-rule `.toon` → hand to diagnose-and-alert |
| **Assist console** | `POST /assist/{intent}` for all 7 intents | A reusable assist panel + contextual embeds. Each renders `AssistResult` (answer, confidence, citations, links, rationale) plus intent-specific `data`: **kpi-to-sql / report-sql** → SQL + sample-rows grid; **nl-to-schedule / suggest-config / diagnose-and-alert** → draft `.toon` + findings + (schedule) nextRuns; **explain-entity** → grounded prose (launched from a catalog node); **report-narrative** → narrative over a report JSON. All draft-only (`applyVia` is null in MVP). Graceful 404 (unknown intent) / 503 (agent absent) handling. |

Contextual entry points: explain-entity from Catalog; nl-to-schedule from Jobs; suggest-config from
Config authoring; diagnose-and-alert from Diagnoses; report-narrative from any Report.

## Phase 6 — Polish & cross-cutting

- Live refresh/polling for Dashboard, Pipelines, Jobs (configurable interval; pause when hidden).
- Scope-aware UI (hide/disable CONTROL-only actions when only an assist token is held).
- Consistent loading/empty/error states; toast notifications on actions; confirm dialogs for
  trigger/pause/reprocess.
- Responsive layout + DevExtreme theme switch (light/dark).
- Lint + unit tests for services + key components; a thin e2e smoke against a running backend.

## Critical files

**Backend (modify):**
- `file-processor/src/main/java/com/gamma/control/ControlApi.java` — CORS + static serving.
- `file-processor/src/test/java/com/gamma/control/ControlApiStaticAndCorsTest.java` — new tests.
- `file-processor/package.ps1` — build + bundle `ui/dist` into the deploy zip; run script gets `-Dui.dir`.

**Frontend (create, under `ui/`):**
- Template scaffold (from the DevExtreme template repo): `package.json`, `angular.json`,
  `src/app/**`, layout/auth shell, themes.
- `src/environments/*.ts`, `proxy.conf.json`.
- `src/app/core/`: `auth.service.ts`, `auth.interceptor.ts`, `error.interceptor.ts`, API services
  (`pipelines/reports/jobs/enrichment/catalog/config/assist/health`), `models/*.ts` (DTO interfaces).
- `src/app/pages/`: `dashboard`, `pipelines` (+ `pipeline-detail`), `jobs`, `enrichment`,
  `catalog` (tables/kpis/graph), `config-authoring`, `diagnoses`, `assist`.
- `.github/workflows/ui.yml` — separate UI workflow: `npm ci` + `ng lint` + `ng build` + unit tests
  (Node toolchain isolated from the Java `ci.yml`).

## Reused, not rebuilt
- All ~30 `ControlApi` routes and their DTOs (`PipelineView`, `StatusReport`, `ServiceReport`,
  `BatchAuditReport`, `EnrichmentRunReport`, `JobView`/`JobRun`, `MetadataNode/Edge/Graph`,
  `ConfigSpec`/`FieldSpec`, `Finding`, `Diagnosis`, `AssistRequest`/`AssistResult`).
- The `/config/spec/{type}` endpoint already exists specifically to drive **UI form rendering** —
  the config-authoring screen consumes it directly (no hand-written forms per type).
- The existing scoped-auth model and `respond()`/`respondText()` writers in `ControlApi` (CORS
  header added in one place).

## Backend gaps surfaced (not blockers; flagged for later, possibly via `ASSIST_WRITE`)
- **No write endpoint** to persist a config/schedule/alert draft to disk — authoring is
  draft+validate+download today; a future `ASSIST_WRITE`-scoped save would close the loop.
- **No pipeline-create** endpoint (pipelines are discovered from on-disk `.toon`); the UI can author
  + validate a draft but not register it live.
- `/metrics` is Prometheus text only (no structured JSON) — parsed client-side for dashboard charts.
- Alert rules are draft-only (no live alert engine yet) — the UI manages drafts, not firing rules.

## Verification
- **Backend:** `cd C:/sandbox/ucc-file-processor && mvn -q test` green (incl. new
  `ControlApiStaticAndCorsTest`); CI **lean-core guard still passes** (no new deps in
  `file-processor`); existing ControlApi tests unchanged.
- **Frontend dev:** start backend
  `java -Dcontrol.token=dev -Dassist.read.token=dev -Dcontrol.cors=http://localhost:4200 -cp file-processor.jar com.gamma.control.ControlApi <config dir>`;
  `cd ui && npm ci && ng serve`; connect with the dev token; exercise each screen against live data.
- **Frontend build/lint/test:** `ng lint`, `ng build --configuration production`, `ng test`.
- **Prod static serving:** build the deploy bundle via `package.ps1`, run the jar with `-Dui.dir=./ui`
  (no `-Dcontrol.cors`), confirm the SPA loads at `http://localhost:8080/`, deep links resolve to
  index.html, the app prompts for a token, and authenticated API calls succeed.
- **e2e smoke:** trigger a pipeline, watch a batch land in the detail grid, open a diagnosis, run a
  kpi-to-sql assist call and see SQL + sample rows.

## ▶ EXECUTION STATUS (resume here — paused 2026-06-08)

> **DIR RENAME 2026-06-09:** the UI source folder was renamed **`ui/` → `inspector-ui/`** (all the `ui/...`
> source paths below now live under `inspector-ui/`). Updated refs: `file-processor/package.ps1` step 1b
> (`$uiDir` → `inspector-ui`; also fixed its build to **pnpm** `install --frozen-lockfile` + `run build`, was
> `npm ci`/`npm run build` which would fail — only `pnpm-lock.yaml` exists), `.github/workflows/ui.yml`
> (working-directory + path filters + cache-dependency-path), and `inspector-ui/README.md`. The **deployed** bundle
> dir stays `./ui` (runtime, served via `-Dui.dir=./ui`) — unchanged. After the move, `pnpm` needed a one-time
> `pnpm install` to reconcile node_modules (CI is unaffected: fresh checkout + `CI=true`). Prod build green from the
> new path. **Per-file in-flight tracker: explicitly POSTPONED by user** (only matters for very large / stuck files).

> **COMMITTED + PUSHED 2026-06-09:** two commits on `4.x` → `origin/4.x` = `518f146`. `e8fc34e` feat(control): CORS +
> static SPA serving + `/pipelines/{n}/pending` + SourceProcessor/SourceService + 2 tests (lean-core, JDK-only).
> `518f146` feat(inspector): the whole `inspector-ui/` SPA (122 files) + `.github/workflows/ui.yml` + `package.ps1`
> bundling + `.gitignore` housekeeping. Removed the template's leftover `inspector-ui/.github/` workflows. `pom.xml`
> (in-flight) left unstaged. **Both CI workflows GREEN** on the pushed commit: UI (pnpm prod+dev build, run 27181152108)
> and Java CI (lean-core guard + tests, run 27181152096).
>
> **LIVE SMOKE TEST 2026-06-09 — ✅ PASSED.** Booted real `ControlApi` (Java 26, target/classes + full runtime cp)
> serving the actual built `inspector-ui/dist/DevExtreme-app/browser` bundle via `-Dui.dir`, dev tokens. Verified over
> real HTTP: `GET /`→200 text/html `<title>Inspector</title>`; SPA deep-link `/dashboard`→index.html; JS asset→
> text/javascript 4.3MB; **API 404 stays JSON** (`/pipelines/nope/files`→`application/json`, not the SPA HTML);
> `/health`/`/ready`/`/metrics` PUBLIC; `/pipelines` 401 w/o token, 200 with. Built a throwaway minimal pipeline
> (mini_schema+mini_pipeline in a temp dir, 3 inbox CSVs) and drove the full lifecycle: **`/pipelines/{n}/pending`
> pending:3 → trigger → pending:0 + committedBatches:1 + /files 3 SUCCESS rows + /batches member_count:3** (the new
> endpoint works end-to-end). Also `/catalog` 200, `/config/spec/pipeline` 200 (type+fields[]), `/validate`
> {clean:true}, assist intent (agent absent)→503 graceful. Server + scratch dir cleaned up; working tree back to just
> in-flight `pom.xml` + the docs PDF. **Minor pre-existing observation (NOT from this work, flagged not fixed):** the
> lean-core JSON writer doesn't escape Windows `\` in `output_paths` (display-only field).
>
> **DOCS UPDATED 2026-06-09 (uncommitted):** (1) `file-processor/README.md` — intro layer line, repo-layout
> `inspector-ui/` row, feature-matrix "Operator console (Inspector)" row, Control-API `/pipelines/{n}/pending` route +
> static-serving/CORS note, NEW "§9b Operate via the Inspector web console" section, docs-index row. (2)
> `docs/operations.md` — Control-API table `/pending` row + a "Serving the operator web console" subsection (`-Dui.dir`/
> `-Dcontrol.cors` flags, serve.sh/.bat), bundle-build step (UI build), bundle-contents (`ui/`,`serve.sh`,`serve.bat`),
> deploy-and-run serve step. (3) NEW **`docs/operator-console.md`** = the operator/user guide (serving dev vs prod,
> token connect, screen-by-screen for all 8 nav sections, common tasks, troubleshooting table, honest "can't do yet"
> list). (4) `inspector-ui/README.md` — operator-guide pointer near top (keeps its dev/build focus).
>
> **TEST RUNNER + LINT WIRED 2026-06-09 (uncommitted, all green).** Adopted #2 from the next-steps list.
> **Vitest** via Angular 21's `@angular/build:unit-test` builder (jsdom auto-detected; builder auto-inits the
> Angular TestBed — so NO initTestEnvironment/setup file): `angular.json` test target switched karma→unit-test
> (`runner: vitest`); added `vitest`+`jsdom` devDeps; `package.json` +`test:ci` (`ng test --no-watch`). **Starter
> suite = 27 tests / 6 files, all green:** `api-base.spec` (toParams/apiUrl), `token-store.service.spec` (bearer
> precedence/trim/clear), `auto-refresh.spec` (visibleInterval pause-when-hidden via fake timers + visibilitychange),
> `auth.interceptor.spec` (Bearer attach/skip via HttpTestingController), `pipelines.service.spec` (URL/encode/params
> incl. the new `/pending`), `auth.service.spec` (connect/scope/logOut). GOTCHA: tests build with the **development**
> environment (apiBaseUrl=`/api`) → specs derive base from `environment.apiBaseUrl` (not hard-coded). **Lint** via
> `ng add angular-eslint` (21.4.0; eslint 10, typescript-eslint 8.59) — curated `eslint.config.js`: strict recommended
> on first-party code; **deferred `prefer-control-flow`** (88 *ngIf/*ngFor sites — purely stylistic, structural
> directives fully supported in NG21; documented, re-enable after a per-screen verified migration); scoped relaxation
> block for **vendored DevExtreme-template files** (app.ts, layouts/, header/footer/side-nav/theme-switcher/user-panel,
> app-info/screen/theme services, app-navigation, unauthenticated-content) disabling prefer-inject/component-selector/
> no-explicit-any/etc. Fixed real first-party issues: assist-panel `==`→`!applyVia`, dashboard `<a (click)>`→`<button>`
> (+scss reset) for a11y, config `<label>`→`<span class=field-label>` (+scss), and converted `auth.service`
> (AuthService+AuthGuardService) to `inject()`. **`pnpm lint`=All files pass; `pnpm test:ci`=27 green; `pnpm build`
> (prod)=complete** (only pre-existing benign NG8107 CatalogComponent optional-chain warnings). **`ui.yml` CI now gates
> lint → unit tests → prod build → dev build** (was builds only). Frozen-lockfile verified clean. `inspector-ui/README.md`
> follow-ups rewritten (lint/test now wired; e2e + component specs still follow-ups) + a Testing&linting section. New
> files: `eslint.config.js` + 6 `*.spec.ts`. NOTHING committed (guardrail); `pom.xml` still unstaged.
>
> **CSV BACKSLASH BUG FIXED 2026-06-09 (uncommitted, lean-core, 401 green).** Root-caused the smoke-test
> `output_paths` finding: it was NOT the JSON writer (ControlApi uses Jackson, which escapes correctly) — it's
> **OpenCSV's default `CSVParser`, whose escape char is `\`**, silently stripping backslashes from Windows audit paths
> on READ (`"C:\db\out.csv"`→`C:dbout.csv`). Writers (`BatchAuditWriter`/`EnrichmentAuditWriter`) keep backslashes;
> only the readers corrupted them — which is why it never surfaced on Linux (forward-slash paths). Fix: both audit
> readers (`FileStatusStore.readCsv`, `EnrichmentAuditReader.read`) now build the reader with
> `RFC4180ParserBuilder` (backslash literal; matches the writers' quote-and-replace convention). `DbStatusStore`
> refreshes FROM FileStatusStore so it inherits the fix. New regression test `com.gamma.service.FileStatusStoreTest`
> writes a real Windows-path audit row via BatchAuditWriter and reads it back through FileStatusStore, asserting
> `output_paths` + lineage `output_file` keep their backslashes. **`mvn -pl file-processor test` = 401 pass / 1 skip
> (was 400; +1), BUILD SUCCESS.** Left out of scope (same latent pattern, but they read external manifests not surfaced
> via API/UI, so different risk): the two `util/` readers `FileBackup`/`FileOrganizer`.

**Env set up:** Node v24.16.0 + npm 11.13.0 installed (winget `OpenJS.NodeJS.LTS`); pnpm 11.5.1 installed
globally via npm (corepack needed admin → used `npm i -g pnpm@11.5.1`). To use these in a shell, first:
`$env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [System.Environment]::GetEnvironmentVariable('Path','User')`.

**P0 — DONE & GREEN (uncommitted).** `ControlApi.java`: prop-gated CORS (`-Dcontrol.cors`) + OPTIONS
preflight + static SPA serving (`-Dui.dir`, PUBLIC, SPA index.html fallback, traversal guard, MIME map).
New `ControlApiStaticAndCorsTest` (9 tests). `package.ps1`: fixed stale `voucher_unknown_*`→`voucher_*`
names (broken by earlier rename commits), added guarded UI build+bundle (pnpm) + `serve.sh`/`serve.bat`
launching `com.gamma.control.ControlApi -Dui.dir=./ui`. **`mvn -pl file-processor test` = 399 pass, 1 skip,
BUILD SUCCESS.** Lean-core preserved (only `java.nio.file.*` added). NOT committed (guardrail).

**BRAND: "Inspector"** (user directive). Applied to user-facing UI only (NOT Java packages / Maven
artifact / `file-processor.jar`): `AppInfoService.title='Inspector'` (+`tagline`), `index.html` `<title>`,
`package.json` name `inspector-ui`, Connect screen + `unauthenticated-content` ("Connect to Inspector"),
token-store keys `inspector.control.token`/`inspector.assist.token`. Resonates with existing
`com.gamma.inspector` package. (Backend rebrand was deliberately out of scope — offer if asked.)

**P1 — DONE & GREEN (uncommitted).** DevExtreme template cloned into `ui/` (Angular 21 + DevExtreme 25.2,
hash-location routing, nested `.git` removed). Toolchain: Node 24.16 + pnpm 11.5.1; **use pnpm** (matches
template lockfile). Built in `ui/`: environments (`environment.ts` prod `apiBaseUrl=''`,
`environment.development.ts` dev `/api`); `proxy.conf.json` (`/api`→:8080 pathRewrite); `angular.json`
(dev fileReplacements + serve proxyConfig); full API layer `src/app/shared/api/` = `models.ts` (all DTOs),
`api-base.ts`, `token-store.service.ts`, `auth.interceptor.ts` (Bearer), `error.interceptor.ts` (401→/connect),
services pipelines/reports/health/jobs/enrichment/catalog/config/assist + `index.ts` barrel; token-based
`auth.service.ts` (`connect`/`logOut`/`hasControl`/`hasAssist`/`getUser`); **Connect** screen
(`shared/components/connect-form/`) replacing the 4 deleted auth forms; deleted demo pages home/profile/tasks
+ dead `not-authorized-container.ts`; `header` user-menu → "Disconnect"; `app.config.ts`
`provideHttpClient(withInterceptors([authInterceptor, errorInterceptor]))`; `app-navigation.ts` = 8 Inspector
sections; `app.routes.ts` (connect + 8 pages + `**`→dashboard); 8 placeholder page components under
`src/app/pages/{dashboard,pipelines,jobs,enrichment,catalog,config,diagnoses,assist}/` (inline stubs).
**Verified: `pnpm run build` (prod) AND `ng build --configuration development` both compile clean**
(only benign DevExtreme CommonJS warnings; dist → `ui/dist/DevExtreme-app`). Live serve-against-backend smoke
NOT yet run.

API URL convention: services call `apiUrl('/pipelines')`; dev→`/api/pipelines`→proxy→`:8080`; prod→`/pipelines`.
Backend dev launch: `java -Dcontrol.token=dev -Dassist.read.token=dev -Dcontrol.cors=http://localhost:4200 -cp file-processor.jar com.gamma.control.ControlApi <config-dir>`.
Shell to use Node/pnpm: first run `$env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [System.Environment]::GetEnvironmentVariable('Path','User')`.

**P2 — DONE & GREEN (uncommitted).** Three real monitoring screens replace the placeholders, all on the
P1 API services. **Dashboard** (`dashboard.component.{ts,html,scss}`): forkJoin of `/ready`+`/status`+`/report`+`/metrics`
→ 6 KPI tiles (service/pipelines/paused/committed/quarantine/error-rate, colour-coded), `dxChart` bar of
p50/p95/p99 durations, `dxPieChart` doughnut of success vs failed, per-pipeline `dxDataGrid`, and a collapsible
raw-Prometheus `<pre>` (so `/metrics` is consumed). **Pipelines** (`pipelines.component.{ts,html}`): `dxDataGrid`
(name/config/paused/committed) + `dxToolbar` (Refresh, **Run all** via POST `/trigger`, disabled w/o CONTROL) +
per-row command buttons trigger / pause↔resume / reprocess (dxPopup batch-id dialog) / open-detail; scope-gated via
`AuthService.hasControl()`. **Pipeline detail** (NEW `pipeline-detail.component.{ts,html,scss}` at route
`/pipelines/:name`): `dxTabs` over batches / files / lineage (dxTextBox batchId filter) / quarantine / commits
(generic dynamic-column grid built from row keys; commits string[]→`{commit}`) + **Report** tab (two `dxDateBox` →
`/pipelines/{n}/report` percentile+throughput stats table); batches rows get a reprocess command button. Route
`pipelines/:name` added to `app.routes.ts`. **angular.json prod budget** initial bumped 4mb/7mb→8mb/12mb (DevExtreme
charts+grids grow the bundle to ~6.1MB; warning-free now). **Verified: `ng build --configuration development` AND
`pnpm run build` (prod) both clean** (only benign DevExtreme CommonJS bailout warnings; dist → `ui/dist/DevExtreme-app`).
Live serve-against-backend smoke still NOT run.

**P3 — DONE & GREEN (uncommitted).** Two screens on `JobsService`/`EnrichmentService`. **Jobs**
(`jobs.component.{ts,html}`): `dxDataGrid` (name/type/cron/onPipeline/enabled/lastStatus/lastRun/nextFire) +
`dxToolbar` (Refresh, **New schedule** disabled w/o assist) + per-row Run-now (CONTROL-gated via `[visible]`) and
Run-history (`dxPopup` grid of `JobRun`s). **New schedule** popup = nl-to-schedule assist embed: `dxTextArea` →
`AssistService.run('nl-to-schedule',{userText})` → renders `AssistResult` (answer/status/confidence/validated/rationale +
pretty-printed `data` draft); 503 → "agent not available" notify. 404 list → "no jobs registered" empty state.
**Enrichment** (`enrichment.component.{ts,html,scss}`): `dxDataGrid` of Stage-2 jobs (single-select) → detail card with
`dxTabs` Runs / Lineage (dxTextBox runId filter) / Report (two dxDateBox → `EnrichmentRunReport` stats table); generic
dynamic-column grids from row keys; 404 → empty state. **Shared styles** promoted to global `styles.scss`:
`.popup-actions`, `.assist-result` (answer/meta/rationale/data), `.report-stats` (moved out of pipeline-detail scss,
now shared). **Verified: dev + prod builds both clean** (no budget warning; only benign DevExtreme CommonJS bailouts).
Live serve-against-backend smoke still NOT run.

**P4 — DONE & GREEN (uncommitted).** Two screens on `CatalogService`/`ConfigService`. **Catalog**
(`catalog.component.{ts,html,scss}`): `dxTabs` Tables / KPIs / Graph. Tables = `/catalog` `MetadataNode[]` grid with
nested `overlay.*` columns (freshness/rowCount/completeness%/lastSeen), row-click → **node-detail `dxPopup`**
(`/catalog/tables/{id}` → kv table + attrs pre + neighbours grid, itself click-through). KPIs = `/catalog/kpis` grid
(id/name/definition/grain/joinKeys/inputs). Graph = traversal form (from id, depth dxNumberBox, direction dxSelectBox,
node-kinds + edge-kinds csv dxTextBox, overlay dxCheckBox) → `/catalog/graph` → side-by-side Nodes + Edges grids; node
rows click → same detail popup. (Interactive dxDiagram deferred to P6 polish; grids are the reliable MVP.) **Config**
(`config.component.{ts,html,scss}`): `dxTabs` Author-draft / Validate-file. Draft: type dxSelectBox
(pipeline/enrichment/job/schema/meta) → `/config/spec/{type}` → **dynamic editors per FieldSpec** (options→SelectBox,
ARRAY→TagBox, INTEGER→NumberBox w/ min/max, BOOLEAN→CheckBox, else TextBox; required\* + description hints), seeded from
`default`; cross-field `rules` listed as hints; flat dotted-path `model` **assembled into nested config** (`setPath`,
drops empties) shown in a live JSON preview with **Copy** (clipboard) — no write endpoint so operator commits the .toon
manually; **Validate draft** → `/validate` `{type,config,safety?}`. File mode: path dxTextBox → `/validate`
`{configPath}`. Findings rendered as verdict (✓ clean / ✗ N findings, safety tag) + severity/field/message grid.
**Shared `model: Record<string,any>`** (dynamic editors need heterogeneous two-way bind). **Verified: dev + prod builds
both clean** (no budget warning; only benign DevExtreme CommonJS bailouts). Live serve-against-backend smoke still NOT run.

**P5 — DONE & GREEN (uncommitted).** Reusable assist panel + Diagnoses + Assist console, all on `AssistService`.
Confirmed the real `data` keys per intent from the agent skills (kpi/report-sql: `sql`,`sampleRows`,`logicExplanation`,…;
nl-to-schedule: `cron`,`humanReadable`,`nextRuns`,`draftToon`,`findings`; diagnose-and-alert: `humanReadable`,`draftToon`,
`findings`; report-narrative: `narrative`,`reportType`,`grounded`; suggest-config: `configType`,`fields`,`draftToon`,
`findings`; explain-entity: prose) and that SQL sample rows are requested via `partialInput.sampleRows=true`.
**`AssistPanelComponent`** (NEW shared `shared/components/assist-panel/`, in barrel): self-contained widget for any
intent — input (dxTextArea + "include sample rows" toggle on SQL intents) → `POST /assist/{intent}` → intent-aware render
(answer/status/confidence/validated; humanReadable; narrative; SQL code+copy; sampleRows grid; draftToon code+copy;
nextRuns list; findings grid; citations/links chips; raw-data `<details>`). `@Input`s: intent, placeholder, userText,
screenContext, basePartialInput, autoRun, hideInput. 503→"agent absent", 404→"unknown intent" graceful. **Assist console**
(`assist.component.{ts,html}`): dxSelectBox of 7 intents (labels+placeholders) drives the panel, recreated via
`*ngFor=[selected];trackBy` so state resets per skill. **Diagnoses** (`diagnoses.component.{ts,html,scss}`):
`/assist/diagnoses?limit=` grid (when desc/pipeline/batch/severity/rootCause/heuristic) + dxNumberBox limit + refresh;
row-click → detail dxPopup (kv + root-cause + citations + suggested alert-rule .toon w/ copy) **with an embedded
diagnose-and-alert AssistPanel** prefilled from the diagnosis text ("Refine as alert"); empty/agent-absent → empty state.
**Contextual embeds:** Catalog node-detail popup now embeds an **explain-entity** panel (`screenContext {entityType,id}`,
prefilled userText, re-keyed per node). **DRY refactor:** Jobs "New schedule" popup now reuses `<app-assist-panel
intent="nl-to-schedule">` (deleted the hand-rolled schedule call/render + AssistService dep from Jobs). **Verified: dev +
prod builds both clean** (only benign DevExtreme CommonJS bailouts). Live serve-against-backend smoke still NOT run.

**P6 — DONE & GREEN (uncommitted). 🎉 ALL PHASES P0–P6 COMPLETE.** Polish + cross-cutting. **Auto-refresh:** new
`shared/api/auto-refresh.ts` `visibleInterval(ms)` (rxjs timer that **pauses when the tab is hidden** via
visibilitychange, restarts on re-show) + `DEFAULT_REFRESH_MS=15000`; wired into **Dashboard** (head toolbar: refresh-now +
Auto on/off toggle) and **Pipelines** (toolbar toggle; skips refresh while reprocess popup open), both via
`takeUntilDestroyed(DestroyRef)`. **Confirm dialogs** (DevExtreme `confirm` from `devextreme/ui/dialog`): Pipelines
trigger / pause↔resume / run-all, Jobs run-now (all now async/await-gated). **Scope-aware UI** already enforced
(P2/P3 `hasControl()` gating); **theme switch** ships in the template header (`ThemeSwitcherComponent`, verified imported).
**CI:** new `.github/workflows/ui.yml` — Node 24 + pnpm 11.5.1, path-filtered to `ui/**` (+ self), `pnpm install
--frozen-lockfile` (postinstall builds themes) → **prod build AND dev build** as the green gate; fully isolated from the
Java `ci.yml` (Node never enters the Maven reactor). **`ui/README.md`** rewritten (Inspector: prereqs, dev w/ CORS+proxy,
prod serving via `-Dui.dir`, token auth model, layout, scope/follow-ups). **Scoping calls (documented, not silent):**
lint + unit tests + e2e DEFERRED — the DevExtreme template ships **no test runner (only `ng test`→nothing) and no ESLint**;
wiring Vitest(`@angular/build`)+angular-eslint is its own task. Interactive catalog `dxDiagram` also deferred (grids are the
MVP). **Verified: dev + prod builds both clean** (only benign DevExtreme CommonJS bailouts). Live serve-against-backend
smoke still NOT run (no backend up this session).

**🎉 ROADMAP COMPLETE: P0 (backend CORS+static, 9 tests, 399 Java green) → P1 (foundation) → P2 (monitoring) → P3
(scheduling+enrichment) → P4 (catalog+config) → P5 (diagnostics+assist) → P6 (polish+CI). All 8 nav sections live, all
~30 ControlApi endpoints + 7 assist intents consumed.** Tasks #42–48 ALL done.

**POST-ROADMAP ENH #1 — file-processing status + batch lineage drill (DONE & GREEN, uncommitted).** User ask:
"file processing status — files processed, pending, under processing" + "lineage for a batch, its details, search a file."
**Backend reality (verified in source):** the durable audit (`BatchAuditWriter` → `StatusStore`/`DbStatusStore`) records
only **processed** files. Files audit columns = `start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,
output_sizes_bytes,duration_ms,error,batch_id` (per-file `status`=SUCCESS/rejected); batch columns include
`batch_id,status,member_count,rejected_count,total_input_rows,total_output_rows,…`. **There is NO pending / under-processing
state** in any StatusStore method or ControlApi route — inbox-pending + in-flight are runtime states the backend doesn't
track/expose. So I built the achievable, real view and flagged the gap (no faked states). Enhanced
`pipeline-detail.component` (no backend change, existing endpoints): **Files tab** now has a processing-status strip —
clickable stat cards Processed / Succeeded(SUCCESS) / Rejected(status≠SUCCESS) / With-errors(error_rows>0) + Rows-parsed,
each doubling as a status filter — plus a **filename search** box and a status dxSelectBox, computed client-side from the
files audit; an inline note states pending/in-flight aren't tracked. Per-file **"open batch"** button. **Batches tab** rows
get a **"Lineage & details"** button (alongside reprocess; also fixed reprocess to read `batch_id` not the never-present
`batchId`). Both open a **batch-detail drawer** (dxPopup): forkJoin of batches+files+lineage(batchId) → batch summary kv
table + member-files grid + input→output lineage grid. **Verified dev + prod builds clean.**

**POST-ROADMAP ENH #2 — true pending + under-processing (DONE & GREEN, uncommitted). Backend touched (lean core,
zero new deps).** User approved the follow-up. **`SourceProcessor`**: extracted a read-only `collectCandidates(cfg)` +
`countPending(cfg)` from `run()` — the exact inbox scan a poll cycle uses (files under `dirs().poll()` matching
`processing.file_pattern`, minus errors/quarantine trees, minus `.processed` markers; keeps the parallel marker-check
optimization; no dir-creation/cleanup side effects); `run()` refactored to call it (behaviour-preserving). **`SourceService`**:
new `running` `Set<String>` marked around `runAllOnce` (active pipeline names via new `activeNames`) + `runPipeline` (the
triggered one); new `record InboxStatus(pipeline,inbox,pending,running)` + `inboxStatus(name)`. **`ControlApi`**: new
`get("/pipelines/{n}/pending")` (CONTROL scope, 404 via `notFound`). **UI:** `models.ts` `InboxStatus`;
`PipelinesService.pending()`; pipeline-detail Files tab forkJoins files+pending and shows two live cards — **Pending**
(count, dashed, amber when >0) and **Processing** (Yes/No, blue when running) — ahead of the audit-derived
Processed/Succeeded/Rejected/Errored filter cards, with the inbox path in the note. **Verified: `mvn -pl file-processor
test` = 400 pass / 1 skip (was 399; +1 new `SourceProcessorPollTest.countPendingReflectsUnprocessedInboxAndIsReadOnly`),
BUILD SUCCESS; lean core unchanged (no new deps); dev + prod UI builds clean.** All three buckets now real: processed
(audit) + pending (inbox scan) + under-processing (mid-ingest flag). Honest limit: "Processing" is a pipeline-level boolean
(ingest is synchronous per cycle), not a per-file in-flight count — no durable in-flight tracker exists.

**Guardrail reminder:** **NOTHING committed this entire session for the UI work** (P0 Java + P1–P6 frontend + ui.yml +
README all UNCOMMITTED per standing guardrail); user's in-flight `pom.xml` still unstaged. **Commit/push only on explicit
ask.** When asked, a natural commit grouping: (1) P0 backend `ControlApi` CORS+static + test + `package.ps1`; (2) the
whole `ui/` SPA; (3) `.github/workflows/ui.yml`. NEXT possible asks: commit the UI work; run the live serve-against-backend
smoke; wire a test runner + lint; interactive dxDiagram.

## Phasing summary (each phase independently shippable / green)
- **P0** backend CORS + static serving (+ tests).  **P1** UI scaffold + auth + API/DTO layer.
- **P2** dashboard + pipelines + pipeline detail.  **P3** jobs + enrichment.
- **P4** catalog + config authoring.  **P5** diagnoses + assist console.  **P6** polish + tests.
