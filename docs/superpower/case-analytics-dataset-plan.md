# Case Analytics → Studio Dataset — implementation plan

**Status: DRAFT 2026-07-24 (design-first; planning session only — no code yet).**
Backlog origin: §3 *Incidents / cases* — "Studio-dataset binding of case analytics (2026-07-24 TRIAGED,
deferred — not a thin add … Design decision first)". This plan makes those decisions so a future shift
can build it in one focused session.

## 1. Goal

Make operational-object analytics (Alerts / Incidents / Cases / Tasks) **bindable as a Studio/BI
Dataset** — so widgets, dashboards, queries, and Alert Rules can chart per-status / per-category /
per-priority counts and their trends — without changing the analytics computation or the mail UI.

Today `ObjectService.analytics(type)` returns a nested JSON-ready rollup **map** rendered directly by
the UI (`GET /objects/analytics`), and `OperationalObject`s live only in the live JDBC table
`inspecto_ops_objects` (DuckDB `inspecto-ops.db` by default) — there is **no row surface** a `dataset`
component's `physicalRef`/`view` could bind to. The method's own javadoc anticipates exactly this
follow-on ("a later Studio-dataset binding can read the same surface",
`inspecto-engine/…/ops/ObjectService.java:254-260`).

## 2. Design — a `objects.analytics` built-in Job Type materializing tall Parquet samples

Mirror the shipped `storage_report` idiom end-to-end: periodic job → flatten to rows → one Parquet
file per run under the space data dir → result-stamp a `dataset` component. Every link in the chain
has a live precedent; nothing is greenfield.

```
JobService.registerBuiltins()                      (register, like recon.run / caserule.evaluate)
  └─ ObjectsAnalyticsJob.run(ctx)
       ├─ objects.get().analytics(type)  ×4 types  (in-process; NEVER a 2nd conn to inspecto-ops.db)
       ├─ flatten nested maps → tall rows          (like storageCatalog's row-per-axis fold)
       ├─ scratch DuckDB: CREATE TABLE + INSERT + COPY … TO
       │    '<dataDir>/ops_analytics/analytics_<epochMs>_out.parquet' (FORMAT PARQUET)
       ├─ optional retention: delete sample files older than retention_days
       └─ ComponentStore.write("dataset", "ops_analytics",
            {name, physicalRef: "ops_analytics", …}, false)   (result-stamp, no version churn)
```

Read path needs **zero new code**: `DatasetRelation.relationSql` already resolves
`physicalRef → read_parquet('<dataRoot>/ops_analytics/**/*.parquet')`
(`inspecto-engine/…/query/DatasetRelation.java:47-80`), which is what `/db/query`, `/bi/datasets`,
widgets, reports, and Alert Rules all consume. The dataset appears in Studio pickers automatically.

### 2.1 Row schema (the flattening decision)

One **tall** table; heterogeneous open-ended breakdown keys (status/category/priority are not fixed
enums) make wide columns unstable. `value` is DOUBLE because not every measure is a count:

| column | type | notes |
|---|---|---|
| `sampled_at` | TIMESTAMP | run instant — the trend axis |
| `object_type` | VARCHAR | `ALERT` \| `INCIDENT` \| `CASE` \| `TASK` |
| `axis` | VARCHAR | `status` \| `category` \| `priority` \| `scalar` \| `cycle_time` \| `impact` |
| `key` | VARCHAR | breakdown key; for `scalar`: `total`/`backlog`; for `cycle_time`: `count`/`avg_ms`; for `impact`: `impact_amount`/`records_affected` |
| `value` | DOUBLE | the measure |

This mirrors `MaintenanceJob.storageCatalog`'s row-per-axis fold (`MaintenanceJob.java:349-395`) and
gives BI both shapes cheaply: latest snapshot = `WHERE sampled_at = (SELECT max(sampled_at) …)`;
trend = group by time bucket. *(Vocabulary: these are __Measures__ in widget config, per GLOSSARY.)*

### 2.2 Write style — append samples, not full-refresh swap

**Append** (`storage_report` style: a new timestamped file per run, glob-read) rather than
`MaterializeTask`'s stage/atomic-swap full refresh — because the whole value over the live
`/objects/analytics` endpoint is the **time dimension** (backlog/aging trends). Current-state-only
needs are served by the max-`sampled_at` filter. Retention keeps the glob bounded (§2.4).

### 2.3 Job Type registration & params

- **Job Type id `objects.analytics`** (dotted, matching `recon.run` / `caserule.evaluate`; the
  `/objects` route family names the domain). Registered in `JobService.registerBuiltins()`
  (`JobService.java:226-299`), resolving `ObjectService` lazily via the existing post-construction
  `Supplier<ObjectService>` (`JobService.objects(…)`, `JobService.java:386-388`) — exactly the
  `ReconRunJob` / `CaseRuleEvalJob` constructor pattern. Requires the Object Engine; fail closed
  (REJECTED) when absent, like `caserule.evaluate`.
- **Params** (`ParameterDecl`): `retention_days` (INT, optional, 0 = keep forever — mirrors
  `notification_prune`/`ledger_prune` naming); `types` (STRING, optional CSV filter, default all 4).
- **Emits** `objects.analytics.completed` (row count + duration in payload), WARNING on write failure
  — standard signal discipline.
- **Cadence is operator-authored, not baked in** — this dissolves the backlog's "undecided sampling
  cadence" product question. Like `recon.run`/`caserule.evaluate`, the built-in registers the *type*;
  a space schedules it by authoring a `*_job.toon` with its own `cron:` (generic `JobConfig.cron`).
  Seed `spaces/demo` with an example (`cron: "0 * * * *"`, hourly) per the editable-sample-catalog
  convention.

### 2.4 Retention

Inline in the job run (simplest; no new task type): after a successful write, delete
`analytics_*_out.parquet` files whose embedded epoch is older than `retention_days`. Skip when 0.
A run is one small file (≈ dozens of rows), so even daily-for-a-year is trivial — retention is
hygiene, not necessity.

### 2.5 Dataset component content

`ComponentStore.write("dataset", "ops_analytics", content, false)` with
`{name: "ops_analytics", physicalRef: "ops_analytics", description: …}` — the result-stamp
(no-version-churn) write `storageCatalog` uses (`MaintenanceJob.java:390-395`). Note there is **no
`ConfigSpecs.dataset()`** — dataset components are written as raw maps; that is the existing
convention, not a gap to fix here.

## 3. What this does NOT do (non-goals)

- No Parquet/view surface for the **raw** `inspecto_ops_objects` rows (that's a different, bigger
  lift — row-level object export has PII/ACL implications; analytics rows are aggregates).
- No second connection to `inspecto-ops.db` (single-writer; in-process `ObjectService` only).
- No change to `GET /objects/analytics` or the mail UI.
- No UI work at all — Studio dataset pickers, `/db/query`, widget binding all light up for free.

## 4. Build order & verify criteria (one session)

1. `ObjectsAnalyticsJob` (flatten + scratch-DuckDB COPY + retention + dataset stamp) →
   *verify:* unit test on the pure flatten (nested map fixture → expected tall rows).
2. Register in `registerBuiltins()` + descriptor →
   *verify:* `JobServiceTest`-style test: seed `ObjectService` (in-memory store) with a few objects,
   `submitAdhocRun`, assert Parquet exists, rows match, dataset component written; re-run appends a
   second file; `retention_days=0` keeps both.
3. Read-back through the real seam →
   *verify:* `DatasetRelation.relationSql("ops_analytics"…)` + a scratch conn returns the rows
   (mirrors existing dataset read-path tests).
4. Demo seed job in `spaces/demo` + docs →
   *verify:* GAUNTLET (`mvn -o clean test` full reactor) green; BACKLOG row struck; as-built distilled
   into `okf/backend/control-plane/jobs.md` and this plan archived per doc lifecycle.

## 5. Open decisions (resolved here unless flagged)

| # | Decision | Resolution |
|---|---|---|
| D1 | Row shape | Tall `(sampled_at, object_type, axis, key, value)` — §2.1 |
| D2 | Append vs full-refresh | Append + retention — §2.2 |
| D3 | Sampling cadence | Operator-authored cron per space; demo seed hourly — §2.3 (dissolves the product question) |
| D4 | Dataset/type naming | dataset `ops_analytics`, Job Type `objects.analytics` — covers all four object types, not just Cases; **confirm against GLOSSARY at build time** (avoid coining a new concept name) |
| D5 | Which types | All four, filterable via `types` param — §2.3 |
