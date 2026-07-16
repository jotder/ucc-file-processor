# Raw Table Browser — design

> A per-space "database client" in the control plane: browse the raw tables and their data for a
> space, and run ad-hoc read-only SQL. Complements [`../DB_LAYER.md`](../DB_LAYER.md) (what the data
> classes/tables are); this doc is how we expose them for browsing.

## Goal (from the request)

A DB-client-style browser, **per space**, over:
- **Business data** — the Hive-partitioned Parquet/CSV **stores** under the space's `dataDir`.
- **Operational data** — the relational tables in the space's DB-backed control-plane stores
  (`inspecto_ops_objects`, `inspecto_job_runs`, …) — *only when that capability runs on a `db`/`postgres`
  backend* (see constraint below).

Capabilities: **list sources/tables → view column schema + paginated rows (sort + filter) → run ad-hoc
read-only SQL.**

## Hard constraints (why this is a real feature, not a wrapper)

1. **Single-writer lock.** Each operational DuckDB file is opened by exactly one live `Connection` held
   by its store; a second `getConnection` to the same file throws *"could not set lock on file"*. So
   operational tables **must be browsed through the store's own live connection** — never a fresh one.
2. **`Connection` is not concurrency-safe.** The live store connection is also used for writes, so every
   browse query runs **`synchronized` on the store** and is strictly read-only.
3. **Empty by default.** With default backends (`objects=memory`, `status=file`, `jobs/provenance=none`,
   `acquire=memory`) **no operational SQL tables exist**. The browser introspects which capabilities are
   live and shows an empty operational section otherwise — this is the intended degrade, not a bug.
4. **Parquet stores have no lock issue** — they're files. They reuse the existing sandboxed query path
   (`QueryExecutor` opens its own ephemeral DuckDB, reads Parquet by absolute path), so business-data
   browsing is a thin, fully-guarded reuse.

## Reuse map (no new query engine)

| Need | Reuse |
|---|---|
| Read-only SQL allow-list (single `SELECT`/`WITH`, no file/system funcs, no DDL/DML) | `sql.SqlGuard.check(sql)` → `List<Finding>` (422 on any finding) |
| Sandboxed execution + projection/sort/`LIMIT+1` truncation + typed columns | `query.QueryExecutor.run(Request)` → `Result` |
| Parquet/CSV read relation | `sql.SqlViews.reader(format, glob, hive)` + `SqlViews.ext(format)` |
| Enumerate on-disk stores | the `MaintenanceJob` idiom: `Files.list(dataRoot).filter(isDirectory)` ⨯ `ComponentStore.list("dataset")` `physicalRef`s |
| Route module + gates + per-space prefix | `RouteModule`, `WriteGates.requireWriteRoot`, `api.dataRoot()`, `api.service()` |
| Row → wire-value coercion (temporal → ISO) | already inside `QueryExecutor` |

## Route contract (`DbBrowserRoutes`, plain paths — space-scoped by the `SPACE_PREFIX` dispatcher)

All under the auth-free control plane. Read-only, but still **write-root-gated (503)** for consistency
with the house gate order (the browser is an authoring-surface diagnostic, only meaningful when a
write-root/registry exists).

### `GET /db/catalog`
Lists browsable groups for the current space.
```json
{ "groups": [
  { "id": "stores", "label": "Data Stores", "kind": "parquet",
    "tables": [ { "name": "orders", "format": "PARQUET", "dataset": "orders_ds" } ] },
  { "id": "ops:objects", "label": "Operational · Objects", "kind": "operational",
    "engine": "duckdb", "live": true,
    "tables": [ { "name": "inspecto_ops_objects" } ] },
  { "id": "ops:jobs", "label": "Operational · Job Runs", "kind": "operational", "live": false, "tables": [] }
] }
```

### `GET /db/table?group=<id>&name=<table>&limit=&offset=&sort=<field>:<asc|desc>`
Schema + one page of rows. Response = the `QueryRoutes` result shape (typed `columns`, `rows`,
`statistics{rowCount,elapsedMs,truncated}`).
- `kind:parquet` → build `relationSql = SqlViews.reader(format, dataRoot/<name>/**/*.<ext>, true)`,
  register as a view, `SELECT * FROM <view>` via `QueryExecutor` (paginated/sorted).
- `kind:operational` → `synchronized(store) { SELECT * FROM <table> LIMIT ?+1 OFFSET ? }` on the live
  connection; columns from `ResultSetMetaData`. Server-built SQL only.

### `POST /db/query`  `{ "group": "...", "table": "...", "sql": "SELECT ...", "limit": , "offset":  }`
Ad-hoc read-only SQL, scoped to the selected group/table's view (Parquet) or connection (operational).
`SqlGuard.check` → 422; then Parquet runs the user SQL over the registered view via `QueryExecutor`,
operational runs it `synchronized` on the live connection (guard already proved read-only).

**Gate order:** write-root 503 → unknown group/table 404 → `SqlGuard` 422 → execute. No path is
user-supplied as a raw filesystem path (store name is validated against the catalog + identifier-safe),
so the path-jail reduces to "store must appear in the catalog".

## Phase 2 seam — operational tables

A tiny interface so the 7 `Db*Store`s expose their live connection without leaking it widely:
```java
package com.gamma.util;
public interface BrowsableStore {
    String tablePrefix();          // "inspecto_" — which tables to surface
    Object browseLock();           // monitor to synchronize on (the store itself)
    java.sql.Connection browseConnection();
}
```
Default `tables()` / `browse(table, limit, offset)` live on the interface (metadata + server-built
`SELECT`), each `synchronized(browseLock())`. Stores implement three trivial members. `DbBrowserService`
collects the **live** ones from `SourceService` (objects/links/notes/status) + `JobService`
(jobs/provenance) + `AcquisitionLedgers.shared()`, skipping in-memory/file capabilities.

> Ad-hoc SQL against operational tables runs on the shared live connection (guarded, synchronized);
> Parquet ad-hoc SQL runs in the ephemeral sandbox. Both go through `SqlGuard`.

## Phasing

- **Phase 1 — business data (Parquet) + ad-hoc SQL. ✅ SHIPPED.** `DbBrowserRoutes`
  ([`control/DbBrowserRoutes.java`](../../inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java))
  reuses the sandbox path; real-HTTP + real-DuckDB test
  ([`ControlApiDbBrowserTest`](../../inspecto/src/test/java/com/gamma/control/ControlApiDbBrowserTest.java),
  5/5) covers catalog / paginated+sorted table / ad-hoc SQL / all fail-closed gates.
- **Phase 3 — UI. ✅ SHIPPED** (built alongside Phase 1). `modules/admin/data-browser/` — store/table
  list (left) + schema & `<inspecto-data-table>` grid + SQL console (right); nav item under **Catalog**;
  offline `db-browser` mock handler. DoD green (lint:tokens · AOT build · unit+a11y) and preview-verified
  end-to-end (catalog → select → rows → run SQL).
- **Phase 2 — operational tables. ✅ SHIPPED.** `BrowsableStore`
  ([`util/BrowsableStore.java`](../../inspecto/src/main/java/com/gamma/util/BrowsableStore.java)) —
  a read-only seam over a store's live connection; reads run `synchronized(browseMonitor())` (=`this`,
  the same monitor the stores' own `synchronized` methods hold) with a server-added `LIMIT n+1`. All 7
  DB-backed stores implement it (objects/links/notes/status/jobs/provenance/acquisition). `SourceService.
  browsableStores()` collects the live ones; `DbBrowserRoutes` emits an `ops:<id>` catalog group per live
  store and routes `/db/table` + `/db/query` (`SqlGuard`-checked) through `browseTable`/`browseQuery`.
  Real-HTTP test `operationalTablesBrowsableWhenDbBacked` (in `ControlApiDbBrowserTest`) boots
  `objects.backend=db`, seeds a row, and browses it end-to-end. Empty on the default in-memory/file
  backends. **Follow-ups:** promote `DbJobRunStore.detectPostgres` to a shared helper (engine label
  currently reads `getDatabaseProductName()` inline via the default method); a `DbAcquisitionLedger`
  Postgres round-trip test (still the one store absent from `PostgresStateStoreTest`).

## Test plan

Real-HTTP (`ControlApiBiQueryTest` harness): 503 without write-root; `GET /db/catalog` lists a seeded
Parquet store; `GET /db/table` returns typed columns + paginated rows with sort; `POST /db/query` runs a
guarded `SELECT` and **422s** on a blocked statement (e.g. `DROP`, `read_parquet(...)`). Phase 2 adds an
operational-table browse test with `objects.backend=db`. UI: DoD (`lint:tokens` + `build` + `test:ci` +
axe) per the angular-ui skill.
