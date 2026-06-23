# Handover — Multi-space (multi-project) feature

**Date:** 2026-06-23 · **Branch:** `feat/multi-space` (off `master` @ `c5f50a3`) · **Status:** Stages 1–5 of 7
done, committed, **NOT pushed** · **Build:** full reactor green (1033 tests, 0 failures / 0 errors / 1 pre-existing skip).

This is a self-contained handover. The authoritative live state is `SESSION_STATUS.local.md` (top section); the
durable model is in `docs/PROJECT_NOTES.md` (§3 key decision, §4 the per-space-MDC gotcha); the approved design is
`~/.claude/plans/i-want-to-brainstorm-snoopy-flute.md`.

---

## 1. What this feature is

One Inspecto server hosts **many isolated "spaces" (projects) concurrently**. A space is a convention directory
`spaces/<id>/{config,data,audit,duckdb,flows}` + a `space.toon` manifest. Each space is fully isolated (own
service, scheduler, event log, stores, connection registry, metric `space` label). The API/UI scope to one space
at a time and can switch. Foundation for future multitenant + superuser editions.

**Locked decisions (do not relitigate):** one server / many spaces / all concurrent; convention-dir layout;
migration REQUIRED (no flat fallback); data source = bundle (pipeline + connection + schema [+ job/meta]);
export/import = config+metadata only now (ingested-data clone = roadmap); editions/auth = future via the existing
ServiceLoader SPI, **no `if(edition==…)` in core**.

## 2. How it works (architecture)

- **Wrap, don't rewrite.** The ~40-method `@PublicApi` per-instance `SourceService` is untouched. New container:
  `SpaceManager` → `SpaceContext` (one space's `SourceService` + `SpaceRoot` + `SpaceManifest`) → unchanged
  `SourceService`.
- **`SpaceRoot`** decides *where* a space's state lives: `LegacySpaceRoot` (flat cwd defaults, honours every `-D`
  flag → byte-identical single-tenant) and `DirSpaceRoot` (`spaces/<id>/…`).
- **Per-space routing of the 5 process-wide singletons is via the SLF4J `space` MDC.** `EventLog.currentSpaceId()`
  reads MDC key `"space"`, fallback `"default"`. The **`default` space sets NO MDC** → no metric label, fully
  byte-identical single-space behaviour. The singletons: EventLog (per-space instance), MetricRegistry (`space`
  *label*, one global registry + one `/metrics` scrape), ConnectionRegistry (keyed by space), StabilityGate
  (per-space), AcquisitionLedgers (per-space).
- **Request seam:** `ControlApi.dispatch` strips an optional `/spaces/{id}` prefix (regex requires a trailing
  `/<rest>` so bare `/spaces` + `/spaces/{id}` stay server-global for CRUD), 404s an unknown id, binds the space
  on the MDC for the request, and matches the **unchanged** route table against the remainder. RouteModules are
  untouched. `service()`/`writeRoot()` on `ControlApi` resolve per-request from the MDC. `/health` `/ready`
  `/metrics` stay un-prefixed.
- **Entry point:** `ControlApi.main` → `SpaceManager.discover(-Dspaces.root)` (multi) or
  `SpaceManager.single(SourceService.fromArgs(args))` (single-tenant, unchanged).

## 3. What's done (Stages 1–5)

| Stage | Commit | Summary |
|---|---|---|
| 1 + 2a | `44c18ef` | `SpaceRoot` seam (pure refactor) + EventLog per-space via MDC |
| 2b | `157f367` | MetricRegistry `space` label (central MDC merge in `labelKey`) |
| 2c | `0272cc0` | ConnectionRegistry namespaced by space |
| 2d | `d1393ea` | StabilityGate + AcquisitionLedgers per-space; `EventLog.currentSpaceId()` |
| 3a | `8b5a435` | MDC-setting on the execution paths (`runAll`/`runConfigs`/`underSpace`/JobService) |
| 3b | `a12047b` | `SpaceId` + `SpaceContext` + `SpaceBootstrap`; `ServiceBootstrap.buildFrom` |
| 3c | `0a0b7d2` | `SpaceManager` + boot discovery; `ControlApi` holds the manager |
| 4 | `8913786` | `/spaces/{id}` request seam + cross-space isolation proof; closed a 3a gap |
| 5a+5b | `f94f675` | `SpaceManager.create/delete` + `SpaceRoutes` HTTP CRUD |
| 5c | `d780911` | `SpaceMigrator` (flat → `spaces/<id>`) command + spaces docs |

**Runtime CRUD (Stage 5):** `GET /spaces`, `POST /spaces {id,display_name?,description?}` (create + boot, no
restart; 400 bad id, 409 dup/single-tenant), `DELETE /spaces/{id}` (deregister + drain; **`?purge=true`** also
deletes files — purge is opt-in by user decision). `SpaceManager.delete` also calls
`AcquisitionLedgers.unregister(id)` to release the per-space ledger + its DB handle.

**Migration:** `java -cp inspecto.jar com.gamma.service.SpaceMigrator [--id default] [--root ./spaces] [--from .]
[--dry-run] <configDir>` — idempotent, cross-fs-safe. **Caveat:** cannot rewrite *absolute* schema/grammar paths
inside configs (author relative paths); custom non-default flat `-D` locations are manual.

## 4. What's left

**Nothing — all 7 stages are done.** Stage 6 (data-source bundle resolver + zip export/import + import-preview,
`7be7d1d`/`34cacc2`/`d7bb1c8`/`da7a64b`) and Stage 7 (the Angular UI) are complete.

- **Stage 6 (backend) — DONE:** `DataSourceBundle` resolver, zip export/import (`bundle.toon` manifest;
  selective per-data-source **and** whole-space), import-preview dry-run, all over the `/spaces/{id}/` seam.
- **Stage 7 (UI, `inspecto-ui/`) — DONE:** `SpacesService` (signals `currentSpaceId`/`availableSpaces`/
  `multiSpace`/`showSwitcher`; localStorage-restored), a global `spaceInterceptor` that rewrites `/api/<p>` →
  `/api/spaces/<id>/<p>` (every feature service unchanged; no-op single-tenant), the header space-switcher, and
  the `modules/admin/spaces` admin view (list/create/delete+purge/export [space & per-data-source]/import-with-
  preview/create-from-bundle). One additive backend enabler: **`GET /spaces/_meta` → `{multiSpace}`** so the UI
  detects discover vs single-tenant even when the space list is empty. Verified: UI lint:tokens + build +
  test:ci (98 pass) + targeted backend tests + preview smoke — all green.

## 5. Key decisions & deviations (so you don't re-derive them)

- **MDC over a per-request `ApiContext` object (Stage 4).** The plan floated "mint a per-request ApiContext, reject
  ThreadLocal." But Stage 3a already committed MDC as the routing substrate for every singleton, and the route
  lambdas capture the registration-time `api` — so a second mechanism would mean changing the `Handler` signature
  across all ~14 RouteModules *and* still need the MDC. Chose one mechanism (MDC); RouteModules + `Handler`
  untouched. `ControlApi` stays the `ApiContext`; its `service()`/`writeRoot()` read the MDC.
- **DELETE purge is opt-in (`?purge=true`)** — user decision. Plain DELETE only unloads + stops the space; files stay.
- **2c/2d used MDC routing** instead of threading explicit `spaceId` params through `SourceConnectors.forConfig`
  (smaller diff, consistent with 2a/2b, correct-by-construction write side).

## 6. Gotchas / landmines

- **Per-space `space` MDC must reach EVERY worker thread on the execution path.** MDC does not cross thread-pool
  boundaries. `MultiSourceProcessor.runAll`/`runConfigs` **and** `SourceProcessor`'s per-batch executor each
  `getCopyOfContextMap()` on the caller + `setContextMap` on the worker + `clear()` in finally. The
  `SourceProcessor` batch-executor case was a real Stage-3a gap found in Stage 4 (the batch commit / per-batch
  metrics / event log fire there, not on the poll thread) — miss one and that space's metrics/events fall back to
  `"default"`. (See `PROJECT_NOTES.md` §4.)
- **The shared global `MetricRegistry` leaks across tests in one JVM fork.** Multi-space tests that emit
  `space`-labelled series must `MetricRegistry.global().reset()` in cleanup, or they trip `ControlApiTest`'s
  bare-substring metrics poll (`ControlApiMultiSpaceTest` and `ControlApiSpacesTest` already do this).
- **Known minor leftover:** `delete` does NOT tear down the per-space ConnectionRegistry / StabilityGate in-memory
  map entries (no OS handle; cleared on restart). Only matters for delete-then-recreate-same-id isolation. Add a
  `forget(spaceId)` to those two if it ever bites.
- **`SpaceId`** is the single jail: `[a-z0-9][a-z0-9-]{0,62}` — no separators/`..` can escape `spaces/`.

## 7. Build / verify

Offline reactor (DuckDB native-access flag is in the surefire argLine):

```
mvn -o clean test
```

Invariant = **0 failures / 0 errors**. Targeted, e.g.: `mvn -o test -pl inspecto -Dtest=SpaceManagerTest,ControlApiSpacesTest,ControlApiMultiSpaceTest,SpaceMigratorTest`.
After code changes, run `graphify update .` (AST-only, no API cost).

## 8. Where the code lives

- `inspecto/src/main/java/com/gamma/service/` — `SpaceRoot`, `SpaceId`, `SpaceContext`, `SpaceBootstrap`,
  `SpaceManager`, `SpaceMigrator`, `ServiceBootstrap`, `SourceService`.
- `inspecto/src/main/java/com/gamma/control/` — `ControlApi` (dispatch seam), `SpaceRoutes`, `ApiContext`.
- `inspecto/src/main/java/com/gamma/event/EventLog.java` — `SPACE_MDC_KEY`, `currentSpaceId()`, per-space registry.
- `inspecto/src/main/java/com/gamma/metrics/MetricRegistry.java` — `labelKey` MDC merge.
- `inspecto/src/main/java/com/gamma/acquire/` — `AcquisitionLedgers` (per-space + `unregister`), `StabilityGate`,
  `ConnectionRegistry`.
- `inspecto/src/main/java/com/gamma/inspector/` — `MultiSourceProcessor`, `SourceProcessor` (MDC propagation).
- Tests: `…/service/{SpaceManagerTest,SpaceBootstrapTest,SpaceIdTest,SpaceMigratorTest}`,
  `…/control/{ControlApiMultiSpaceTest,ControlApiSpacesTest}`.

## 9. Policy

`feat:` → **master-line only, EMPTY merge-forward set** (no backport to retired lines). **Commit/push/PR/tag only
on explicit ask.** Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Per-stage commits
have been the cadence; nothing is pushed yet. Untracked `file-processor-deploy-old/` is unrelated clutter (a
`.gitignore`/delete candidate — pollutes graphify; not acted on).
