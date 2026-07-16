# Studio — KPI / Report / Dashboard Builder — design (north star)

> Status: **design** (2026-06-28). UI-first, mock-backed, phased. Decisions below are **locked with the user**;
> open questions are flagged. This is the **visualization head on the rule-builder's Query Core** — one query
> model, two heads (rule *actions* vs *viz*). Grounded in the real, shipped UI substrate and the real engine
> seams (DuckDB-as-engine, `/components/{type}` persistence, `NodeKind.KPI` catalog).
>
> **Companion doc:** [`rule-builder-design.md`](../rule-builder-design.md) — the Query Core (projection + filter +
> SQL + live preview) this builds on, and its deferred **Phase 2 (aggregation)** which lands here.
>
> **SHIPPED substrate (reused, not rebuilt):** the offline **Query Core**
> (`inspecto-ui/src/app/inspecto/query/` — `QueryPanelComponent`, `compileSql`, `inferColumns`), **AlaSQL**
> offline execution (`inspecto/data-table/sql/sql-run.ts`), the theme-aware **Chart.js** host
> (`inspecto/components/chart.component.ts`), **ag-Grid** (consolidated in `inspecto/data-table`), **G6**, the
> **`/components/{type}`** CRUD pattern (`inspecto/api/components.service.ts`; mock-served offline now — real
> persistence needs the backend type enum widened, see §10), the design system + the env-gated
> mock-interceptor pattern (`connection-mock.interceptor.ts`).
> **NOT yet built:** the `dataset`/`chart`/`dashboard` artifacts, the `VizPlugin` registry, the Explore
> field-mapper, the dashboard grid, and real server-side query execution.

## 1. Vision

A **Studio**: one place to pick data source(s), shape a **dataset** with SQL, **visualize** the result, and
compose saved charts/KPIs into **dashboards** — *mostly configuration-driven*, with an **interface escape
hatch** so arbitrarily complex hand-coded viz fit the same contract.

Key insight (mirrors the rule-builder): this is **not a new analytics engine**. It is a **composition layer +
a plugin registry** over substrate we already own. DuckDB is already the execution engine; Chart.js / ag-Grid /
G6 are already hosted; the Query Core already compiles SQL and previews it offline. The new code is the four
*artifacts* (dataset / chart / dashboard / plugin), the **Explore** field-mapper, and the **dashboard grid**.

This is the natural home for the rule-builder's deferred **aggregation phase**: a Studio **dataset** is the
Query Core extended with measures/dimensions + result metadata, and a **chart** is a structured aggregate query
plus a viz mapping.

## 2. Locked decisions

1. **Home / name.** A new **"Studio"** nav group + `src/app/modules/admin/studio/` with lazy nested routes
   `/studio/datasets`, `/studio/charts`, `/studio/dashboards`. The existing **operational `/dashboard`** (KPI
   tiles + trend charts backed by metrics) is a *different* feature and stays untouched — no name/path clash.
2. **Dataset = a data-source abstraction**, not merely "a saved query." Kinds: **physical** (table / parquet /
   store), **virtual** (a SQL/`QueryModel` view, not persisted), **materialized** (a virtual dataset whose
   result is persisted/cached). All carry typed **column metadata + roles + formats + named metrics**. Stored
   in the **component library** as a new `dataset` component type.
3. **KPI = a first-class viz plugin with three in-place render modes** (mini → standard → max). One tile that
   *maximizes in place* (chosen on UX grounds over a separate KPI builder or a raw big-number+line combo).
   Aligns with the existing server `NodeKind.KPI` catalog.
4. **Viz engine = Chart.js-first + a `VizPlugin` registry seam.** P1 set (~6–8): kpi/big-number, table
   (ag-Grid), line, bar (grouped/stacked), area, pie/donut, scatter/bubble. Network/sankey via the existing
   G6 host later. ECharts / geospatial / heatmap-treemap are **deferred behind the plugin seam** (a real new
   dependency = a future decision, not now).
5. **Reuse over fork.** Dataset embeds the Query Core's `QueryModel`; chart filters reuse the Query Core
   `ConditionGroup`; persistence reuses `/components`; preview reuses AlaSQL + the chart/grid hosts. Field
   mapping is **config selects-per-channel first**, drag-drop shelves as later polish.

## 3. The four-object model (mapped to what we already own)

| Superset object | What it is | Studio equivalent | Reuse / gap |
|---|---|---|---|
| Database | a connection | `connections` feature | **done** (`connections.service.ts`) |
| Dataset | physical table or virtual (SQL) dataset + column metadata + metrics | new `dataset` component | Query Core + `inferColumns` + `/components`; **gap:** roles/formats/metrics, kind taxonomy |
| SQL Lab | ad-hoc SQL IDE, save result as dataset | `data-table` **pro** (CodeMirror + AlaSQL + Run + history) | **done**; **gap:** "save query → dataset" |
| Chart (Slice) | viz_type + dataset + control config | new `chart` component + `<inspecto-chart>` / ag-Grid / G6 | hosts exist; **gap:** the Explore field-mapper + `VizPlugin` registry |
| Dashboard | grid layout of charts + cross-filters | new `dashboard` component | **gap:** CDK drag-drop grid + dashboard filters |
| Plugin (@superset-ui) | per-viz controlPanel + buildQuery + transformProps + renderer | `VizPlugin` registry in **new `inspecto/viz/` lib** | **gap:** the plugin contract (the heart of "config + interface") |

The whole thing **authors fully offline** (AlaSQL) and **executes on DuckDB later** — the same
offline-now / server-later seam the data-table already uses.

## 4. Dataset — model + result metadata

A `dataset` is a saved Query-Core artifact (when virtual) or a source reference (when physical), plus typed
result metadata that makes the Explore field-mapper smart.

```ts
type DatasetKind = 'physical' | 'virtual' | 'materialized';
type ColumnRole  = 'dimension' | 'metric' | 'temporal';
type ColumnFormat =
  | { kind:'number';   decimals?:number; thousands?:boolean }
  | { kind:'percent';  decimals?:number }
  | { kind:'currency'; code?:string; decimals?:number }
  | { kind:'date';     pattern?:string }
  | { kind:'string' };

interface DatasetColumn { name; type:ColumnType; role:ColumnRole; label?; format?:ColumnFormat; hidden?:boolean }
interface NamedMetric   { id; label; expression:string; format?:ColumnFormat }   // e.g. "SUM(duration_s)", "COUNT(*)"

interface Dataset {
  id; name; kind:DatasetKind;
  sourceName: string;            // logical FROM name (AlaSQL table + QueryModel source)
  query?:      QueryModel;       // virtual only — the Query Core model (projection + filter + agg)
  physicalRef?: string;          // physical/materialized — catalog ref / parquet path / cache id
  columns:  DatasetColumn[];     // typed cols + roles + formats (seeded by inferColumns + inferRoles)
  metrics:  NamedMetric[];       // reusable measures, drive chart "y" / KPI value
  viz?: { defaultType?: string; defaultMappings?: Record<string,string> };  // Show-Me hint
}
```

- **Metadata is mostly free:** `inferColumns()` / `inferType()` (`inspecto/query/query-columns.ts`) already give
  number/string/date/bool. A light **`inferRoles`** step (date→temporal, numeric-non-id→metric, else dimension)
  seeds roles; the user can re-tag. Roles gate the field-mapper (only temporal cols for a time axis, only
  metrics for y, …).
- **`ColumnMeta` has no role today** — roles/formats/metrics are **Studio-owned** (`DatasetColumn`), keyed by
  column name. Do **not** widen the shared `ColumnMeta`.
- **Alignment with `NodeKind.KPI`:** the engine already exposes a *declarative* KPI catalog
  (`GET /catalog/kpis`, `CatalogRoutes.catalogKpis()` → `{id,name,definition,grain,joinKeys,inputs}`). A Studio
  KPI/dataset should be **importable from / publishable to** that catalog later (P4), not a parallel fork.

## 5. The `VizPlugin` contract — "config-based, but interfaces for the complex"

A `VizPlugin` is a registry entry. **80% of charts are pure config** (controls + a Chart.js builder); the
**escape hatch** is a hand-coded Angular component behind the same contract.

```ts
type ChannelRole  = 'x'|'y'|'series'|'size'|'color'|'value'|'rows'|'columns';
type VizRenderKind = 'chartjs'|'aggrid'|'g6'|'component';

interface ControlSpec {                       // DECLARATIVE field-mapper input
  key; label; channel:ChannelRole;
  input:'column'|'columns'|'metric'|'metrics'|'select'|'number'|'toggle';
  acceptRoles?:ColumnRole[];                  // gates which dataset columns appear
  required?:boolean; options?:{value;label}[]; default?:unknown;
}
interface VizMeta { type; label; icon; category; description;
  fit:{ minDim?; maxDim?; minMetric?; maxMetric?; temporal?:boolean } }   // drives Show-Me

interface VizPlugin<Props=unknown> {
  meta: VizMeta;
  controls: ControlSpec[];
  buildQuery(controls, dataset): QuerySpec;            // controls → structured query
  transformProps(rows, controls, dataset): Props;      // result rows → renderer shape
  render:                                              // THE ESCAPE HATCH (a discriminated union)
    | { kind:'chartjs';  chartType: ChartType }        // 80%: pure config → <inspecto-chart>
    | { kind:'aggrid' }                                // table / pivot → <inspecto-data-table>
    | { kind:'g6' }                                    // network / sankey / tree (later)
    | { kind:'component'; component: Type<unknown> };  // hand-coded complex viz (e.g. KPI)
}
```

- **Config path** (line/bar/area/pie/scatter): the Explore form is **auto-generated** from `controls`; the
  renderer is a one-line `chartType`. Adding "stacked area" ≈ one registry entry, zero new UI code.
- **Interface path** (`kind:'component'`): a developer hand-builds the viz (custom canvas, multi-layer,
  interactive) behind the same `VizPlugin`. The Explore/dashboard don't care which path a plugin took.
- **Registry home:** a **new shared lib `inspecto/viz/`** (sibling of `query/`, `data-table/`, `theme/`) — it's
  cross-feature reusable and must never be a feature→feature import.

## 6. `QuerySpec` — one spec, two executors (offline now, DuckDB later)

Charts emit a **structured `QuerySpec`**, not raw SQL — so we never paint ourselves into a dialect corner and
can push aggregation to the warehouse later.

```ts
interface QuerySpec {
  datasetId: string;
  groupBy:  string[];                                   // dimensions → GROUP BY
  metrics:  { id; expression:string; label? }[];        // measures → SELECT agg AS id
  filters:  ConditionGroup;                             // REUSE the Query Core nested AND/OR
  timeGrain?: { column:string; grain:'day'|'week'|'month'|'quarter'|'year' };
  orderBy?: { expr:string; dir:'asc'|'desc' }[];
  limit?:   number;
}
```

- **`compileSpec(spec, dataset, {dialect})`** →
  `SELECT <groupBy>, <metric.expr AS id> FROM <sourceName> [WHERE …] [GROUP BY …] [ORDER BY …] [LIMIT …]`.
  Reuse the WHERE-builder + identifier quoting from `query-sql.ts` (export `quoteIdent` / a `compileWhere`
  helper). **`compileSql` today is projection+WHERE only** — the agg/group/order/limit compiler is net-new.
- **Offline (now):** `runSpec` → `runSql(sql, dataset.sourceName, rows)` (`data-table/sql/sql-run.ts`).
  Constraints to design around: **SELECT-only, single-table** (no offline joins), DuckDB→AlaSQL identifier
  rewrite. **AlaSQL has no `DATE_TRUNC`** → do time-grain bucketing in `transformProps` offline; keep the
  DuckDB `DATE_TRUNC(...)` form behind the dialect flag.
- **Server (later, P4):** a `QuerySpec → DuckDB` endpoint executes over the real store. The **QuerySpec→SQL
  boundary is the single swap seam** — nothing above it changes.

## 7. KPI — the three-tier plugin (the user's vision, made concrete)

KPI = a **value in a moment, for a scenario (conditions), over one or more datasets**, with one tile that
maximizes through three modes. Implemented as a single `VizPlugin` (`render.kind:'component'`,
`component: KpiComponent`) with an internal `mode` signal — **no route change on maximize**.

| Mode | Shows | Source |
|---|---|---|
| **mini** | big numeric value · the period (e.g. "today") · %Δ vs previous period · %Δ vs same day last week · a name · a maximize affordance | one metric, current + 2 comparison windows |
| **standard** (maximize ×1) | a **trend chart** over the window (line/area), axis + units + name + description; nice-to-have **time-range slider** (share-price style) | metric bucketed by temporal grain |
| **max** (maximize ×2) | **superimposed indices** — multiple metrics/datasets on the same time frame (normalized/indexed overlay) | N metrics over one temporal axis |

```ts
interface KpiConfig {
  datasetId; valueMetric: string;                       // the headline measure
  temporal?: string;                                    // trend axis (standard/max)
  compare?: { label:string; offset:'prevPeriod'|'prevWeek'|'prevYear' }[];   // deviation chips (mini)
  overlays?: { datasetId; metric; label }[];            // max mode superimposed series
  format?: ColumnFormat; name; description?;
}
```

- **Deviation chips** use `status-badge` semantics + `CHART_SERIES` colors (no hardcoded hex). Up/down/flat with
  accessible text, not color alone.
- `buildQuery` for mini = single metric + comparison windows; for standard/max = metric(s) bucketed by
  `timeGrain` over the temporal column.

## 8. "Show Me" — Tableau-style intelligent chart options

The Explore picker is **driven by the result-set shape** (counts of dimensions / metrics / temporal columns)
and offers ranked viz with **auto channel assignment** — the user's "intelligent options" ask.

- **Recommend:** `recommend({dims, metrics, temporal})` ranks plugins by `meta.fit`. Examples:
  `0 dim + 1 metric` → KPI / big-number / table; `temporal + ≥1 metric` → line / area; `1 dim + 1 metric` →
  bar / pie; `2 metrics` → scatter; `≥3 metrics` → bubble (height + size + color); `≥2 dim + 1 metric` →
  grouped bar / table / heatmap (later).
- **Channel mapping (the measure-stacking idea):** `autoAssignChannels` seeds defaults — **1st measure →
  height/y**, **2nd measure → size (bubble)**, **3rd measure/dimension → color**, temporal → x, 2nd dimension →
  series. The user can override any channel in the auto-generated control form.

## 9. Dashboards (P2) — multiple, user-placed in the menu tree

Each dashboard is its own `dashboard` **component** (Operation · Case · RA · Fraud · …) — created, listed, and
edited like datasets/charts. A dashboard = a CDK drag-drop **grid of saved chart/KPI references** + optional
**dashboard-level filters** that inject into each child's `QuerySpec` (cross-filter). KPIs sit as tiles that
maximize in place. Parameterized dashboards reuse the rule `:param` machinery (P3).

```ts
interface Dashboard {
  id; name;
  layout: { chartId:string; x; y; w; h }[];        // grid placement of saved charts/KPIs
  filters?: DashboardFilter[];
  params?: Param[];
  nav?: { parentId:string; title?:string; icon?:string; order?:number };   // where it lives in the menu
}
```

### User-placed menu nodes (verified feasible)

The sidebar is a **recursive JSON tree** of `GammaNavigationItem`
(`@gamma/components/navigation/navigation.types.ts`: `{id, title, type:'basic'|'collapsable'|'group'|…, link,
icon, children[], meta}`), exposed as `Navigation` through a **reactive** `NavigationService`
(`core/navigation/navigation.service.ts` — a `ReplaySubject<Navigation>` behind `navigation$`; currently
mock-served by `NavigationMockApi` from `mock-api/common/navigation/data.ts`). So a user can create a dashboard
and drop it **anywhere** in the menu — **yes, fully supported** — via two clean mechanisms, no per-dashboard
codegen:

- **Routing — one parameterized route.** A single lazy route `/studio/dashboards/:id` renders any dashboard by
  id (loads the `dashboard` component, renders the grid). Every menu node just points its `link` at
  `/studio/dashboards/<id>`. **No runtime route registration** needed (Angular's `router.resetConfig` exists if
  truly custom paths like `/fraud-dashboard` are ever wanted — not required here).
- **Menu — merge at runtime, live.** A `studio-nav` step (extend `NavigationService` with a merge, or a thin
  wrapper) takes the base navigation + the saved dashboards list and **injects each dashboard as a
  `type:'basic'` child under its `nav.parentId`**, then `.next()`s the merged tree → the sidebar re-renders
  **without a reload** (the `ReplaySubject` is reactive). Delete/rename simply re-merges.

**Placement UX:** the dashboard editor's "Save & place in menu" step shows a **parent picker** over the current
nav tree (any existing group — Operations, Acquisition, … — or a default auto-created **"Dashboards"** group),
plus title/icon/order. `nav.parentId` references an existing node `id`. The client-side merge is
backend-agnostic, so it works offline against the mock today and against a real nav endpoint later.

**Placement scope (locked): shared within the space.** `nav` is stored on the `dashboard` component, so
everyone in that space sees the dashboard in the spot it was placed. A **personal** per-user menu-layout overlay
is a deferred later option (not v1).

## 10. Persistence — reuse `/components/{type}`

All three artifacts persist through the existing CRUD (`components.service.ts`): `GET/POST/PUT/DELETE
/components/{type}/{id}`. **Offline (P0/P1) the studio-mock serves these — no backend needed.** For *real*
persistence later: `ComponentRoutes` accepts any `{type}` path segment, but the **storage layer enforces a
closed type set** (`ComponentStore.WRITABLE_TYPES` / `ComponentRegistry.TYPE_BY_DIR` = grammar/schema/transform/
sink; unknown → `400`). So `dataset`/`chart`/`dashboard` require widening those two constants + a registry
directory — a small, deliberate backend change, **not** zero. Mirror `rule/rules.service.ts` for each
(`datasets.service.ts`, `charts.service.ts`, `dashboards.service.ts`).

- **UI `ComponentType` is a closed union** typed through every method — widen it to add the three types (leave
  the `COMPONENT_TYPES` *palette* untouched so they don't surface in the components feature, exactly like `rule`).
- **No `/components/*` mock exists today** (rule writes hit the real backend). Studio ships the **first** one:
  a `studio-mock.interceptor.ts` (env-gated on `environment.mockStudio`) serving in-memory CRUD for the three
  types, **registered before `spaceInterceptor`** in `app.config.ts`, seeded with 1–2 demo datasets.

## 11. Convergence with the rule-builder

One query model, two heads:

```
                         ┌─ rule head:  filter/route, alert, issue/case, enrichment (rule-builder)
Query Core (QueryModel) ─┤
                         └─ viz head:   dataset → chart → dashboard / KPI            (this doc)
```

A Studio **dataset** is the Query Core + aggregation (rule Phase 2) + result metadata. A **chart** is a
`QuerySpec` (aggregate query) + a viz mapping. The two builders share `QueryModel`, `ConditionGroup`,
`compileSql`/`compileSpec`, `inferColumns`, the AlaSQL preview, and `/components` persistence.

## 12. Phased plan (each phase has a verifiable DoD)

- **P0 — Dataset.** "Save as dataset" from the embedded Query Core / data-table pro; kind taxonomy
  (physical/virtual/materialized); role/format tagging; dataset list + picker; `datasets.service` + studio-mock.
  *DoD: list/create/edit a virtual dataset offline; roles/formats round-trip through `/components/dataset`;
  lint:tokens + a11y + build + test:ci green; verified in preview.*
- **P1 — Explore / Chart builder + `VizPlugin` registry.** `inspecto/viz/` lib (types, registry,
  `compileSpec`/`runSpec`, Show-Me, `viz-render` dispatcher) + ~7 plugins (kpi 3-mode, table, line, bar, area,
  pie, scatter); the auto-generated Explore field-mapper; live preview; save chart.
  *DoD: dataset → Show-Me → field-mapper → live preview on every change → save → reload reproduces; KPI toggles
  3 modes in place; line/area/scatter themed (light+dark); gates green; verified in preview.*
- **P2 — Dashboards.** Multiple `dashboard` components (Operation/Case/RA/Fraud/…); CDK drag-drop grid of saved
  charts/KPIs; dashboard filters → cross-filter; single `/studio/dashboards/:id` route; **user-placed menu
  nodes** via a live `NavigationService` merge.
- **P3 — Depth.** calculated columns / custom metrics UI; more viz (gauge, funnel, pivot, G6 network);
  parameterized dashboards (rule `:params`); export (PNG / CSV).
- **P4 — Backend execution + scheduling.** `QuerySpec → DuckDB` endpoint (build on `SqlSandbox` or a new store
  + a `RouteModule`); materialized datasets via `EnrichmentConfig.transformSql`; scheduled delivery via
  `JobType.REPORT` / a new `Job`; align executable KPIs with `NodeKind.KPI`.

## 13. Backend seams & nuances (grounded, verified)

- **Execution:** closest "run SELECT…GROUP BY, return rows" seam is `ParquetEventStore.queryParquet()` (typed,
  via `EventRoutes.eventQuery`); `SqlSandbox` is a throwaway-DuckDB for component test-previews — the cleanest
  target for a future ad-hoc `/datasets/query`. No free-form aggregate endpoint exists yet.
- **Materialization:** `EnrichmentConfig.transformSql` accepts verbatim aggregate SQL → the path to persist a
  materialized dataset.
- **KPI catalog:** `NodeKind.KPI` + `GET /catalog/kpis` exist as *declarative* metadata (no compute engine
  behind them). Make Studio KPIs interoperate with this rather than fork it.
- **Reports:** `ReportService` (UI `reports.service.ts` + Java `ReportService`) are **fixed ops rollups**, not a
  user report builder — orthogonal; no collision.
- **Scheduling:** `JobType.REPORT` + `ReportJob` + `JobService` exist; a `DASHBOARD_SNAPSHOT`/report `Job` hooks
  in with no scheduler plumbing change. No delivery channel (email/webhook) exists yet.
- **Routing:** JDK `HttpServer` + `RouteModule` pattern; new `/datasets|/charts|/dashboards` routes plug into
  `ControlApi.registerRoutes()`'s `List.of(...)` — only when P4 needs server execution.

## 14. Risks / open questions

- **Chart.js ceiling:** great for standard charts, weak for heatmap/treemap/large-scale. The `VizPlugin`
  interface lets ECharts/G6 drop in later — but ECharts is a real new dep (a decision, not now).
- **Offline joins:** AlaSQL `runSql` is single-table; multi-source datasets need either a pre-join virtual
  dataset (DuckDB, P4) or client-side join helpers (limited). v1 datasets resolve to one source.
- **Time-grain parity:** offline bucketing (in `transformProps`) vs DuckDB `DATE_TRUNC` must produce matching
  buckets — test both against a hand-checked case.
- **Dataset ↔ rule overlap:** dataset reuses the Query Core but is a *sibling* artifact to `rule` (not the same
  component type) — keep the line clear so neither carries the other's concerns.
- **Governance:** per-space scoping (assume yes, like other components); versioning/audit when a saved
  chart/dataset is edited; who can publish a KPI to the catalog.
- **a11y for canvas:** every chart needs a text alternative + a non-color encoding for deviation/series
  (WCAG 2.2 AA + axe gate); KPI deviation must not rely on color alone.
