# Database / Persistence Layer
> *Moved from `docs/DB_LAYER.md` (docs consolidation, 2026-07-16).*

> **Scope:** how Inspecto stores state on disk — the three data classes, the operational
> (relational) table schemas, the per-space file topology, and how to run operational data on
> Postgres. Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md) (**Store** = physical backend,
> **Dataset** = queryable relation).

> **⚠️ Keep this current.** This doc is derived from the source files listed below — when any of
> them changes (a table's DDL/columns, a store's backend wiring, the per-space file layout, a
> `-D*.backend` toggle, or Postgres behavior), update the matching section here (and
> [`superpower/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md) if browsable tables/stores
> change). A `PostToolUse` hook (`.claude/hooks/post-tool-db-layer-doc.sh`) reminds you on edits to
> these files. Source of truth for the DDL is each store's `initSchema()` — keep the SQL blocks in §3
> byte-accurate.
> **Derived from:** `ops/DbObjectStore` · `ops/link/DbLinkStore` · `ops/note/DbNoteStore` ·
> `service/DbStatusStore` · `job/DbJobRunStore` · `pipeline/exec/DbProvenanceStore` ·
> `acquire/DbAcquisitionLedger` · `event/ParquetEventStore` · `service/ServiceStores` ·
> `service/SpaceRoot` · `util/JdbcDrivers` · `util/DuckDbUtil`.

---

## 1. Three data classes

Inspecto has **no ORM and no single database**. Persistence is a thin *Store SPI* pattern, and there
are three physically distinct kinds of state:

| Class | What it is | Where it lives | Engine |
|---|---|---|---|
| **Business data** | Ingested rows — the records you actually process | Hive-partitioned Parquet/CSV under `<dataDir>/<store>/**` | Files on disk; queried through DuckDB `read_parquet`/`read_csv`. **Not a database.** |
| **System / config data** | Authored manifests: components, pipelines, views, connections | `registry/*.toon` files via `ComponentStore` / `PipelineStore` / `ViewStore` | Plain files, versioned in `.history/`. **No JDBC.** |
| **Operational data** | Control-plane metadata — facts about the system's *own* operation (alerts, incidents, cases, events, job runs, ingest status, acquisition ledger…) | Per-capability DuckDB files (Postgres-pluggable) + one Parquet-backed store for events | **JDBC** (DuckDB default) — this is the only relational DB layer |

Only the **operational** layer is a database in the SQL sense. This document covers it in full;
business data (the file lake) and config (TOON) are documented in
[`pipeline-graph-design.md`](../pipeline-graph/pipeline-graph-design.md) and [`configuration`](../config/configuration.md) respectively.

### Key seams (source of truth)

| Concern | File |
|---|---|
| Connection factory (driver-by-URL-scheme) | [`util/JdbcDrivers.java`](../../../../inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java) |
| DuckDB engine helpers (ETL path) | [`util/DuckDbUtil.java`](../../../../inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java) |
| Composition root (reads `-D` toggles, opens stores) | [`service/ServiceStores.java`](../../../../inspecto/src/main/java/com/gamma/service/ServiceStores.java) |
| Per-space file locations | [`service/SpaceRoot.java`](../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java) |
| Business-data read-relation builder | [`sql/SqlViews.java`](../../../../inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java) |
| Dataset → physical store resolution | [`query/DatasetRelation.java`](../../../../inspecto/src/main/java/com/gamma/query/DatasetRelation.java) |

---

## 2. Operational store inventory

Each capability owns its own interface + implementations (no shared root interface). All DB
implementations are **plain JDBC over a single shared `Connection`**, with hand-rolled DDL created
**lazily on first open** — there is no migration tool.

| Domain | Interface | DB impl | Backend toggle (`-D…`) | Default |
|---|---|---|---|---|
| Operational objects (ALERT / INCIDENT / CASE / TASK) | `ops/ObjectStore` | [`DbObjectStore`](../../../../inspecto/src/main/java/com/gamma/ops/DbObjectStore.java) | `objects.backend=memory\|db` | `memory` |
| Correlation links | `ops/link/LinkStore` | [`DbLinkStore`](../../../../inspecto/src/main/java/com/gamma/ops/link/DbLinkStore.java) | `objects.backend` (shared) | `memory` |
| Notes / evidence | `ops/note/NoteStore` | [`DbNoteStore`](../../../../inspecto/src/main/java/com/gamma/ops/note/DbNoteStore.java) | `objects.backend` (shared) | `memory` |
| Events (append-only facts) | `event/EventStore` | [`ParquetEventStore`](../../../../inspecto/src/main/java/com/gamma/event/ParquetEventStore.java) *(Parquet, not JDBC)* | `events.backend=memory\|parquet` | `memory` |
| Ingest status / audit projection | `etl/StatusStore` | [`DbStatusStore`](../../../../inspecto/src/main/java/com/gamma/service/DbStatusStore.java) | `status.backend=file\|db` | `file` |
| Job-run reporting | *(class is the API)* | [`DbJobRunStore`](../../../../inspecto/src/main/java/com/gamma/job/DbJobRunStore.java) | `jobs.backend=none\|duckdb\|postgres` | `none` |
| Flow-run provenance (per-edge counts) | *(class is the API)* | [`DbProvenanceStore`](../../../../inspecto/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java) | `provenance.backend=none\|duckdb\|postgres` | `none` |
| Acquisition / dedup ledger + export watermark | `acquire/AcquisitionLedger` | [`DbAcquisitionLedger`](../../../../inspecto/src/main/java/com/gamma/acquire/DbAcquisitionLedger.java) | `acquire.ledger.backend=memory\|db` *(via `AcquisitionLedgers`, not `ServiceStores`)* | `memory` |
| Ops escalation queues | `ops/queue/QueueStore` | **none** — in-memory only | — | — |
| Pipeline execution watermarks | `pipeline/exec/PipelineWatermarkStore` | **none** — in-memory/file only | — | — |

> **`ALERT`s are not their own table.** Alerts, incidents, cases and tasks are all rows in
> `inspecto_ops_objects`, discriminated by the `object_type` column
> ([`ObjectType`](../../../../inspecto/src/main/java/com/gamma/ops/ObjectType.java): `ALERT, INCIDENT, CASE, TASK`).

Every backend **degrades gracefully**: a failed DB open falls back to in-memory/file and logs a
warning rather than blocking startup.

---

## 3. Schemas (operational, non-Parquet)

Exact DDL as created by each store's `initSchema()`. All columns are `VARCHAR`/`BIGINT` only (no
engine-specific types), ids are application-generated strings (no auto-increment), and timestamps are
epoch-millis `BIGINT`. `attributes`/`payload` columns hold JSON serialized as text.

Legend: **A** = append-only (insert only), **M** = mutable (update/delete in place).

### 3.1 `inspecto_ops_objects` — alerts / incidents / cases / tasks  · **M**
File: `inspecto-ops.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_objects (
  id             VARCHAR PRIMARY KEY,
  object_type    VARCHAR,   -- ALERT | INCIDENT | CASE | TASK
  title          VARCHAR,
  description     VARCHAR,
  status         VARCHAR,
  severity       VARCHAR,
  priority       VARCHAR,
  "owner"        VARCHAR,   -- quoted: reserved word
  assignee       VARCHAR,
  correlation_id VARCHAR,
  attributes     VARCHAR,   -- JSON
  created_at     BIGINT,    -- epoch ms
  updated_at     BIGINT,
  closed_at      BIGINT
);
```

### 3.2 `inspecto_ops_links` — correlation edges  · **A**
File: `inspecto-ops-links.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_links (
  from_id      VARCHAR,
  from_type    VARCHAR,
  to_id        VARCHAR,
  to_type      VARCHAR,
  relationship VARCHAR,
  created_at   BIGINT
);
```

### 3.3 `inspecto_ops_notes` — notes / evidence  · **M**
File: `inspecto-ops-notes.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_ops_notes (
  id         VARCHAR PRIMARY KEY,
  object_id  VARCHAR,   -- FK (by convention) → inspecto_ops_objects.id
  kind       VARCHAR,
  author     VARCHAR,
  body       VARCHAR,
  attributes VARCHAR,   -- JSON
  created_at BIGINT
);
```

### 3.4 `inspecto_status_*` — ingest status / audit projection  · **A**
File: `inspecto-status.db` (legacy `ucc-status.db` auto-renamed on open)

Five append-only projection tables. `payload` is the JSON record; `seq` orders events within a pipeline.

```sql
CREATE TABLE IF NOT EXISTS inspecto_status_commits    (pipeline VARCHAR, batch_id VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_batches    (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_files      (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_lineage    (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR);
CREATE TABLE IF NOT EXISTS inspecto_status_quarantine (pipeline VARCHAR, seq BIGINT, payload VARCHAR);
```

### 3.5 `inspecto_job_runs` — job-run reporting  · **A**
File: `jobs_report.duckdb`

```sql
CREATE TABLE IF NOT EXISTS inspecto_job_runs (
  run_id      VARCHAR,
  job         VARCHAR,
  type        VARCHAR,
  "trigger"   VARCHAR,   -- quoted: reserved word
  start_time  VARCHAR,   -- ISO-8601 string
  end_time    VARCHAR,
  status      VARCHAR,
  duration_ms BIGINT,
  message     VARCHAR
);
```

### 3.6 `inspecto_flow_provenance` — per-edge row counts  · **A**
File: `provenance.duckdb`

```sql
CREATE TABLE IF NOT EXISTS inspecto_flow_provenance (
  flow_id   VARCHAR,
  batch_id  VARCHAR,
  node_id   VARCHAR,
  rel       VARCHAR,
  row_count BIGINT,
  run_ts    VARCHAR
);
```

### 3.7 `inspecto_acquisition_ledger` + `_db_watermark` — acquisition dedup  · ledger **M**, watermark **M**
File: `inspecto-acquisition.db`

```sql
CREATE TABLE IF NOT EXISTS inspecto_acquisition_ledger (
  source_id      VARCHAR,
  relative_path  VARCHAR,
  name           VARCHAR,
  size           BIGINT,
  checksum       VARCHAR,
  etag           VARCHAR,   -- added in place for pre-ACQ-7 ledgers
  object_version VARCHAR,   -- (named to avoid the reserved word `version`)
  last_modified  BIGINT,
  processed_at   BIGINT,
  status         VARCHAR,
  PRIMARY KEY (source_id, relative_path)
);

CREATE TABLE IF NOT EXISTS inspecto_acquisition_db_watermark (
  source_key      VARCHAR,
  watermark_value VARCHAR,
  advanced_at     BIGINT,
  PRIMARY KEY (source_key)
);
```

### 3.8 Events — append-only, Parquet (not a SQL table)  · **A**

`ParquetEventStore` writes rolling **Hive-partitioned Parquet** under `<eventsDir>/year=/month=/day=/`,
read back through an in-memory DuckDB connection (`evt_buf` is only a transient write buffer). The event
record shape:

```
event_id, ts_ms (BIGINT), type, source, pipeline, correlation_id,
message, attributes (JSON), payload (JSON), level  -- + partition cols year, month, day (VARCHAR)
```

`level` ∈ [`EventLevel`](../../../../inspecto/src/main/java/com/gamma/event/EventLevel.java). There is **no
JDBC/Postgres event table** — events are Parquet-only.

---

## 4. File topology (per space)

**One DuckDB file per capability** — not one shared DB, and not one file per space. Each file is
single-writer-locked (documented in `ServiceStores`). Locations come from
[`SpaceRoot`](../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java):

| Layout | Capability file locations |
|---|---|
| **`DirSpaceRoot`** (per-space dir) | `<spaceBase>/duckdb/<file>` — e.g. `spaces/demo/duckdb/inspecto-ops.db` |
| **`LegacySpaceRoot`** (flat working dir) | `./inspecto-ops.db`, `./inspecto-ops-links.db`, `./inspecto-ops-notes.db`, `./inspecto-status.db`, `./jobs_report.duckdb`, `./provenance.duckdb`, `./inspecto-acquisition.db` |

So across N spaces you get N separate sets of these files. Events live under `<dataDir>/events/`
(`DirSpaceRoot`) or `./inspecto-events/` (legacy). Every `-D<capability>.db.url` flag overrides the
per-space default explicitly — note that a global `-D*.db.url` therefore funnels EVERY space into one
shared file; leave them unset in multi-space mode so each space keeps its own `duckdb/` set.

`DirSpaceRoot` **mints `<spaceBase>/duckdb/` on first URL build** (`SpaceRoot.java`, guarded by
`SpaceRootTest`): repo-checked-out spaces gitignore `duckdb/` and DuckDB does not create parent
dirs, so without the mkdir every DB-backed store silently degraded to in-memory on a fresh checkout.

---

## 5. Running operational data on Postgres

The layer was **designed** for this: stores are JDBC-pluggable by URL scheme, the DDL is deliberately
portable (`VARCHAR`/`BIGINT`, composite PKs, no auto-increment, no upserts — explicit DELETE-then-INSERT),
and there is a **real embedded-Postgres round-trip test**
([`PostgresStateStoreTest`](../../../../inspecto/src/test/java/com/gamma/service/PostgresStateStoreTest.java))
covering 6 of the 7 DB-backed stores.

### 5.1 Flags (all read in `ServiceStores` unless noted)

| Capability | Backend flag | URL flag | Credentials |
|---|---|---|---|
| Objects **+ links + notes** | `-Dobjects.backend=db` | `-Dobjects.db.url`, `-Dobjects.links.db.url`, `-Dobjects.notes.db.url` | `-Dobjects.db.user` / `-Dobjects.db.password` (shared) |
| Status | `-Dstatus.backend=db` | `-Dstatus.db.url` | `-Dstatus.db.user` / `.password` |
| Jobs | `-Djobs.backend=postgres` | `-Djobs.db.url` | (in URL) |
| Provenance | `-Dprovenance.backend=postgres` | `-Dprovenance.db.url` | (in URL) |
| Acquisition ledger | `-Dacquire.ledger.backend=db` | (property in [`AcquisitionLedgers`](../../../../inspecto/src/main/java/com/gamma/acquire/AcquisitionLedgers.java)) | — |
| Events | `-Devents.backend=parquet` | — | **No Postgres path** |

Point each URL at `jdbc:postgresql://…`; the three ops URLs may share one database/schema (table names
don't collide).

### 5.2 Dialect notes & landmines

- **Only `DbJobRunStore` has a dialect branch** — it swaps DuckDB `quantile_cont(col,p)` for Postgres
  `percentile_cont(p) WITHIN GROUP (ORDER BY col)`. The probe behind it is the shared
  `JdbcDrivers.isPostgres(Connection)` (also backs `BrowsableStore.browseEngine()`'s catalog label).
  Everything else is ANSI SQL (incl. `FILTER (WHERE …)`, supported by both engines).
- **`CHECKPOINT` in `maintenance()`** (`DbJobRunStore`, `DbAcquisitionLedger`) is superuser-only on
  Postgres — currently caught-and-logged, so it degrades to a no-op VACUUM cycle. Verify that's acceptable.
- Reserved words already quoted: `"owner"`, `"trigger"`; `object_version` deliberately avoids `version`.
- No `COPY`, sequences, or DuckDB-specific types in the operational stores — those idioms live only in
  the **business-data** path (out of scope here).
- **No connection pooling anywhere** — every store uses one raw `DriverManager` connection. A real
  Postgres deployment should add a pool (e.g. HikariCP); it doesn't exist today.

### 5.3 Migration checklist

1. Put the Postgres JDBC driver on the runtime classpath (`org.postgresql.Driver`).
2. Set the flags in §5.1 with `jdbc:postgresql://…` URLs + credentials.
3. **Existing DuckDB rows don't move automatically** — there is no export/import tool
   (`BackupTask` is a filesystem zip, not a DB-row mover). Either write a one-off per-table
   `SELECT → INSERT` script, or accept a clean cutover with empty Postgres tables that the writers
   repopulate going forward.
4. Before relying on it: confirm the `CHECKPOINT` no-op is fine. (All seven JDBC stores now have a
   Postgres round-trip in `PostgresStateStoreTest`, including `DbAcquisitionLedger`.)
5. Events cannot move — `ParquetEventStore` has no DB sibling; moving events off Parquet needs new code.

For the 7 covered stores this is essentially a **configuration change** — flags + URLs + driver + a
Postgres instance — not a code change.

---

## 6. Browsing the raw tables

The **Data Browser** pane (a per-space DB client) browses these stores live. Backend: `/db/catalog`,
`/db/table`, `/db/query` in [`control/DbBrowserRoutes.java`](../../../../inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java)
(read-only, `SqlGuard`-checked). UI: `inspecto-ui` → **Catalog › Data Browser**. Design + phasing in
[`superpower/db-browser-design.md`](../../../archived-documents/plans-archive/db-browser-design.md).

- **Business-data stores** (§1) read via an ephemeral DuckDB sandbox (`read_parquet`/`read_csv`).
- **Operational tables** (§3) browse through each store's *live* connection via
  [`util/BrowsableStore.java`](../../../../inspecto-util/src/main/java/com/gamma/util/BrowsableStore.java) —
  reads are `synchronized` on the store (single-writer lock) and appear only when that capability runs on
  a `db`/`postgres` backend. Every `Db*Store` in §2/§3 implements this seam.
