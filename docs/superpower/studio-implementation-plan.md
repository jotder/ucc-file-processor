# Studio — KPI / Report / Dashboard Builder (north-star spec + P0–P1 build)

## Context

We want a **Superset-lite authoring surface** ("Studio") in inspecto-ui where a user picks data source(s),
shapes a dataset with SQL, visualizes the result, and composes saved charts/KPIs into dashboards — mostly
configuration-driven, with an interface escape hatch for hand-coded complex viz.

**Why now / why feasible:** the expensive substrate already exists and was verified in this session — the
offline **Query Core** (`inspecto/query`: `QueryModel`, `compileSql`, `inferColumns`), **AlaSQL** offline
execution (`data-table/sql/sql-run.ts`), a theme-aware **Chart.js** host (`inspecto/components/chart.component.ts`),
**ag-Grid** (consolidated in `data-table`), **G6**, the **`/components/{type}`** persistence pattern
(mock-served offline now; real persistence needs the closed backend type enum widened — see the design doc §10),
the design system + mock-interceptor
pattern. The new code is mostly **composition + a plugin registry**, and this is the natural home for the
rule-builder's deferred **Phase 2 (aggregation)**.

**Two facts that shaped the design (verified):**
- An **operational `/dashboard`** already exists (KPI tiles + trend charts backed by metrics). The new builder
  must NOT reuse that name/path → it lives under a new **"Studio"** group with nested routes.
- **`NodeKind.KPI` + `GET /catalog/kpis`** already exist server-side as a *declarative* KPI catalog
  (`CatalogRoutes.catalogKpis()`). Studio's KPI model should align with it (read/seed from it later), not fork it.

**Intended outcome:** (1) a durable design-of-record doc, and (2) a shippable, mock-first P0→P1 that proves the
dataset→explore→chart→save loop end-to-end offline.

## Locked decisions (confirmed with user)

1. **Home/name:** new **"Studio"** nav group + `src/app/modules/admin/studio/` with lazy nested routes
   `/studio/datasets`, `/studio/charts`, `/studio/dashboards`. Existing ops `/dashboard` untouched.
2. **Dataset = a data-source abstraction**, not merely "a saved query": kinds `physical` (table/parquet),
   `virtual` (SQL/QueryModel view), `materialized` (persisted result). Stored in the component library as a new
   `dataset` component type (`/components/dataset`). Virtual datasets embed the **Query Core `QueryModel`**;
   all carry column **roles** (dimension/metric/temporal) + formats + named metrics + viz metadata.
3. **KPI = a first-class viz plugin with 3 in-place render modes** (mini value+deviations → standard trend chart
   → max superimposed indices). One tile that maximizes in place (chosen on UX grounds over a separate builder
   or raw big_number+line). Aligns with server `NodeKind.KPI`.
4. **Viz engine = Chart.js-first + a `VizPlugin` registry seam.** P1 set ~6–8: kpi/big_number, table (ag-Grid),
   line, bar (grouped/stacked), area, pie/donut, scatter/bubble. Network/sankey via existing G6 later;
   ECharts/geo/heatmap deferred behind the plugin seam (no new dep now).
5. **Convergence:** Studio is the **visualization head on the rule-builder's Query Core** — one query model,
   two heads (rule actions vs viz). Mock-first; real DuckDB execution deferred to a later phase.

## Target architecture (the four-object model, mapped to what we own)

| Superset object | Studio equivalent | Reuse |
|---|---|---|
| Database | connections feature | done (`connections.service.ts`) |
| Dataset | `dataset` component (physical/virtual/materialized) | Query Core + `inferColumns` + `/components` |
| SQL Lab | data-table `pro` (CodeMirror + AlaSQL) | `data-table.component.ts` |
| Chart (Slice) | `chart` component + `VizPlugin` | `chart.component.ts`, ag-Grid, G6 |
| Dashboard | `dashboard` component (CDK grid) | CDK drag-drop |
| Plugin (@superset-ui) | `VizPlugin` registry in **new `inspecto/viz/`** lib | net-new seam |

**Execution seam:** charts emit a structured **`QuerySpec`** (groupBy/metrics/filters/timeGrain/orderBy/limit),
compiled to **AlaSQL now** (`runSql`) and to **DuckDB later** via a backend endpoint. The QuerySpec→SQL
boundary is the single swap seam. Real-data backend candidates (verified): `SqlSandbox` (scratch DuckDB),
`EnrichmentConfig.transformSql` (materialization), `JobType.REPORT`/`ReportJob` (scheduling) — all later phases.

---

## Deliverable 1 — `docs/superpower/report-builder-design.md` (north-star) ✅ done

Author the design-of-record (OKF-aligned, mirroring `docs/rule-builder-design.md` structure + status banner).
Sections: Vision · Locked decisions · Four-object model & reuse table · Dataset model (kinds + roles + metrics) ·
`VizPlugin` contract (config path vs component escape hatch) · `QuerySpec` + offline/DuckDB seam ·
KPI 3-tier spec · "Show Me" recommender · Tableau-style channel mapping (measure→height/bar, 2nd→bubble size,
3rd→color) · Convergence with rule-builder Query Core · Alignment with server `NodeKind.KPI` · Phasing P0–P4 ·
Risks/open questions. Add a pointer in `docs/INDEX.md`. **DoD:** doc committed, cross-linked to
`rule-builder-design.md`, reviewed.

## Deliverable 2 — P0: Dataset

**New files** under `src/app/modules/admin/studio/`:
- `studio.routes.ts` — `export default` Routes: redirect → datasets + 3 nested `loadChildren` (so P1/P2 never
  re-touch `app.routes.ts`).
- `datasets/datasets.routes.ts`, `datasets/datasets.component.ts`(+`.html`) — list page (mirror
  `ConnectionsComponent`: signals, `<inspecto-empty-state>`, kind chip via `<inspecto-status-badge>`,
  `InspectoConfirmService.confirmDestructive`).
- `datasets/dataset-editor.component.ts`(+`.html`) — virtual kind embeds `QueryPanelComponent` /
  `<inspecto-data-table tier="pro">`; physical/materialized = ref form; hosts the role/format UI + "Save as dataset".
- `datasets/dataset-columns.component.ts`(+`.html`) — presentational role/format tagger, seeded by `inferRoles`.
- `datasets/dataset-picker.component.ts` — dialog (P1 chart builder consumes it).
- `datasets/dataset-types.ts` — `Dataset` model + `buildDataset()` + `inferRoles()` (mirror `rule-types.ts`).
- `datasets/datasets.service.ts` — mirror `inspecto/rule/rules.service.ts` over `'dataset'`.
- `inspecto/api/studio-mock.interceptor.ts` — env-gated (`environment.mockStudio`) in-memory CRUD for
  dataset/chart/dashboard; regex tolerant of the space-rewrite; **registered before `spaceInterceptor`**; seed
  1–2 demo datasets. (First `/components/*` mock — none exists today.)
- specs alongside each (vitest + `expectNoA11yViolations`).

**`Dataset` model (sketch):** `kind: physical|virtual|materialized`; `sourceName`; `query?: QueryModel` (virtual);
`physicalRef?` (catalog ref / parquet path / cache id); `columns: DatasetColumn[]` (`{name,type,role,label?,format?,hidden?}`);
`metrics: NamedMetric[]` (`{id,label,expression,format?}`); `viz?: {defaultType?, defaultMappings?}`.
`inferRoles`: date→temporal, numeric-non-id→metric, else dimension.

**Existing files changed:** `app/app.routes.ts` (+`studio` route) · `app/app.config.ts` (register
`studioMockInterceptor` first) · `mock-api/common/navigation/data.ts` (+`studio-group`) · all `environment*.ts`
(`mockStudio: true`) · `inspecto/api/components.service.ts` (widen `ComponentType` union to add
`dataset`/`chart`/`dashboard`, leave `COMPONENT_TYPES` palette unchanged — exactly like `rule`) ·
`inspecto/api/index.ts` (export interceptor).

**Entry point:** Studio editor embeds the Query Core and owns its own "Save as dataset" dialog (mirror
`RuleSaveDialog`) → no edit to the shared `data-table.component.ts` required.

**P0 DoD:** `/studio/datasets` lists seeded + saved datasets offline; create/edit a virtual dataset via the
embedded Query Core; roles/formats round-trip through `/components/dataset`. `lint:tokens` + a11y specs +
`npm run build` + `test:ci` all green; verified in preview.

## Deliverable 3 — P1: Explore/Chart builder + `VizPlugin` registry

**Registry lives in a NEW shared lib `src/app/inspecto/viz/`** (sibling of `query/`, `data-table/`, `theme/`) —
cross-feature reusable, never a feature→feature import. Files: `viz-types.ts`, `viz-registry.ts`
(`registerViz/getViz/allViz`), `query-spec.ts` (`compileSpec`+`runSpec`), `show-me.ts`, `controls.ts`,
`viz-render.component.ts` (dispatcher), `plugins/{kpi,table,line,bar,area,pie,scatter}.plugin.ts`,
`plugins/kpi.component.ts`, `index.ts`.

**Studio charts** under `modules/admin/studio/charts/`: `charts.routes.ts`, `charts.component.ts` (saved list),
`explore.component.ts` (workbench), `explore-controls.component.ts` (field-mapper), `chart-types.ts`,
`charts.service.ts` (mirror over `'chart'`), `chart-save.dialog.ts`.

**Contracts (sketch):**
- `VizPlugin`: `meta{type,label,icon,fit{minDim,maxDim,minMetric,maxMetric,temporal}}` · `controls: ControlSpec[]`
  (channel x/y/series/size/color/value + `acceptRoles`) · `buildQuery(controls,dataset): QuerySpec` ·
  `transformProps(rows,controls,dataset)` · `render: {kind:'chartjs'|'aggrid'|'g6'|'component', chartType?, component?}`.
- `QuerySpec`: `{datasetId, groupBy[], metrics[{id,expression,label}], filters: ConditionGroup (reuse Query Core),
  timeGrain?, orderBy?, limit?}`.
- `compileSpec` builds `SELECT groupBy, metric AS id FROM sourceName [WHERE][GROUP BY][ORDER BY][LIMIT]`, reusing
  the WHERE-builder + identifier quoting from `query-sql.ts` (**export `quoteIdent`/`compileWhere`**). `runSpec`
  → `runSql`. **timeGrain** bucketed in `transformProps` for AlaSQL (no `DATE_TRUNC`); DuckDB form behind a
  dialect flag.
- **Show Me**: `recommend({dims,metrics,temporal})` ranks plugins by `meta.fit` + `autoAssignChannels`
  (temporal→x, metric→y/value, 2nd dim→series, extra measures→size then color — the Tableau-style mapping).

**Live preview:** `viz-render.component.ts` switches on `render.kind`: chartjs→`<inspecto-chart>`,
aggrid→`<inspecto-data-table tier="standard">`, component→`NgComponentOutlet` (KPI), g6→no-op for now.
**KPI plugin** = `render.kind:'component'`, internal `mode` signal toggling mini/standard/maxSuperimposed in place.

**Existing files changed:** `inspecto/components/chart.component.ts` (theme axes for line/area/scatter/bubble +
stacked-bar — today only `bar` is styled) · `inspecto/theme/chart-tokens.ts` (add `CHART_CATEGORICAL` ramp — the
only sanctioned hex owner) · `inspecto/query/query-sql.ts` (export `quoteIdent`/`compileWhere`).

**P1 DoD:** pick dataset → Show-Me ranks → auto field-mapper → live preview on every change → save chart →
reload reproduces preview deterministically. KPI toggles 3 modes in place. Line/area/scatter themed (light+dark).
`lint:tokens` + a11y (incl. canvas text-alt) + `build` + `test:ci` green; verified in preview.

## Later phases (in the north-star doc, not built now)
- **P2 — Dashboard:** CDK drag-drop grid of saved charts; dashboard filters → cross-filter (inject into each
  chart's QuerySpec); save as `dashboard` component.
- **P3 — Depth:** calculated columns/metrics UI; more viz (gauge, funnel, pivot, G6 network); parameterized
  dashboards (reuse rule `:params`); export (PNG/CSV).
- **P4 — Backend execution + scheduling:** `QuerySpec → DuckDB` endpoint (build on `SqlSandbox`/new store +
  `RouteModule`); materialized datasets via `EnrichmentConfig.transformSql`; scheduled delivery via
  `JobType.REPORT`/new `Job`; align executable KPI with `NodeKind.KPI`.

## Key integration gotchas (verified)
1. `compileSql` is projection+WHERE only → QuerySpec compiler is net-new (don't overload `QueryModel`).
2. `runSql` is SELECT-only + single-table → no offline joins; do time-bucketing/post-agg in `transformProps`.
3. `chart.component.ts` themes axes only for `bar` → must extend for line/area/scatter.
4. `ColumnMeta` has no role → roles/formats/metrics are Studio-owned (`DatasetColumn`), seeded by `inferType`+`inferRoles`.
5. `ComponentType` is a closed union typed through every service method → widen it; keep `COMPONENT_TYPES` palette unchanged.
6. No `/components/*` mock exists → studio-mock must fully serve CRUD and register before `spaceInterceptor`.
7. Canvas-color lint guard → new palette hex only in `chart-tokens.ts`; KPI deviation via `status-badge`/`CHART_SERIES`.
8. a11y/lint gates → every spec needs `expectNoA11yViolations`; `@defer` editors need `compileComponents()`;
   chart `<canvas>` needs a text-alt; G6 can't instantiate in jsdom (its render arm must no-op in tests).
9. Follow existing literal-string i18n style (connections/rule dialogs aren't transloco-keyed) — don't half-migrate.

## Verification (per deliverable)
- **Doc:** committed + linked from `docs/INDEX.md`; cross-referenced with `rule-builder-design.md`.
- **Build loop:** `cd inspecto-ui && npm run lint && npm run lint:tokens && npm run build && npm run test:ci`
  green (use the build-verify skill / verify-runner).
- **Live, mock-first (preview tools):** boot the UI, navigate `/studio/datasets` → create a virtual dataset →
  `/studio/charts` → Explore → live preview → save → reload. Screenshot the KPI 3-mode toggle and a themed
  line chart (light + dark) as proof.
