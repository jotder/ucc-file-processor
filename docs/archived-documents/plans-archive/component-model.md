# Component Model — the unified metamodel (north star)

> Status: **design** (2026-06-28). The conceptual frame the Studio and the component-registry work are both
> instances of. Companion: the build sequence in
> [`component-model-adoption-plan.md`](component-model-adoption-plan.md); the viz half in
> [`report-builder-design.md`](report-builder-design.md). Grounded in the real seams
> (`ComponentStore`/`ComponentsService`, `FlowGraph`, `catalog-graph` G6, the planned `VizPlugin`).

## 1. The insight

Every reusable artifact "works the same, differing only by **configuration**." A **pipeline, job, or dashboard
is just a combination of components, with its own config, connected in a particular fashion.** So a *leaf* and a
*container* are the same thing — a **Component** — differing only in whether it has **parts** and how those
parts are **wired**.

This is not a green field: three patterns already *are* this metamodel, un-unified — (a) "X is a
`/components/{type}` component" (`rules.service.ts` maps `RuleTemplate` ↔ `ComponentDef`), (b) "typed shape →
`G6GraphData`" (`catalog-graph.ts`, `flow-graph.ts`), (c) the planned `VizPlugin` (a per-chart strategy
bundle). The model below *names and unifies* them; it does not replace them.

## 2. The model (one recursive shape)

```ts
Component { kind; id; name; space?; config; parts?: Part[]; wiring? }     // atomic = no parts/wiring
Part      { partId; ref: { kind; id? | inline: Component }; configOverride? }   // reuse + per-use override
Wiring    = { strategy:'none' }
          | { strategy:'graph';    nodes; edges }     // pipeline FlowGraph, job DAG
          | { strategy:'layout';   tiles }            // dashboard CDK grid
          | { strategy:'schedule'; cron?; on? }        // job triggers
          | { strategy:'mapping';  channels }          // chart field→channel

ComponentKind {                                        // the registry entry / per-kind strategy bundle
  id; label; allowedPartKinds: string[]; wiring: WiringStrategy;
  config:    { validate(c): ConfigFinding[]; create?(): C };   // CONFIG seam
  deriveWiring?(parts, config): Wiring;                        // WIRING seam (pure)
  authoring?: { editorKey: string };                          // AUTHORING seam (string key → Angular map)
  exec?:      { runnerKey: string };                          // EXEC seam (string key → runner)
}
```

**Strategy seams are string keys, not classes/functions** — resolved Angular-side via a token map
(`NgComponentOutlet`). So the whole model lib imports **no Angular** and is unit-testable in plain vitest, the
same discipline as `inspecto/query/`.

## 3. Everything maps onto it

| kind | parts | wiring | authoring (exists) | exec |
|---|---|---|---|---|
| grammar · schema · transform · sink | — | none | form / parser-config dialog | `/test` |
| connection | — | none | connection workbench | probe/sample |
| rule | — | none | rule builder (Query Core) | AlaSQL/DuckDB |
| dataset | source · connection · dataset | (query) | data-table pro | AlaSQL/DuckDB |
| chart | dataset | mapping (channels) | Explore field-mapper | render (Chart.js/ag-Grid) |
| kpi | dataset(s) | mapping + overlay | KPI editor | render |
| dashboard | chart · kpi | layout (grid) | CDK grid | render |
| pipeline · flow | source · parser · schema · transform · sink · route · join | graph (DAG) | flow editor (G6) | flow executor |
| job | pipeline · report | schedule | scheduler | job runner |

**`FlowGraph` is literally a `pipeline`'s `wiring`.** A dashboard's grid is a `dashboard`'s `wiring`. A chart's
channel map is a `chart`'s `wiring`. Atomic kinds = no parts/wiring; composites = parts + a wiring strategy; and
every composite is itself a referenceable part (recursive: chart ∈ dashboard, dataset ∈ chart, pipeline ∈ job).

## 4. The relationship graph is *derived*, not a new store

The "registry as a relationship graph" = the union of **composition** edges (parent → part, `part-of`) and
**reference** edges (part.ref → referent, `uses`), computed on demand by walking `parts`/`wiring`. It emits
`G6GraphData` so the **existing** `GraphViewComponent` renders it with zero new rendering code; a dangling ref
becomes a ghost node (mirrors the flow validator's "dangling" status). The catalog/metadata **lineage** graph
stays separate — it is the *data-asset* projection (auto-derived, config-only); this is the *authoring-artifact
reuse* projection. Two lenses, one renderer.

## 5. Mapping onto real seams (reuse, don't duplicate)

- **Persistence** stays federated and adapter-based: each kind serializes `Component.config` ↔
  `ComponentDef.content` via a thin service (the `rules.service.ts` pattern). The registry is an **in-memory
  kind catalog**, not a new persistence layer. Persistence is *optional* per kind (via a `DataProvider`
  interface) so a kind can exist while it's only mock-served — the backend `ComponentStore.WRITABLE_TYPES` is a
  closed enum (unknown → 400), widened only when a kind needs real persistence.
- **VizPlugin = the `kind:'chart'` entry.** `inspecto/viz/` becomes a *consumer* of the model; the model never
  absorbs Chart.js/G6 specifics — it just gives `chart` a home so the reuse graph sees charts beside pipelines.
- **`RuleTemplate`/`QueryModel`** = `rule`'s `config` (the `where` `ConditionGroup` is reused verbatim by the
  SQL/QuerySpec compilers).
- **`AuthoredFlow`** → a `pipeline`'s `parts` (nodes' `use` ref → `Part.ref`, node config → `configOverride`) +
  `wiring.edges`. `flow-graph.ts` mappers do the conversion.

## 6. Principles (how we keep it honest)

- **Thin components, logic in libraries.** Components wire inputs→signals and mount editors; *all* logic, pipes,
  and patterns live in framework-agnostic libs (`component-model/`, `query/`, `format/`, controllers). Extends
  the existing `data-table-controller.ts` / `query-sql.ts` pattern.
- **Patterns, used sparingly:** Registry/Plugin (the kind registry), Strategy (per-kind seams), Composite (the
  recursive Component), Adapter (wrap existing stores/graphs), Controller/Presenter (thin host + pure
  controller). No ceremony beyond genuine polymorphism.
- **Backend-agnostic seam:** kinds talk to data through a `DataProvider` interface — mock now, DuckDB later, no
  call-site churn.

## 7. What this is NOT (the over-abstraction traps)

1. **Not a single generic wiring editor** — `Wiring` is *data*; authoring stays per-kind (graph editor ≠ CDK
   grid ≠ channel mapper). NiFi gets one editor only because everything there is a DAG; here wiring is diverse.
2. **Not a storage unification** — keep `/components/{type}` + per-kind adapters; secrets/lifecycles differ.
3. **Not a big-bang migration** — existing screens are untouched until a phase opts them in; adapters are
   register-only. New kinds (dataset/chart/dashboard) conform from birth; old kinds opt in opportunistically.
4. **Not patterns-for-their-own-sake** — no JSON-schema engine (validators are small fns/no-ops); no `Wiring`
   variant before a kind uses it; no abstraction without a second consumer.

> Adoption sequence, impact map, and per-phase STOP criteria:
> [`component-model-adoption-plan.md`](component-model-adoption-plan.md).
