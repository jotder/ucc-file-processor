# R3 — Query kind + `$`-Parameters + ResultSet descriptor (full slice)

## Context

R3 of the Living Operational System roadmap (`docs/superpower/living-operational-system.md` §4–§5).
R1 shipped the one ref-derivation; R2 added the `job` kind. R3 lifts the **query** out of the four
sites that embed it inline (dataset `QueryModel`, widget `QuerySpec`, geo/link view projections) into
a first-class **`query` ComponentKind**, so *one query can serve many renderings*; adds a runtime
**`$`-parameter** namespace (distinct from `:fieldValue` rule templates and `${ENV:…}` secrets); and
formalizes the **Result Set** descriptor that the Show-Me recommender already half-computes. The user
chose the full A+B+C scope in one slice. Mock-first, UI-only, independently shippable — same rhythm as
R1/R2. Proposed vocabulary (§6) becomes real here, so GLOSSARY is updated as part of the slice.

> **First implementation step:** copy this approved plan to `docs/superpower/query-kind-plan.md`
> (repo rule: working artifacts live in-repo, never on the profile).

## Scope decisions (deliberate cuts, in the R2 "no abstraction without a second consumer" spirit)

- `QueryType` union = **`'sql' | 'structured'`** only (the two with a live run path: SQL via `runSql`,
  structured via the Query Core `QueryModel`→`compileSql`). `graph`/`spatial`/`search`/`api` are **not**
  built — geo/link views keep their own query shapes; folding them in is a later slice. Noted in the doc.
- Referencing consumer that proves the thesis = **widget → query** (`binds`): a saved query bound by
  **two** widgets with different viz types. Query itself `binds` its dataset → chain `widget→query→dataset`.
- `$`-namespace ships only the params with a live resolver: **`$today`, `$now`, `$day(-N)`,
  `$current_user`, `$role`**, plus user-declared `$name` defaults. The rest of the design's list is
  declared as future providers, not built.

## Part A — the `query` ComponentKind

New feature folder `inspecto-ui/src/app/modules/admin/studio/queries/`:

- **`query-types.ts`** — pure model (mirrors `dataset-types.ts`):
  ```ts
  export type QueryType = 'sql' | 'structured';
  export interface QueryConfig {
    type: QueryType;
    datasetId?: string | null;        // source dataset → `binds` ref
    sourceName?: string;              // logical FROM (from the dataset)
    text?: string | null;            // SQL (type:'sql')
    model?: QueryModel | null;       // Query Core (type:'structured') — reuse app/inspecto/query
    parameters: ParameterDef[];      // Part B
  }
  ```
- **`query.kind.ts`** — register `QUERY_KIND` (house pattern, guarded): `id:'query'`, `wiring:'none'`,
  `allowedPartKinds:[]`, `config.validate/create`, `deriveRefs → queryRefs`,
  `authoring:{editorKey:'query'}`, `exec:{runnerKey:'query'}` (runner key already used by `dataset.kind`).
- **`queries.service.ts`** (`inspecto/api`, barrel-exported) — generic component CRUD over
  `componentCollection('query')`; mock-served, no new handler needed (the components handler is generic).
- **`queries.component.ts` + `.html`** — Query Library list + editor. Reuse the **data-table pro** SQL
  surface (CodeMirror + AlaSQL `runSql`) for the SQL editor and a live result preview; the preview panel
  renders the **ResultSet descriptor** (Part C) and the resolved `$`-params (Part B). Create/edit follow
  the binding form rules (`uniqueNameValidator`, ask-the-minimum name/description at save).
- **`queries.routes.ts`** — `export default [...] as Routes`.

## Part B — `$`-parameter namespace (pure + a tiny service)

- **`inspecto/query/parameters.ts`** (pure, unit-testable, barrel-exported):
  ```ts
  export interface ParameterDef { name: string; type: 'date'|'string'|'number'; default?: string; label?: string; }
  export interface ParameterContext { now?: Date; user?: string; role?: string; }
  export function findParameters(text: string): string[];              // the $tokens present
  export function resolveParameters(text: string, defs: ParameterDef[], ctx: ParameterContext): string;
  ```
  Substitutes `$today`,`$now`,`$day(-N)`,`$current_user`,`$role`, then user-declared `$name` from
  `defs[].default`. Leaves `:fieldValue`/`:watermark`/`${ENV:…}` untouched (different namespaces — a
  comment documents the boundary).
- **`ParameterContextService`** (`inspecto/api`, root) — builds a `ParameterContext` from `LensService`
  (user/role) + the clock. The resolution seam the design names.
- **Integration:** the query editor's run/preview resolves params before `runSql`; a query-bound widget
  resolves them in the run path (`explore.component`) before compiling/running. **Parameter chips** in the
  query editor list detected `$`-tokens + editable defaults.

## Part C — ResultSet descriptor (formalize what Show-Me half-computes)

- **`inspecto/viz/result-set.ts`** (pure, barrel-exported):
  ```ts
  export interface ResultColumn { name: string; type: ColumnType; role: FieldRole; cardinality?: number; }
  export interface ResultSet { columns: ResultColumn[]; rowCount: number; }
  export function describeResultSet(rows: Record<string, unknown>[], hints?: Partial<ResultColumn>[]): ResultSet;
  ```
  Generalizes the inline field/role/cardinality inference currently in `explore.component.ts:88-101`
  and `dataset-types.ts inferRoles`.
- **Second consumer (earns C its place):** refactor the **Show-Me recommender** (`viz/recommend`, called
  at `explore.component.ts:103`) to score against a `ResultSet` instead of an ad-hoc `VizField[]`. Both the
  query editor preview *and* the widget builder then read the **same** descriptor — "the same result set
  rendered differently by metadata alone." Backward-compatible: `VizField[]`→`ResultSet` via a thin adapter
  so dataset-bound widgets are unchanged.

## Wiring the three R1 consumers (mechanical — R1 made them generic)

1. **`component-model/refs.ts`** — add `queryRefs(config)` (query `binds` `datasetId`); register
   `query: queryRefs` in `STRUCTURAL`; extend `widgetRefs` to add a widget→`query` `binds` edge when
   `config.queryId` is set. (`RefRel` already has `binds` — no new rel.)
2. **`mock/integrity.ts`** — add `'query'` to `COMPONENT_KINDS` (deleting a query a widget binds → 409).
3. **`catalog/registry.component.ts`** — add `'query'` to `REGISTRY_KINDS`; `EDITOR_PATH.query =
   '/studio/queries'`.
4. **`transfer/bundle.ts`** — add `'query'` to `BundleKind` + `BUNDLE_KINDS` ordered **after `dataset`,
   before `widget`** (query binds dataset; widget binds query).
5. **`widgets/widget-types.ts`** — add optional `queryId?: string` to `WidgetConfig`; widget kind runs the
   bound query (resolved) when set, else the existing controls→spec path. Validator: query-bound widget
   needs a `queryId` xor a `datasetId`.

## Seeds / routing / glossary

- **Seed** (`mock/seeds/default-space.seed.ts`): one shared `query` (SQL over an existing seeded dataset,
  with a `$day(-7)` param) + **two** widgets binding it (e.g. a bar + a KPI) to demo one-query-many-renderings.
  Bump `MOCK_STORE_KEY` **v10 → v11**.
- **Routing:** lazy `/studio/queries` in `app.routes.ts` + a "Query Library" nav item in
  `mock-api/common/navigation/data.ts` (Studio group, next to Viz Library).
- **GLOSSARY** (`docs/GLOSSARY.md`): adopt **Query** (kind), **Parameter**, **Result Set** (the §6 proposed
  terms become binding); note the three parameter namespaces (`$` runtime · `:` rule template · `${ENV:}` secret).
- **Docs:** flip R3 → SHIPPED in `living-operational-system.md` §5; short note in `metadata-network-design.md`.

## Tests (add alongside each piece)

- `query/parameters.spec.ts` — resolve `$today`/`$day(-N)`/`$current_user`/defaults; leaves `:`/`${ENV}` alone.
- `viz/result-set.spec.ts` — `describeResultSet` inference + recommender ranking over a `ResultSet`.
- `queries/query.kind.spec.ts` — validate + `deriveRefs` (query→dataset).
- `component-model/refs.spec.ts` — add `queryRefs` + widget→query cases.
- `mock/handlers/components.handler.spec.ts` — 409 when deleting a query a widget binds.
- `queries/queries.component.spec.ts` — behavior + `expectNoA11yViolations`.
- `transfer/bundle.spec.ts` — query pulled into a widget's dependency closure.

## Verification (Definition of Done)

1. `npm run lint:tokens` — token guard green.
2. `npm run build` — AOT + budgets green.
3. `npm run test:ci` — all specs + axe green (baseline was 933; expect ~+15).
4. **Preview** (`inspecto-ui` dev server): create a query with a `$day(-7)` param → preview resolves +
   runs (ResultSet shown); bind it from two widgets with different viz types → both render; reuse-graph
   (`/catalog`) shows one query bound by two widgets and binding its dataset; delete the query → 409 toast;
   export a widget from Settings → Import & Export → closure pulls the query + its dataset.
5. Update `/design` only if a shared primitive changed (none expected — reuses data-table pro + chips).
6. Commit `feat(ui): R3 …` (master-only per release-workflow); push only on explicit ask.

## Risks / notes

- **Largest slice yet** (~18 files). Kept coherent by leaning on existing seams: `runSql`/`compileSpec`,
  the data-table pro SQL surface, `refsForComponent`, the generic components handler. One commit as the
  user asked; the three parts are separable in review by folder.
- **`widget.kind.spec.ts` registry-isolation flake** (`task_a7ab593f`) may surface adding a new kind —
  register `QUERY_KIND` with the same `if (!getKind(...))` guard the others use; if the flake trips, it's
  the known isolation issue, not this change.
- The **structured** query type reuses the Query Core `QueryModel` — no new query builder UI in this slice
  (the SQL editor is the primary surface; structured is import/seed-only for now, editor is a follow-on).
