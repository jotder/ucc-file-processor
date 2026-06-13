# Spec: Operational Intelligence Platform — Five-Phase Implementation Roadmap

> **Date:** 2026-06-13
> **Status:** Phases 1–4 shipped on `4.x` (Phase 4 = CASE + OBJECT_LINK; its comments/attachments
> deferred); Phase 5 planned (implementation roadmap)
> **Branch:** `4.x`
> **Source requirement:** [ticketing_systems_requirement.md](../../ticketing_systems_requirement.md)
> **Builds on (confirmed seams):** `com.gamma.etl.BatchEvent` / `com.gamma.service.BatchEventBus`
> (in-process pub/sub), `com.gamma.alert.*` (Alert/AlertRule/AlertService — Phase-2 seed already
> present), `com.gamma.service.StatusStore` + `DbStatusStore` (JDBC-over-DuckDB projection pattern),
> `com.gamma.etl.PartitionWriter` (DuckDB `COPY … TO … (FORMAT PARQUET, PARTITION_BY …)`),
> `com.gamma.sql.SqlViews` (`read_parquet(… hive_partitioning=true)`), `com.gamma.control.ControlApi`
> (JDK `HttpServer`, scoped routes), `com.gamma.metrics.MetricRegistry`.
>
> This document is a **self-contained implementation roadmap**. Each phase maps the requirement's
> intent onto concrete Inspecto packages/classes/endpoints, marks what already exists, and is grounded
> in real seams — confirm the cited classes before coding.

---

## 0. Architectural decision — storage substrate (answers "can rolling Parquet be the database?")

**Short answer: yes for the EVENT layer, no for the operational-object layer.** The requirement itself
splits the world into exactly these two layers, and they have opposite storage needs:

| Requirement layer | Nature | Right substrate | Why |
| :---------------- | :----- | :-------------- | :-- |
| **Layer 1 — `EVENT`** (immutable facts) | append-only, never modified, high-volume, structured | **Rolling Parquet, queried by DuckDB** | Columnar + compressed + write-once is Parquet's sweet spot. **The repo already does this**: `SqlViews.parquetScan` / `ParquetSummarizer` / `EnrichmentEngine` read `read_parquet('<root>/**/*.parquet', hive_partitioning=true)`, and `PartitionWriter` already writes Hive-partitioned Parquet via DuckDB `COPY`. No new technology. |
| **Layer 2 — Operational objects** (`ALERT`/`ISSUE`/`CASE`/`TASK`) | **mutable** (status `OPEN→ACK→RESOLVED…`), low-volume, relational | **DuckDB / PostgreSQL table** (the existing `DbStatusStore` JDBC pattern) | These rows *change*. Parquet cannot do that. |

### Why rolling Parquet works *as a database* for events

- The requirement defines `EVENT` as **append-only / never modified / high-volume** — which is precisely
  what a columnar immutable file format is built for.
- DuckDB turns "a directory of Parquet files" into "a table": one `read_parquet(glob, hive_partitioning=true)`
  view gives you full SQL search, filtering, aggregation, time-range scans, and `COPY … TO` export — all
  the Phase-1 "Event Viewer" verbs — with zero extra dependencies (DuckDB is already bundled).
- "Rolling" = a new file per time window (hour/day) under Hive partitions
  (`type=…/year=…/month=…/day=…`). Old windows age out by deleting whole files/dirs (retention is a
  `rm`, not a `DELETE`).

### Where rolling Parquet is *not* a database (the honest caveats)

1. **No in-place UPDATE/DELETE.** Immutable. Fine for events (never modified); **disqualifying for the
   mutable operational objects** of Phase 2+. Those go in a table store.
2. **No low-latency single-row writes.** Writing one file per event = the "small-files problem." You
   **buffer in memory and flush in batches** (size- or time-triggered). Sub-second durability of the
   newest events comes from the in-memory buffer, not the disk.
3. **No transactions / multi-writer coordination / secondary indexes / constraints.** It is an
   *analytical* store, not OLTP. Partition pruning (by `level`/date) is the only "index."
4. **Live tail** = the in-memory buffer (not-yet-flushed) **unioned with** the on-disk Parquet.

### Net design (matches the requirement's own layering)

```
Layer 1  EVENT (immutable facts)        → rolling Parquet  + DuckDB read_parquet   ← Phase 1
Layer 2  Operational objects (mutable)  → DuckDB/Postgres table (DbStatusStore-style) ← Phase 2+
Layer 3  Platform services (search/filter/saved-views/export/correlation/audit) → reused across both
```

---

## 1. The reuse thesis (why this is ~70–90% shared, per the requirement)

The requirement insists: build the **platform**, not five apps. Inspecto already has the spine:

| Requirement "engine" | Already in Inspecto | Phase that hardens it |
| :------------------- | :------------------ | :-------------------- |
| **Event Engine** | `BatchEvent` + `BatchEventBus` (commit/fail events); emission points across ingest/enrich/job | **Phase 1** generalizes this into a typed `Event` + durable `EventStore` |
| **Object Engine** | — (Alerts are in-memory only today) | **Phase 2** introduces one `OPERATIONAL_OBJECT` table for ALERT/ISSUE/CASE/TASK |
| **Search / Filter / Export** | DuckDB SQL + `SqlViews` + `COPY … TO`; `ControlApi` query routes | Phase 1 (events), Phase 2+ (objects) |
| **Workflow Engine** | `AlertService` lifecycle (implicit); `JobType` state | Phase 2 (config-driven state machine) |
| **Notification Engine** | `MetricRegistry`, `log.warn("[ALERT] …")`; agent's `diagnose-and-alert` | Phase 2 |
| **Rule / Correlation Engine** | `AlertService` rule eval; `MetadataGraphService` (catalog graph) | Phase 2 (rules), Phase 4 (`OBJECT_LINK` graph) |
| **Security Engine** | `ControlApi` scoped bearer tokens (PUBLIC/CONTROL/ASSIST_*) | all phases |
| **Intelligence** | `inspecto-agent` (assist skills, `SqlOracle`, catalog) | **Phase 5** |
| **UI Framework** | `inspecto-ui` (Angular, ag-Grid, multi-pane) | all phases (Event-Viewer-style explorer) |

So each phase is mostly **promotion + persistence + lifecycle** on top of seams that already exist.

---

## 2. Five-Phase Roadmap (mapped to the codebase)

### Phase 1 — Operational Event Viewer  ✅ shipped (v4.2.0; detail in §3)
**Outcome:** "What happened?" — durable, searchable, structured events.
- **New package `com.gamma.event`**: `Event` (immutable record), `EventLevel`, `EventType`,
  `EventStore` (SPI), `InMemoryEventStore` + `ParquetEventStore` (rolling), `EventQuery`, `EventLog`
  (process-wide facade, mirrors `MetricRegistry.global()`).
- **Ingestion (two paths):** (a) **domain emitters** at lifecycle points (job/batch/file/pipeline/alert);
  (b) **SLF4J capture** of `INFO`/`WARN`/`ERROR` (DEBUG/TRACE excluded — the "everything except debug" rule).
- **Storage:** rolling Hive-partitioned Parquet via the existing `PartitionWriter`; queried via
  `SqlViews.parquetScan(... hive_partitioning=true)`.
- **Surface:** new `ControlApi` routes `/events`, `/events/{id}`, `/events/search`, `/events/stream`
  (live tail), `/events/export`, `/events/views` (saved views). Reuse scoped-token auth + Jackson.
- **Correlation IDs:** `correlationId` = batchId / job-run-id, threaded through emitters.
- **Metrics:** `inspecto_events_total{level,type}` counter on the existing `MetricRegistry`.

### Phase 2 — Alert Center  ✅ shipped (commit `4933542`; as built in §4)
**Outcome:** "Should I care?" — promote alerts from a transient ring to managed objects.
- **`com.gamma.ops.OperationalObject`** + **`ObjectStore`** (DuckDB/Postgres table, `DbStatusStore`
  JDBC pattern): one table keyed by `object_type` (`ALERT` first). Columns per requirement:
  `id, object_type, title, description, status, severity, priority, owner, assignee, created_at,
  updated_at, closed_at`.
- **Workflow Engine** (`com.gamma.ops.workflow`): config-driven state machine; Alert =
  `OPEN→ACKNOWLEDGED→RESOLVED`. Transitions append `OBJECT_ACTIVITY` events (reuse Phase-1 events).
- **Promote existing `AlertService`** to *persist* a fired `Alert` as an `OPERATIONAL_OBJECT(ALERT)`
  and **link** it to the triggering event (`OBJECT_LINK` Alert→Event, `CAUSED_BY`).
- **Notifications:** `NotificationService` SPI (log/webhook/email later); ack/escalation endpoints.
- **Adds routes:** `/alerts/{id}/ack`, `/alerts/{id}/resolve`, `/objects?type=ALERT&status=…`.

### Phase 3 — Issue Tracker  ✅ shipped (v4.4.0; as built in §5)
**Outcome:** "Manage the problem." — same `OPERATIONAL_OBJECT` table, `object_type=ISSUE`.
- Lifecycle `OPEN→ASSIGNED→IN_PROGRESS→RESOLVED→CLOSED`; ownership/assignee/priority already columns.
- **SLA tracking:** due-at + breach events (Phase-1 events) + a scheduled sweep on the existing
  `Scheduler`. Impact assessment = links to affected pipelines (catalog).
- Reuses the Object Engine, Workflow Engine, search/filter, comments/activity — **no new storage**.

### Phase 4 — Case Management  ✅ shipped (v4.5.0; as built in §6) — comments/attachments deferred
**Outcome:** "Investigate." — `object_type=CASE`, lifecycle `OPEN→INVESTIGATING→ESCALATED→RESOLVED→CLOSED`.
- **`OBJECT_LINK`** correlation graph (`from,from_type,to,to_type,relationship`) becomes first-class:
  `Case CONTAINS Issue`, `Issue ESCALATED_FROM Alert`, `Alert CAUSED_BY Event`. Render via the existing
  `MetadataGraphService` traversal machinery (it already does typed nodes/edges + depth/direction).
- **Evidence/Notes/Attachments:** `OBJECT_ATTACHMENT` + `OBJECT_COMMENT` tables; RCA templates as
  authored `.toon` (reuse `ConfigCodec`/`/config/write`).

### Phase 5 — Operational Intelligence
**Outcome:** "Learn." — route through the existing **`inspecto-agent`** (optional, classpath-gated).
- AI RCA / similar-incident / event clustering / NL-query / recommendations as **assist skills**
  (mirror `diagnose-and-alert`, `kpi-to-sql`, `report-sql`): the agent reasons, a human saves/acts; the
  lean core stays deterministic. Predictive alerting = new `AlertRule` metric types over the event store.

---

## 3. Phase 1 — implementation detail (as built)

### 3.0 Architecture at a glance (as built)

Two ingest paths converge on one process-wide sink (`EventLog.global()`), which writes to a single
append-only `EventStore`; the backend is a deployment choice (`-Devents.backend=memory|parquet`), and
the `/events*` API reads back through the same store.

```mermaid
flowchart TD
    subgraph ingest["Ingest — INFO and above"]
        SLF["SLF4J capture<br/>INFO · WARN · ERROR<br/>(DEBUG / TRACE dropped)"]
        DOM["Domain emitters<br/>batch · alert · pipeline"]
    end
    LOG["EventLog.global()<br/>process-wide sink"]
    STORE["EventStore<br/>append-only SPI"]
    subgraph backends["Storage backend — -Devents.backend"]
        MEM["InMemoryEventStore<br/>bounded ring · default"]
        PQ["ParquetEventStore<br/>rolling Parquet → DuckDB"]
    end
    API["/events* API<br/>search · export · saved views"]

    SLF --> LOG
    DOM --> LOG
    LOG --> STORE
    STORE --> MEM
    STORE --> PQ
    STORE --> API

    classDef ingest fill:#E1F5EE,stroke:#0F6E56,color:#04342C;
    classDef engine fill:#F1EFE8,stroke:#5F5E5A,color:#2C2C2A;
    classDef storage fill:#EEEDFE,stroke:#534AB7,color:#26215C;
    classDef api fill:#FAECE7,stroke:#993C1D,color:#4A1B0C;
    class SLF,DOM ingest;
    class LOG,STORE engine;
    class MEM,PQ storage;
    class API api;
```

Capture is a logback `EventStoreAppender` (ThresholdFilter at INFO — the "everything except debug"
rule); `ParquetEventStore` buffers then flushes batches to Hive-partitioned Parquet
(`level/year/month/day`), queried via DuckDB `read_parquet` — the immutable-event layer of §0. The
live tail merges the not-yet-flushed in-memory buffer with the on-disk Parquet.

### 3.1 New package `com.gamma.event`

```
Event           record: eventId, ts(epochMillis), level(EventLevel), type(String),
                        source, pipeline?, correlationId?, message, attributes(Map<String,String>)
                        + toMap() (JSON-ready, LinkedHashMap, stable order)
EventLevel      enum  : TRACE, DEBUG, INFO, WARN, ERROR  (capture threshold = INFO)
EventType       const : LOG, SERVICE_STARTED, PIPELINE_REGISTERED, PIPELINE_PAUSED/RESUMED,
                        JOB_STARTED/SUCCEEDED/FAILED, BATCH_COMMITTED/BATCH_FAILED,
                        FILE_RECEIVED/FILE_QUARANTINED, ENRICHMENT_RUN, ALERT_FIRED, CONFIG_VALIDATED …
EventQuery      record: fromMs?, toMs?, minLevel?, type?, pipeline?, correlationId?, textContains?,
                        limit, offset
EventStore      iface : void append(Event);  List<Event> query(EventQuery);  List<Event> recent(int);
                        (append-only; no update/delete)
InMemoryEventStore    : bounded ring (CopyOnWrite/ArrayDeque), capacity-bounded; default lean store,
                        also serves the live-tail buffer + tests
ParquetEventStore     : buffers events; flush on size (e.g. 1000) or time-roll; writes via
                        PartitionWriter.write(conn,"evt_buf",root,"PARQUET","zstd","events_"+flushId,
                        List.of("level","year","month","day"), List.of());
                        query() builds SqlViews.parquetScan(root, hive=true) + WHERE; recent() from buffer
EventLog        final : process-wide facade — EventLog.global(); emit(Event)/emit(level,type,…);
                        installStore(EventStore). Mirrors MetricRegistry.global() so any layer emits
                        without constructor threading; also bumps the events_total metric.
```

**ParquetEventStore flush mechanics (reuse, don't reinvent):** maintain one DuckDB connection
(`DuckDbUtil`); on flush, insert the buffered rows into a scratch `evt_buf` table (cols incl. derived
`year/month/day` + `attributes` as JSON string), call the existing **`PartitionWriter.write(...)` full
overload** with `excludeColumns = List.of()` and a unique `baseName = "events_" + flushId` (so each flush
yields distinct files per partition — no overwrite), then `DELETE FROM evt_buf`. Query path = a DuckDB
view over `read_parquet('<root>/**/*.parquet', hive_partitioning=true)` (via `SqlViews`) with `WHERE`
from `EventQuery`; live tail merges the in-memory buffer.

### 3.2 "Everything except DEBUG" — the SLF4J capture decision

The repo binds **`slf4j-simple`**, which has **no appender SPI** — so auto-capturing existing
`log.info/warn/error` calls requires one of:

- **(A) Domain emitters only** *(lean, no dependency change):* emit rich structured `Event`s at the ~12
  lifecycle points listed in `EventType`. DEBUG internal logging stays console-only. Satisfies the
  requirement's "structured events instead of log parsing" intent, but does **not** auto-capture
  arbitrary `log.warn(...)` lines elsewhere.
- **(B) Domain emitters + SLF4J auto-capture** *(swap `slf4j-simple` → `logback-classic`, add a custom
  `EventStoreAppender` with a `ThresholdFilter` at `INFO`):* literally "everything except debug" — every
  existing `log.info/warn/error` becomes an `Event`, **plus** the rich domain emitters. Console output is
  preserved by a parallel `ConsoleAppender`. One standard dependency; touches the "keep core lean" guardrail.

**Recommendation: (B)** — it is the literal reading of the requirement and the only option that captures
the long tail of existing log sites without rewriting call sites. (A) is the fallback if a dependency
swap is unwanted. *Either way the domain emitters are built.*

### 3.3 Wiring (where Phase 1 plugs into the running service)

- **`SourceService`** constructs `EventLog.global()` store at startup (parallel to `MetricRegistry`),
  emits `SERVICE_STARTED`, and **bridges the existing bus**: `bus.subscribe(ev -> EventLog.emit(
  BATCH_COMMITTED/BATCH_FAILED from BatchEvent))` — so every committed/failed batch is already an event
  on day one (zero new emission points needed for the headline signal).
- **Emitters** added at: pipeline register/pause/resume (`SourceService`), job start/finish
  (`JobService`), file received/quarantined (ingest/`QuarantineManager`), alert fired (`AlertService`,
  ties Phase 1↔2). Each sets `correlationId = batchId|runId`.
- **`ControlApi`**: register `/events*` routes (CONTROL scope; `/events/stream` may be PUBLIC-read like
  `/metrics` if desired). `EventStore` reached via `service.events()`.
- **Backend selection** mirrors `status.backend`: `-Devents.backend=parquet|memory` (default `memory`
  for the lean fat-JAR; `parquet` for durable deployments), `-Devents.dir=inspecto-events`.

### 3.4 Acceptance (Phase 1)

- `Event`/`EventQuery`/`EventStore` + both stores, `EventLog`, capture bridge — unit tested
  (in-memory + a Parquet round-trip: append → flush → `read_parquet` query → filter by level/type/time).
- Every terminal batch produces a queryable `BATCH_COMMITTED|BATCH_FAILED` event end-to-end.
- `GET /events`, `/events/{id}`, `/events/search?level=&type=&pipeline=&from=&to=&q=`,
  `/events/export?format=csv|json`, `/events/views` (CRUD saved views) return as specified; auth scoped.
- `inspecto_events_total{level,type}` visible on `/metrics`.
- Full reactor stays green; no behavior change when `events.backend=memory` and capture-mode (A).

---

## 4. Phase 2 — Alert Center (as built)

Shipped on `4.x` (commit `4933542`) — promotes fired alerts from a transient ring into mutable,
lifecycle-managed objects. Realizes §2 Phase 2 and the §0 decision (mutable objects → table store, not
Parquet). Full reactor green: core 511 (+27) + agent 157 + hosted 4 = 672. **Zero new dependencies** —
DuckDB JDBC + Jackson were already core, so `inspecto/pom.xml` is untouched; additive-only.

### 4.1 New package `com.gamma.ops` (the Object Engine)

```
ObjectType           enum  : ALERT, ISSUE, CASE, TASK   (Phase 2 uses ALERT; one table serves all)
OperationalObject    record: id, objectType, title, description, status, severity, priority, owner,
                             assignee, correlationId, attributes(Map), createdAt, updatedAt, closedAt
                             + builder, withStatus(state,now,terminal), withAssignee, toMap()
ObjectQuery          record: objectType?, status?, severity?, assignee?, owner?, correlationId?,
                             textContains?, limit, offset  + matches() + builder  (mirrors EventQuery)
ObjectStore          iface : create, get, query, UPDATE, close   (mutable — the opposite of EventStore)
InMemoryObjectStore        : map-backed; the lean default (-Dobjects.backend=memory)
DbObjectStore              : JDBC over the bundled DuckDB (or BYO Postgres); table inspecto_ops_objects;
                             real UPDATE on transition — the DbStatusStore idiom (§ status backend)
```

### 4.2 Workflow Engine (`com.gamma.ops.workflow`)

`Workflow` is a config-driven state machine per `ObjectType`. The built-in `defaultFor(ALERT)` is
`OPEN --ack--> ACKNOWLEDGED --resolve--> RESOLVED` (plus a direct `OPEN --resolve--> RESOLVED`); `RESOLVED`
is terminal (sets `closedAt`). States/actions match case-insensitively. A `*_workflow.toon` overrides the
default (`Workflow.load`, the TOON `transitions[N]{from,to,action}:` tabular form) — "configuration over
custom code". **`ObjectService`** is the orchestrator: `open` (create in the initial state) and
`transition`/`ack`/`resolve`/`transitionTo` (workflow-checked), each persisted via `ObjectStore` and
recorded as a Phase-1 event (`OBJECT_OPENED` / `OBJECT_ACTIVITY`) on `EventLog.global()` — so an object's
history shows inline in the Event Viewer. `active(type, correlationId)` returns the non-terminal objects
(used for dedup).

### 4.3 Wiring

- **`AlertService`** gained a `(rules, configs, status, ObjectService)` constructor: each fired alert is
  promoted to an `OPERATIONAL_OBJECT(ALERT, OPEN)` linked to the firing event via a `causedByEvent`
  attribute, deduplicated while a non-terminal object exists for the same rule+pipeline. The prior 3-arg
  constructor stays events-only (abstain-safe).
- **`SourceService`** builds the store from `-Dobjects.backend=memory|db` (default `memory`;
  `-Dobjects.db.url` default `jdbc:duckdb:inspecto-ops.db`, a failed DB open degrades to in-memory) and
  exposes `objects()` — always present, so `/objects` works even with no alert rules.
- **`EventType`** gained `OBJECT_OPENED` / `OBJECT_ACTIVITY`.

### 4.4 Surface (`ControlApi`, CONTROL scope)

- `GET /objects?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=` — filtered list.
- `GET /objects/{id}` — one object, or 404.
- `POST /objects/{id}/ack`, `/objects/{id}/resolve` — fixed-action transitions (`{actor?}` body).
- `POST /objects/{id}/transition` — `{action}` or `{status|to}` (+ optional `actor`).
- Errors: 404 unknown id, 422 illegal transition, 400 bad type / empty transition body, 401 unauthenticated.
  The existing `/alerts*` routes (fired-alert ring, rules, evaluate) are unchanged.

### 4.5 Acceptance (met)

- A fired alert is queryable as `OPERATIONAL_OBJECT(ALERT, OPEN)`; `ack`/`resolve` walk it
  `OPEN → ACKNOWLEDGED → RESOLVED` with `OBJECT_ACTIVITY` events appearing in `/events`.
- Illegal transitions → 422; auth scoped; additive-only (Phase-1 + `/alerts*` behaviour unchanged).
- **Deferred to Phase 4** (per §2): the first-class `OBJECT_LINK` correlation graph — Phase 2 links
  lightly via `correlationId` + `causedByEvent`. **Phase 3 (Issue Tracker)** reuses this exact table +
  workflow engine with `object_type=ISSUE` — no new storage.

---

## 5. Phase 3 — Issue Tracker (as built)

Shipped on `4.x` (v4.4.0) — adds operator-managed **ISSUE** objects on the *same* Phase-2 engine and
table. Realizes §2 Phase 3. **Zero new dependencies and zero new storage**: issues are
`object_type=ISSUE` rows in `inspecto_ops_objects`, and the SLA deadline rides the existing
`attributes` JSON — so `inspecto/pom.xml` and the table schema are untouched. Full reactor green:
core 516 (+5) + agent 157 + hosted 4 = 677.

### 5.1 ISSUE lifecycle (Workflow Engine)

`Workflow.defaultFor(ISSUE)` replaces the placeholder with
`OPEN --assign--> ASSIGNED --start--> IN_PROGRESS --resolve--> RESOLVED --close--> CLOSED`. Only
`CLOSED` is terminal (sets `closedAt`); `RESOLVED` is deliberately **not** terminal, so the SLA clock
(which stops at `RESOLVED`) is distinct from closure. As with ALERT, a `*_workflow.toon` can override
it. No new engine code — `ObjectService.transition`/`transitionTo` already drive any workflow action.

### 5.2 Issue creation (`POST /objects`)

Alerts are auto-promoted; issues are operator-created, so Phase 3 adds the one missing verb:
`POST /objects` (CONTROL scope) → `ObjectService.open(...)` in the workflow's initial state. Body
`{type?,title,description?,severity?,priority?,owner?,assignee?,correlationId?,attributes?,dueAt?|dueInMinutes?}`:
`type` defaults to `ISSUE`, `title` is required (else 400), an unknown `type` is a 400.
`ObjectService.open` gained an overload carrying `priority`/`owner`/`assignee` (the prior 6-arg form
delegates to it, so the auto-promoting `AlertService` is unchanged). Lifecycle moves use the generic
`/objects/{id}/transition {action}` (`assign`/`start`/`resolve`/`close`).

### 5.3 SLA tracking

- **Deadline:** `dueAt` (epoch millis) stored as an *attribute* — set at creation from `dueAt` or
  relative `dueInMinutes`. An attribute, not a new column, so both `InMemoryObjectStore` and
  `DbObjectStore` round-trip it with **no migration**.
- **Sweep:** `ObjectService.sweepIssueSla(now)` breaches every ISSUE past its `dueAt` that is still
  being worked (not `RESOLVED`, not `CLOSED`) and not already breached; it stamps a `slaBreachedAt`
  marker (idempotent — never re-fires) and emits a new **`OBJECT_SLA_BREACH`** event (level `WARN`)
  onto `EventLog.global()`, so the breach surfaces in `/events` next to the issue's activity. `now`
  is injected so the schedule and tests evaluate against the same clock.
- **Schedule:** `SourceService.start()` arms the sweep on the existing `Scheduler.everySeconds`,
  cadence `-Dobjects.sla.sweep.seconds` (default `60`; `<=0` disables) — the same scheduler that runs
  the poll cycle and enrichment jobs.
- **Impact = affected pipelines:** carried lightly via the issue's `correlationId` (the pipeline /
  batch), as Phase 2 does, pending the first-class `OBJECT_LINK` graph in Phase 4.

### 5.4 Surface (`ControlApi`, CONTROL scope)

- `POST /objects` — create (above). The Phase-2 routes are unchanged and serve issues as-is:
  `GET /objects?type=ISSUE&status=…`, `GET /objects/{id}`, `POST /objects/{id}/transition`.
  (`/ack`, `/resolve` remain ALERT-shaped conveniences; issues use `/transition`.)
- New event type `OBJECT_SLA_BREACH`, queryable at `GET /events/search?type=OBJECT_SLA_BREACH`.

### 5.5 Acceptance (met)

- An operator creates an ISSUE (`POST /objects`) and walks it
  `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED`, each step an `OBJECT_ACTIVITY` event; an illegal
  move → 422, missing title / bad type → 400, auth scoped.
- An overdue, unresolved issue breaches exactly once per deadline (idempotent across sweeps), emitting
  `OBJECT_SLA_BREACH`; a future-due, resolved, or no-SLA issue does not breach. Tested in
  `WorkflowTest`, `ObjectServiceTest`, `ControlApiObjectsTest`.
- Additive-only: Phase-1/2 + `/alerts*` behaviour unchanged; no new dependency, no schema change.
- **Deferred to Phase 4** (per §2): the first-class `OBJECT_LINK` correlation graph, plus
  comments/attachments — Phase 3 links lightly via `correlationId`.

---

## 6. Phase 4 — Case Management (as built)

Shipped on `4.x` (v4.5.0) — the first phase to make **correlation first-class**. Adds the `CASE`
lifecycle and a new `OBJECT_LINK` graph (the piece Phases 2–3 deferred to here), both on the existing
ops engine. The bundled DuckDB serves the new link table, so **no new dependency** (`inspecto/pom.xml`
untouched); additive-only. Full reactor green: core 525 (+8) + agent 157 + hosted 4 = 686.

> **Scope of this slice:** the CASE lifecycle + the `OBJECT_LINK` correlation graph. **Comments,
> attachments, and authored RCA templates** (the rest of §2 Phase 4) are deferred to a follow-up —
> they reuse the same engine and add no new architecture.

### 6.1 CASE lifecycle (Workflow Engine)

`Workflow.defaultFor(CASE)` = `OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED` (actions
`investigate`/`escalate`/`resolve`/`close`, plus a direct `INVESTIGATING → RESOLVED` for "resolve
without escalating"); only `CLOSED` is terminal. Cases are operator-created via the Phase-3
`POST /objects` (`type=CASE`) and walk via `/objects/{id}/transition` — no new lifecycle code.

### 6.2 The OBJECT_LINK correlation graph (new package `com.gamma.ops.link`)

```
ObjectLink        record: from, fromType, to, toType, relationship, createdAt + of()/other()/toMap()
LinkRelationship  const : CONTAINS, ESCALATED_FROM, CAUSED_BY, RELATED_TO  (free-form, like EventType)
LinkStore         iface : add, incident(id), all(limit), close — APPEND-ONLY (no update/delete; the
                          twin of EventStore, not ObjectStore)
InMemoryLinkStore       : list-backed, newest-first; the lean default
DbLinkStore             : JDBC over the bundled DuckDB (or BYO Postgres); table inspecto_ops_links
```

A link is an immutable directed edge between two objects — `Case CONTAINS Issue`,
`Issue ESCALATED_FROM Alert`, `Alert CAUSED_BY Event` — exactly the requirement's
`{from, from_type, to, to_type, relationship}` model. Relationships normalise to upper-case.

### 6.3 Engine + wiring

- **`ObjectService`** gained a `LinkStore` (3-arg constructor; the 1-/2-arg forms default to
  `InMemoryLinkStore`, so every existing call site is unchanged) and three methods:
  `link(from,to,relationship,actor)` (both endpoints must exist → 404 otherwise; idempotent on a
  duplicate edge; emits a new `OBJECT_LINKED` event), `linksOf(id)` (incident links), and
  `graph(rootId,depth)` — a BFS subgraph `{root,depth,nodes,edges}` where each node is a light object
  summary, so the UI renders the graph with no extra lookups.
- **`EventType`** gained `OBJECT_LINKED`.
- **`SourceService`** builds a `LinkStore` on the same `-Dobjects.backend` toggle — a *separate* DuckDB
  file (`-Dobjects.links.db.url`, default `inspecto-ops-links.db`), because a file-based DuckDB holds a
  single-writer lock and the object store owns `inspecto-ops.db`; point both at one Postgres for a
  distributed deployment — passes it to `ObjectService`, and closes it in `close()`.

### 6.4 Surface (`ControlApi`, CONTROL scope)

- `POST /objects/{id}/links` — body `{to, relationship?, actor?}` (404 unknown endpoint, 400 missing `to`).
- `GET /objects/{id}/links` — links incident to the object.
- `GET /objects/{id}/graph[?depth=]` — correlation subgraph (default depth 2, capped at 5; 404 unknown root).
- New event type `OBJECT_LINKED`, queryable at `GET /events/search?type=OBJECT_LINKED`.

### 6.5 Acceptance (met)

- A CASE walks `OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED`; an Alert / Issue / Case can be
  correlated (`POST …/links`) and the neighbourhood + multi-hop subgraph read back (`…/links`,
  `…/graph`); each link is an `OBJECT_LINKED` event in `/events`.
- Idempotent links; unknown endpoints → 404; additive-only (Phases 1–3 + `/alerts*` unchanged); no new
  dependency. Tested in `WorkflowTest`, `LinkCoreTest`, `ObjectServiceTest`, `ControlApiObjectsTest`.
- **Deferred (a Phase-4 follow-up):** `OBJECT_COMMENT` / `OBJECT_ATTACHMENT` and authored RCA `.toon`
  templates — same engine, no new architecture.

---

## 7. Development guidelines (from the requirement, applied here)

- **Platform services first** — `EventStore`/`ObjectStore`/`Workflow` are SPIs, not per-product code.
- **Separate immutable facts from operational workflows** — Parquet events vs. table objects (§0).
- **Configuration over custom code** — rules, workflows, RCA templates are `.toon` (reuse `ConfigCodec`).
- **Correlation is first-class** — `correlationId` from Phase 1; `OBJECT_LINK` graph from Phase 4.
- **Phased adoption** — each phase ships independently; later phases never force a redesign of earlier.
- **Lean core** — intelligence (Phase 5) stays in the optional `inspecto-agent`; core is deterministic.
