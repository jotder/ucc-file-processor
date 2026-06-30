# GraphSource + Link Analysis Studio — design

> Status: **design** (2026-06-30). Unifies the three graph "lenses" we already render into one pluggable seam,
> and reserves a fourth for the future business **Entity/Link** graph. Vocabulary is locked in
> [`../GLOSSARY.md`](../GLOSSARY.md) §11; relationship analysis in [`../COMPONENT_GRAPH.md`](../COMPONENT_GRAPH.md);
> the metamodel this rests on is [`component-model.md`](component-model.md).
>
> **Scope of this doc:** define the `GraphSource` seam + the Link Analysis Studio shape, and a contained,
> behavior-preserving refactor to adopt it. The **P3 business Entity/Link graph is reserved, not built**
> (it pairs with schema relationships — scope undecided).

## 1. The insight

We already have **one renderer, three lenses, three bespoke wirings.** The renderer is pluggable; the **sources
are not.** A future **Link Analysis Studio** (graph analytics — paths, centrality, communities — over both
business data *and* system metadata) needs the *sources* to become pluggable too. That is the whole job: not a
new renderer, not a new graph store — **one query seam + N sources.**

## 2. What exists today (grounded)

| Lens (plane) | Produced by | Backed by | Has a query API? |
|---|---|---|---|
| **Lineage** (P2) | `catalog-graph.ts::toG6Data(nodes, edges)` | backend `MetadataGraphService` | ✅ `GET /catalog/graph?from&depth&direction&kinds&edgeKinds&overlay` |
| **Pipeline topology + provenance overlay** (P2′) | `flow-graph.ts::toFlowG6Data(g, counts?, iconMap?)` | flow topology + `DbProvenanceStore` counts | ⚠️ topology fetch only; counts via `/provenance` |
| **Component reuse** (P1) | `component-graph.ts::deriveComponentGraph(input)` | `ComponentsDataProvider.list()` (FE-derived) | ❌ pure client derivation |

All three emit the **same** shape and feed the **same** host:

```ts
// inspecto/graph/graph-types.ts  (existing)
interface G6Node { id: string; data: { label: string; kind: string; iconSrc?: string; color?: string; missing?: boolean }; }
interface G6Edge { id: string; source: string; target: string; data: { kind: string }; }
interface G6GraphData { nodes: G6Node[]; edges: G6Edge[]; }
// GraphViewComponent (G6 v5 / Dagre) consumes G6GraphData and keys shape/color off the generic `kind` string.
```

**The asymmetry:** the lineage lens already has the richest query (`GraphQuery` → `/catalog/graph`); flow and
component each invented their own path. That lineage query is the **seed** for the unified seam.

## 3. The seam

```ts
// inspecto/graph/graph-source.ts  (NEW — framework-agnostic, no Angular)

interface GraphQuery {
  from?:       string;                       // root node; absent = whole graph
  depth?:      number;                        // BFS radius from `from`
  direction?:  'OUT' | 'IN' | 'BOTH';
  kinds?:      string[];                      // node-kind filter
  edgeKinds?:  string[];                      // edge-kind filter
  overlay?:    boolean;                       // P2: attach OperationalOverlay
  counts?:     boolean;                       // P2′: weight edges by provenance row counts
  // entity-projection params (P3, reserved): datasetId, sourceCol, targetCol, linkKindCol, attrCols…
}

interface GraphSource {
  readonly id: 'component-registry' | 'lineage' | 'provenance' | 'entity-projection';
  readonly label: string;
  query(q: GraphQuery): Promise<G6GraphData>;   // async: some sources hit the backend, some derive client-side
}
```

- The interface is **the generalization of today's lineage `GraphQuery`** plus two additive flags (`counts`,
  and the reserved entity-projection params). It does **not** change the renderer or `G6GraphData`.
- `query()` is async so a source may call the backend (lineage, provenance) or compute client-side (component).

## 4. The four sources

| `GraphSource.id` | Wraps (existing) | Status |
|---|---|---|
| `lineage` | `CatalogService.graph()` → `toG6Data()` | adapt (it already *is* this shape) |
| `component-registry` | `ComponentsDataProvider.list()` → `deriveComponentGraph()` | adapt |
| `provenance` | flow per-step counts (`DbProvenanceStore`) → `toFlowG6Data(g, counts)` Sankey | exists (flow steps) |
| `entity-projection` | a Dataset projected via column→Entity/Link mapping | **reserved — not built (P3)** |

Each becomes a thin class implementing `GraphSource`; the **pure mapper functions stay unchanged** and are
called inside `query()`. No mapper logic moves.

**Cross-engine provenance is bridged at the STORE, not by `batch_id` (corrected 2026-06-30).** Grounding showed
the original "join `DbProvenanceStore` ⨝ ingest `LineageRow` on `batch_id`" was unsound: a **flow** reads a
`source_store` (data at rest) and has *no file dimension*; the **ingest** pipeline has the file→partition matrix
(`LineageRow`) but *no node dimension*; they are disjoint engines that don't share a `batch_id`. The bridge is
the **store name** (ingest *writes* `batches.output_table`; a flow *reads* it as `source_store`). Phase 4
shipped this as `GET /lineage?store=` (`control/LineageRoutes`): `upstream` = ingest file→partition counts into
the store, `downstream` = flows consuming it — stitching *file → store → flow-step → sink*. Per-record tracing
remains out of scope.

## 5. Link Analysis Studio (the consumer)

A host that: (1) lets the user pick a **GraphSource** + a **GraphQuery** (root, depth, direction, filters),
(2) renders the result with the **existing** `GraphViewComponent`, and (3) runs **analysis** over the returned
`{nodes, edges}` — the same moves the project already uses elsewhere (graphify): shortest **path** between two
nodes, **neighborhood/explain** of a node, **centrality**, **community** detection. Analysis operates purely on
`G6GraphData`, so it is source-agnostic by construction.

Studio is itself a **Component** (a Studio screen), consistent with the metamodel; a saved Link-Analysis view is
a **Widget** when its source is `entity-projection` (a Graph Visualization Type bound to a Dataset).

## 6. Adoption — contained, behavior-preserving

1. Add `inspecto/graph/graph-source.ts` (interface + `GraphQuery`). No call-site churn.
2. Wrap the **three existing** mappers as `LineageGraphSource`, `ComponentRegistryGraphSource`,
   `FlowGraphSource` — each calls its current mapper verbatim inside `query()`.
3. Point the three existing screens at their source (optional; can stay on the direct mapper until opted in).
4. `provenance` source = the existing flow per-step Sankey; **Phase 4** additionally shipped the store-keyed
   cross-engine stitch (`GET /lineage?store=`, `control/LineageRoutes`) — see §4.
5. `entity-projection` is a documented seam only.

**Verification (no UI change):** unit-test each `GraphSource.query()` returns **byte-identical** `G6GraphData`
to the current mapper for the same input. This is the contract that proves the refactor is behavior-preserving.

## 7. Deferred — P3 Entity/Link + schema relationships (scope undecided)

The business graph (caller→callee, call-type) is **designed but not built** here. When scheduled, it is an
`entity-projection` GraphSource backed by a **Graph Visualization Type** whose **Widget** binds a **Dataset** via
a `mapping` wiring:

```
mapping: { sourceEntity: <col>, targetEntity: <col>, linkKind?: <col>, linkAttrs?: [<col>…] }
```

It pairs with **schema relationships** (FK-like links between Schemas/Tables) — which the lineage graph (P2)
partly expresses via `JOINS_INTO` / `FEEDS` but does *not* yet expose at the business-attribute level. Designing
that projection + the schema-relationship model is the open work, intentionally **out of scope** for this pass.

## 8. Non-goals
- No new renderer (the G6 host stays).
- No new graph store (sources derive or query existing stores; the metadata lineage + provenance stores remain).
- No per-record provenance (Phase 4 is per-batch step counts, stitched at the store; see [`../GLOSSARY.md`](../GLOSSARY.md) §11).
- No generic graph-query language — `GraphQuery` stays a small typed record; extend it only when a source needs it.
