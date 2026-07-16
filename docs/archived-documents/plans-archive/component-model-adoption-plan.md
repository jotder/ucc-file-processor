# Unified Component Model — interfaces, thin components, incremental adoption

## Context

**The insight (user):** every reusable artifact "works the same, differing only by configuration." A pipeline,
job, or dashboard is **just a combination of components with its own config, connected in a particular
fashion.** So leaf and container are the *same thing* — a **Component** — differing only in whether it has
*parts* and how those parts are *wired*.

**Why now:** we're about to build Studio (datasets/charts/dashboards) and the user wants the platform's
component concept unified, bounded by clean interfaces/patterns, with **UI components kept thin and all
logic/pipes/patterns pushed into reusable framework-agnostic libraries** (we're on mock backend, so logic must
not be entangled with components or HTTP).

**Impact on existing work = LOW / additive (the key finding).** An audit confirms the codebase *already*
follows "thin component / logic-in-library": `inspecto/query/` is 100% framework-free; `data-table/core` +
`sql` are ~95% pure; `flow-graph.ts` / `catalog-graph.ts` are pure G6 mappers; hosts delegate. So this is
*reinforce + extend*, not *introduce* — realized as a new shared lib + a kind registry that existing kinds opt
into via thin adapters. No big-bang migration.

**Intended outcome:** one recursive component model + a `ComponentKind` registry (the generalization of the
planned Studio `VizPlugin`); the "relationship graph" derived (composition ∪ reference), rendered by the
existing catalog G6 host; thinner components; Studio built *on* the model as its proving ground.

> **Guardrail (user): do the improvement only while it stays simpler than the status quo.** Every phase below
> has an explicit **STOP** criterion. If a step fights the existing code, keep the existing path.

## The model (one recursive shape)

```
Component { kind, id, name, space?, config, parts?: Part[], wiring? }   // atomic = no parts/wiring
Part      { partId, ref: {kind,id?|inline}, configOverride? }            // reuse + per-use override
Wiring    = none | graph{nodes,edges} | layout{tiles} | schedule | mapping{channels}   // kind-specific
ComponentKind { id, label, allowedPartKinds, wiring, config{validate,create?},
                deriveWiring?, authoring{editorKey}, exec{runnerKey} }   // per-kind strategy seams
```
- **FlowGraph is literally a `pipeline`'s `wiring`**; a dashboard's grid is a `dashboard`'s `wiring`; a chart's
  channel map is a `chart`'s `wiring`. Nothing new invented — existing shapes map on.
- **VizPlugin = the `kind:'chart'` entry**; `inspecto/viz/` becomes a consumer of the model.
- **`rule`/`QueryModel`** = an atomic `chart`-adjacent kind whose `config` is the `RuleTemplate` body
  (`where` is a `ConditionGroup`, reused verbatim).
- **Relationship graph = derived**, not persisted: composition (part-of) ∪ reference (uses) edges →
  `G6GraphData` → existing `GraphViewComponent`.
- **Strategy seams are string keys** (`editorKey`/`runnerKey`), resolved by a thin Angular map
  (`NgComponentOutlet`) — so the lib imports **no Angular** and is vitest-pure (like `query/`).

## Deliverables (each additive, independently shippable, reversible; DoD = lint:tokens + prod build + test:ci incl. a11y + preview-verified)

### D0 — Capture the north-star concept
`docs/superpower/component-model.md` — the model above, explicitly mapped onto the real seams
(`ComponentStore`/`ComponentsService`, `FlowGraph`, `catalog-graph` G6, `VizPlugin`). Cross-link from
`docs/superpower/report-builder-design.md` + `docs/INDEX.md`.

### P0 — Lib foundation `inspecto/component-model/` (zero existing-behavior change)
New shared lib, sibling of `query/`, **no Angular imports**:
- `component-types.ts` (Component/Part/Wiring), `component-kind.ts` (ComponentKind + seams),
  `component-registry.ts` (module-level register/get/all, like `viz-registry`), `component-graph.ts`
  (`deriveComponentGraph` → `G6GraphData`; dangling-ref → ghost node), `data-provider.ts`
  (`columns/preview/list` — the backend-agnostic seam, mock now), `index.ts` barrel, `*.spec.ts`.
- **Only existing edit:** export `quoteIdent` + the WHERE/group compiler from `inspecto/query/query-sql.ts`
  (currently module-private; Studio needs this anyway). Additive.
- **DoD:** lib compiles; vitest covers graph derivation (atomic/composite/dangling) + registry. No app change.
- **STOP:** ship only the `Wiring` variants a real kind consumes (`graph`/`mapping`/`none`); don't pre-add `layout`/`schedule` until P1/P2 use them.

### P1 — Studio built ON the model (VizPlugin = first kind)
Build Studio per [`studio-implementation-plan.md`](studio-implementation-plan.md),
registering `chart`/`dataset`/`dashboard` as `ComponentKind`s; `inspecto/viz/` plugins are sub-entries of the
`chart` kind. Proves the model with one real kind before any migration.
- **DoD:** Studio P0/P1 DoD + `allKinds()` returns the three + a chart round-trips as a `Component`.
- **STOP:** if the model makes `VizPlugin` registration more awkward than the standalone `viz-registry`, keep
  `viz-registry` and have `chart` register a *thin* kind pointing at it (`exec.runnerKey:'viz'`). Don't force it.

### P2 — Thin adapters register existing kinds (register-only, no UI/behavior change)
`grammar`/`schema`/`transform`/`sink`/`rule` as kinds backed by `ComponentsService` + `DataProvider`;
`pipeline` as a composite kind whose `deriveWiring` reuses `flow-graph.ts` (AuthoredFlow→parts+edges). Same
shape as `rule/rules.service.ts`.
- **DoD:** `allKinds()` lists all platform kinds; `DataProvider.list('grammar')` returns existing components via mock; existing screens unchanged.
- **STOP:** validators are tiny hand-written fns or no-ops — **no JSON-schema engine**. An adapter > ~80 lines means that kind isn't ready; skip it.

### P3 — Registry / reuse-graph view + pipeline-component table
New `modules/admin/registry/`: (a) `deriveComponentGraph(allComponents)` rendered via the **existing**
`GraphViewComponent`; (b) a `<inspecto-data-table>` over derived refs (cols: **id · pipeline/job · type/kind ·
action[view/edit]**) — a *view over derived edges*, not a new store.
- **DoD:** graph renders composition + reference edges; table lists `uses`/`part-of` rows; node-click → detail; a11y on the no-graph arm (G6 can't instantiate in jsdom).
- **STOP:** if the global graph is a hairball at real scale, scope to a **focused** (one component + neighbors) view and drop the global one.
- **Note:** the *admin menu-placement* activity + per-edition/role *gating* depend on the auth/RBAC model that **does not exist yet** (core is auth-free; `inspecto-security` planned). Build the menu-layout activity (shared-per-space) when reached; gating lands only with the security module.

### P4 — Formatter/pipe consolidation + fat-component extractions
- `inspecto/format/format.ts` — pure fns (`fmtDateTime` moved here + `grid/index.ts` re-exports it;
  `fmtBytes`/`fmtInt`/`fmtNumber`/`fmtPercent`/`fmtCurrency`). `inspecto/format/pipes.ts` — thin pure standalone
  pipes wrapping the fns (the app has **no** formatting pipes today). Pipes in templates; pure fns in TS.
- Extract inline logic to pure fns: `sources.component.ts:126-174` (metrics + `fmtBytes`) →
  `sources-metrics.ts`; `pipeline-detail.component.ts:192-219` (file-status filters/stats) → `file-status.ts`.
- **DoD:** behavior identical; logic now in tested pure fns; ≥1 pipe used in a template.
- **STOP:** consolidate a formatter only at ≥2 call sites; don't pipe-ify everything.

## Impact map
- **Additive (deletable to revert):** `inspecto/component-model/**`, `inspecto/format/**`, `modules/admin/registry/**`, `inspecto/viz/**` + `modules/admin/studio/**`, per-kind adapter files.
- **Touched (small/additive):** `query/query-sql.ts` (export private helpers) · `grid/index.ts` (`fmtDateTime` re-export, name+barrel unchanged) · `catalog-graph.ts` *(optional, recommended)* lift `G6Node/Edge/GraphData` to a shared `inspecto/graph/` so the shared lib never imports a feature (1-line directional flip) · `components.service.ts` (widen `ComponentType` union, palette unchanged) · `sources`/`pipeline-detail` components (delegate to pure fns) · one-time Studio app wiring (`app.routes.ts`, `app.config.ts`, navigation `data.ts`, `environment*.ts`, `api/index.ts`).
- **Wide-refactor risks — explicitly avoided:** backend `ComponentStore.WRITABLE_TYPES` closed enum (unknown → 400) — **not** widened now; mock serves new kinds; persistence is optional via `DataProvider`. No edits to `data-table.component.ts`, `GraphViewComponent`, `chart.component.ts` (beyond Studio's own axis-theming), or the flow editor — the model **consumes** them unchanged.

## What to deliberately NOT do (over-abstraction traps)
1. **No single generic wiring editor** — `Wiring` is data; authoring stays per-kind (graph editor ≠ CDK grid ≠ channel mapper).
2. **No forced storage unification** — keep `/components/{type}` + per-kind adapters; the registry is an in-memory *kind* catalog, not a new persistence layer.
3. **No big-bang migration** — existing screens untouched until a phase opts them in; P2 adapters are register-only.
4. **No patterns-for-their-own-sake** — no schema engine, no `DataProvider` until a consumer needs it, no `Wiring` variant before a kind uses it, no formatter move below 2 call sites.
5. **No re-rolling the design system / graph host / table** — reuse `GraphViewComponent`, `<inspecto-data-table>`, the shared primitives.

## Verification
- Per phase: `cd inspecto-ui && npm run lint:tokens && npm run build && npm run test:ci` green (build-verify skill / verify-runner); new specs include `expectNoA11yViolations`; pure libs unit-tested without TestBed.
- Live (preview tools): P1 — `/studio/*` create→preview→save→reload; P3 — `/registry` graph + table render, node-click detail, themed light+dark. Screenshot proof.

## Housekeeping
This plan file lives on the user profile only because plan mode requires it. **On approval I'll relocate it to
`docs/superpower/component-model-adoption-plan.md`** (and write D0's `component-model.md`), per the standing
"nothing project-related on the profile; keep docs/plans under docs/superpower" instruction — then delete the
profile copy.
