# Plan: Multi-Space (multi-project) feature for Inspecto

## Context

Today Inspecto is a **single flat tenant**: one running `SourceService` discovers every TOON config by
suffix-scanning CLI path args into one global registry, and writes all data/audit/duckdb/flows under
process-global roots. There is no notion of a project, namespace, or grouping anywhere in the codebase.

We want **spaces** — self-contained projects, each owning its own set of data sources (tens per space),
its own config tree, and its own isolated data. One server hosts **many spaces concurrently**; the UI/API
scope to one space at a time and can switch between them. This is the foundation for a future multitenant
deployment (users see only the spaces they may access) and a future superuser console (Standard edition).

**Decisions locked in this brainstorm (do not relitigate):**
- **Runtime:** ONE server, MANY spaces, all pipelines/jobs run concurrently; each space fully isolated.
- **Layout:** convention directory `spaces/<id>/{config,data,audit,duckdb,flows}` + a small `space.toon` manifest.
- **Migration:** REQUIRED — no legacy flat mode. Ship an automated migrate command **and** docs.
- **Data source = a bundle:** pipeline + its connection + its schema(s) (+ optional job/metadata), treated as one unit.
- **Export/import:** config + metadata only now (selective per-data-source **and** whole-space). **Ingested-data
  cloning is roadmap (future).**
- **Phase 1 ships:** space primitive + isolation, space-scoped API + UI switcher, space CRUD from UI, bulk
  data-source onboarding, and config/metadata export/import.
- **Editions/auth:** core stays auth-free; access-control + superuser are future and slot in via the existing
  ServiceLoader SPI seam — **no `if(edition==…)` in core**.

---

## Architecture

### Backend container: `SpaceManager` → `SpaceContext` → unchanged `SourceService`

The engine is already per-instance: `SourceService` holds its stores, scheduler, bus, `ConfigRegistry`,
`JobService`, and locks as instance fields, and `close()` already drains them in order
([SourceService.java:902](inspecto/src/main/java/com/gamma/service/SourceService.java:902)). So we **wrap**, we
don't rewrite. Do **not** make `SourceService` (a ~40-method `@PublicApi` facade) multi-space — that would
break the API and force per-space lock re-auditing.

New types:

| Type | Responsibility |
|---|---|
| `SpaceId` | Validated FS-safe id (`[a-z0-9-]`). The single enforcement point; jails ids under `spaces/`. |
| `SpaceRoot` (record) | The one per-space root `spaces/<id>/` with derived `config()`, `data()`, `audit()`, `duckdb()`, `flows()`. **Replaces every `System.getProperty` root read.** |
| `SpaceContext` (AutoCloseable) | Owns one space's live runtime: its `SourceService` + `SpaceRoot` + display metadata from `space.toon`. `close()` delegates to `SourceService.close()`. |
| `SpaceManager` (AutoCloseable) | New top-level container; concurrent `Map<SpaceId,SpaceContext>`. Boot discovery, runtime CRUD, orderly shutdown. Replaces the single `SourceService` that `ControlApi.main` builds. |
| `SpaceBootstrap` | Per-space analogue of `ServiceBootstrap.build(args)`; builds one `SpaceContext` by scanning `spaceRoot.config()` instead of CLI args. Reuses `MultiSourceProcessor.resolveConfigs` + the existing suffix loaders. |

One process-global flag is acceptable: `-Dspaces.root` (default `./spaces`) — the container root, not a per-space path.

### Request seam: `/spaces/{spaceId}/...` prefix + per-request `ApiContext` view

- API routes move under `/spaces/{spaceId}/...`. `ControlApi.dispatch` already strips an optional `/api` prefix
  ([ControlApi.java:236](inspecto/src/main/java/com/gamma/control/ControlApi.java:236)); add one step that captures
  & strips `/spaces/{id}`, resolves the `SpaceContext`, then matches the **existing** route patterns against the
  remainder. **RouteModules do not change** — they still see `/pipelines`, `/objects/{id}`, etc. Unknown id → 404.
- `/health`, `/ready`, `/metrics` stay un-prefixed and server-global.
- `ApiContext` ([ApiContext.java:41](inspecto/src/main/java/com/gamma/control/ApiContext.java:41)) becomes a
  per-request view: `service()` returns *that space's* `SourceService`, `writeRoot()` returns *that space's* root.
  `ControlApi` stops being the `ApiContext` itself and mints one per request. Route registration is unchanged —
  handlers are stateless lambdas over the `ctx` handed in at call time. (Rejected: a `ThreadLocal<SpaceContext>` —
  works under the virtual-thread executor but is implicit ambient state.)

### Wiring sites that change (the entire global-root surface — confirmed small)

1. **`ServiceStores`** ([ServiceStores.java](inspecto/src/main/java/com/gamma/service/ServiceStores.java)) — the 8
   `open*` methods stop reading `System.getProperty` for **locations** and take a `SpaceRoot`:
   `openFlowStore→base.flows()`, `openJobRunStore→base.duckdb()/jobs_report.duckdb`, `openProvenanceStore→…/provenance.duckdb`,
   `openEventStore→base.data()/events`, `openObjectStore→…/inspecto-ops.db`, `openLinkStore→…/inspecto-ops-links.db`,
   `openNoteStore→…/inspecto-ops-notes.db`, `openStatusStore→…/inspecto-status.db`. The **backend toggles**
   (`status.backend`, `objects.backend`, `events.backend`, `jobs.backend`, `provenance.backend`) stay process-global
   `-D` flags — they pick memory-vs-db/parquet uniformly; only the URL/dir becomes per-space. Per-space duckdb dirs
   also satisfy DuckDB's single-writer lock for free.
2. **`SourceService` constructor** — relocate the `flowStore = ServiceStores.openFlowStore()` **field initializer**
   ([SourceService.java:143](inspecto/src/main/java/com/gamma/service/SourceService.java:143)) into a new
   `SpaceRoot`-taking constructor (it currently reads the global property before the ctor body runs — a silent
   ordering trap). Replace the `jobs.audit.dir`/`data.dir` reads (~:256–257) with `base.audit()`/`base.data()`.
   Keep existing public constructors (defaulting to a cwd-rooted `SpaceRoot`) so the test suite stays byte-identical.
3. **`JobService`** — **no signature change**; it already takes `auditDir`/`dataDir` as ctor args
   ([JobService.java:129](inspecto/src/main/java/com/gamma/job/JobService.java:129)). Only the call site passes
   `base.audit()`/`base.data()`.
4. **`ControlApi`** — holds `SpaceManager` instead of one `SourceService`; `assist.write.root` moves out of the
   ControlApi field into the per-request `ApiContext` view; `control.cors`/`ui.dir` stay server-global.
5. **Entry points** — `ControlApi.main`/`SourceService.main` build a `SpaceManager` that scans `-Dspaces.root`.
   `SourceService.fromArgs`/`ServiceBootstrap.build(args)` are retained for the migrate command + tests.

### Landmines (the genuinely risky bits — sequence these first, verify each)

1. **`EventLog.global()` is a process-wide singleton** ([EventLog.java:34](inspecto/src/main/java/com/gamma/event/EventLog.java:34)).
   `SourceService.installStore`/`addSubscriber`/`emit` + the SLF4J capture appender all funnel through it. N spaces
   would cross-contaminate events and the gap→ALERT bridge. **Fix:** make EventLog per-space (instance owned by
   `SpaceContext`, threaded into `SourceService`). Highest-risk — `emit` is on a no-log re-entrancy path and the
   appender must resolve a context-scoped sink.
2. **`MetricRegistry.global()` is process-wide** (MetricsService, JobService, EventLog all write it; `/metrics`
   scrapes it). **Fix:** add a `space` **label** to per-space metric emissions and keep the single global scrape
   endpoint (don't fragment into N registries). Decide label-vs-registry before touching emitters.
3. **`ConnectionRegistry` is a static map** ([ConnectionRegistry.java:23](inspecto/src/main/java/com/gamma/acquire/ConnectionRegistry.java:23));
   the static `SourceConnectors.forConfig` poll path resolves `source.connection:<id>` from it. Two spaces with the
   same connection id collide. **Fix:** namespace keys by `spaceId` and thread it into `SourceConnectors.forConfig`.
   **Audit the sibling static idioms** `StabilityGate.shared()` and `AcquisitionLedgers.shared()` for the same leak.
4. **Assist-agent ServiceLoader** — `start()` binds one agent per `SourceService` via `agent.init(this)`. N spaces ⇒
   one agent per space; **audit `UccAssistAgent.init` (inspecto-agent) for static/process state.** The SPI seam itself
   is exactly where the future access-control/superuser editions plug in.

### Pipeline-id uniqueness — simplifies, doesn't break

`ConfigRegistry` is already per-instance, not static. One registry per `SpaceContext` ⇒ ids unique **within a space**
automatically (zero `ConfigRegistry` change). This is what makes export/import clean: a space is a self-contained
`spaces/<id>/config` tree with locally-unique ids; importing under a new space needs no cross-space id rewriting.

---

## Space lifecycle & CRUD

- **`space.toon` manifest** in each `spaces/<id>/`: `display_name`, `description`, `created_at`. Discovery = a dir
  with a `config/` subtree (manifest optional; defaulted if absent).
- **Boot:** `SpaceManager` scans `-Dspaces.root`, builds + `start()`s each `SpaceContext` in parallel, registers
  only on success (warn-and-skip a bad space, mirroring `ServiceBootstrap`/`ConfigRegistry.rebuild`).
- **Create:** validate id → create `spaces/<id>/{config,data,audit,duckdb,flows}` + `space.toon` → build + `start()`
  → put into map. Reject duplicate.
- **Delete:** remove from map first (new requests 404) → `context.close()` (the existing drain-first
  `SourceService.close()`) → then guarded file removal. A per-space STARTING/RUNNING/CLOSING flag lets mid-transition
  requests 409. Each space keeps its own `Scheduler` + virtual-thread executors (document the thread multiplier); the
  HTTP server keeps one shared executor. Shutdown hook: `svc.close()` → `spaceManager.close()`.
- **New `SpaceRoutes`** (server-global, un-prefixed): `GET /spaces`, `POST /spaces`, `DELETE /spaces/{id}`, plus the
  export/import endpoints below. This is the one new route group and the natural future-ACL SPI seam.

---

## Data-source bundle + onboarding + export/import

### Data-source bundle (the unit)

A `DataSourceBundle` resolver walks a `*_pipeline.toon`'s references to gather the cohesive unit: the pipeline + the
connection profile it names (`source.connection:<id>`) + the schema(s) it references (`*_schema.toon`/`.grammar.toon`)
+ optionally a `*_job.toon` targeting it + a `*_meta.toon` referencing it. This is the granularity for onboarding,
selective export, and selective import.

### Export/import (config + metadata only; data deferred)

- **Format:** a zip archive containing the relevant TOON files + a `bundle.toon` manifest (kind: `space` | `datasource`,
  artifact list, source space id, schema version). No parquet/duckdb bytes (roadmap).
- **Selective:** `GET /spaces/{id}/datasources/{ds}/export` → bundle for one data source.
  `POST /spaces/{id}/import` → unpack a bundle into the target space's `config/`, then `ConfigRegistry.rebuild`
  hot-reloads (no restart). Reuse the existing write-root path-jail + `AtomicFiles` from `ConfigRoutes`.
- **Whole-space:** `POST /spaces/{id}/export` → bundle the full `config/` tree + `space.toon`.
  `POST /spaces/import` (create-from-bundle) → new space seeded from a whole-space bundle.
- **Conflicts:** import detects id clashes within the destination registry (`rebuild` already warns/dedups) and
  surfaces a 409 with the colliding ids; offer overwrite vs rename on import.

### Bulk onboarding

UI flow to add many data sources to a space at once: upload/drop multiple bundles (or a multi-source zip), preview the
resolved units + validation findings (reuse `ConfigRoutes` validation), then commit → `rebuild`.

---

## UI (Angular `inspecto-ui`) — apply the `angular-ui` skill before any change

- **`SpaceService`** (new global signal service): `currentSpace`, `availableSpaces`, `selectSpace(id)`; loads
  `GET /spaces` on app init. Mirrors the existing global-signal-service pattern.
- **Space switcher** in the shell header (`LayoutComponent`, next to the user widget): dropdown of spaces; selection
  updates `SpaceService` and re-scopes the app.
- **HTTP interceptor** (new, in `core/http-interceptors`): prefixes API calls with `spaces/<currentSpace>` so every
  existing feature service stays unchanged. (Header alternative possible; path-prefix chosen for deep-linkability.)
- **Spaces management view** (new feature module under `modules/admin/`): list / create / rename / delete spaces;
  export (download bundle) + import (upload bundle) + bulk-onboard. Follows the design system (status-badge /
  empty-state / skeleton / grid), the no-hardcoded-color guard, and the WCAG/axe gate.
- **Deep-linking** (`:spaceId` route param) is optional Phase-1.5 polish; the switcher + interceptor cover the
  core requirement first.

---

## Migration (required — no flat mode)

- **`migrate` command** (a `package.ps1`/CLI entry or a small `main`): moves the current `inspecto/config` tree into
  `spaces/<id>/config` (default id `default`) and relocates existing data/audit/duckdb/flows under `spaces/<id>/`,
  then writes `space.toon`. Idempotent, dry-run flag, prints the new launch wiring.
- **Docs:** update `docs/configuration.md`, `docs/EDITIONS.md`, and the launch recipes (`launch.json`, run scripts)
  for the `spaces/` layout + `-Dspaces.root`. Update `docs/PROJECT_NOTES.md` with the space model.

---

## Roadmap (future phases — out of scope for Phase 1, but designed-for)

- **Phase 2 — Multitenant access control (Standard edition).** Wire OIDC into the existing `Authenticator` SPI; add a
  `SpaceAccessPolicy` SPI (core default: allow-all) consulted by `SpaceManager`/`SpaceRoutes` so `GET /spaces` returns
  only spaces the user may access and switching is gated. No core `if(edition==…)`.
- **Phase 3 — Superuser console (Standard edition).** Cross-space management UI (all spaces, health, create/suspend),
  gated by edition + the access policy. Standalone/Personal keeps free switching across all spaces.
- **Roadmap — ingested-data export/import.** Extend the bundle to optionally include parquet/duckdb with path
  rewriting + large-file handling for true space clone/backup.

---

## Suggested staging (limits risk; verify after each)

1. Introduce `SpaceRoot`; thread it through `ServiceStores` / `SourceService` ctor / `JobService` call site —
   defaulting to a cwd-rooted `SpaceRoot` so single-space behavior + tests stay byte-identical. **Pure refactor.**
2. Namespace the three singletons behind still-single-space wiring (EventLog per-space, metric `space` label,
   `ConnectionRegistry` keyed by spaceId; audit `StabilityGate.shared()`/`AcquisitionLedgers.shared()`/agent SPI).
   **Highest-risk — own commits, careful verify.**
3. Add `SpaceContext` + `SpaceManager` + boot discovery; `ControlApi` holds `SpaceManager`.
4. Add the request seam: `/spaces/{id}` prefix + per-request `ApiContext` view. Routes untouched.
5. Add `SpaceRoutes` CRUD + the `migrate` command.
6. Data-source bundle resolver + export/import endpoints + bulk onboarding (backend), then validation reuse.
7. UI: `SpaceService` + header switcher + interceptor; then the Spaces management/onboarding view.

---

## Verification

- **Backend unit/integration:** the offline reactor loop `mvn -o clean test` (with the DuckDB native-access JVM flag)
  after each staging step; invariant = **0 failures / 0 errors**. New tests: `SpaceManagerTest` (boot discovery,
  create/delete-without-restart, concurrent isolation), per-space store isolation (two spaces' duckdb files never
  cross), `EventLog`/`ConnectionRegistry` per-space isolation, export→import round-trip (selective + whole-space),
  migrate-command round-trip (flat tree → `spaces/default/` boots identically).
- **Cross-space isolation proof:** real-HTTP test — two spaces, same pipeline id + same connection id, assert events,
  metrics (by `space` label), and data dirs do not bleed.
- **UI:** `lint:tokens` + prod `build` + `test:ci`, plus **preview-iterate** — both servers up, create a space, switch
  spaces in the header, onboard a data source bundle, export it, import into a second space, confirm scoping via the
  network panel (`/spaces/<id>/...`) and a clean console.
- **Migration:** run `migrate` on a copy of the current `inspecto/config` + data, boot from `spaces/default/`, confirm
  pipelines/jobs/events behave identically to pre-migration.
