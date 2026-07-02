# Plan — Canonical vocabulary + relationship model (4 sequenced workstreams)

## Context

We locked a canonical vocabulary (`docs/GLOSSARY.md`) to kill ambiguous/overlapping terms. The user then raised
the real stakes: a future **Link Analysis Studio** over **two kinds of graph** — business-object relationships
(caller→callee, tied to schema relationships) *and* component/lineage/provenance relationships ("how many records
from file F moved through which pipeline step, source→sink"). That makes the **relationship model load-bearing**,
so we extend its vocabulary *before* renaming and rename **once**.

Grounding (two read-only Explore passes) shows the substrate is **already substantially built** — the work is to
*name it cleanly, rename consistently, unify the query seam, and close one provenance join* — **not** to reinvent.

Decisions locked with the user (2026-06-30):
- Workstreams this pass: **all four below.**
- Business Entity/Link graph = **a Graph Visualization Type over a Dataset** (column→Entity/Link mapping).
- Provenance v1 = **per-batch step counts** (extend + join what exists; render the Sankey).
- **The business-object relationship graph itself (P3) comes later, with schema relationships — scope undecided
  now.** This pass *reserves the vocabulary and the GraphSource seam* for it; it does **not** build it.

## What already exists (grounded — keep, don't reinvent)

- **One G6 renderer, three lenses:** `GraphViewComponent` (G6 v5/Dagre) consumes one `G6GraphData {nodes,edges}`,
  fed by pure mappers `catalog-graph.ts` (lineage), `flow-graph.ts` (pipeline topology, **with a row-count edge
  overlay**), `component-graph.ts` (registry, FE-derived `uses` edges + ghost nodes).
- **Lineage graph (P2) is real, backend, queryable:** `inspecto/catalog/MetadataGraphService` — 8 `NodeKind`
  (`SOURCE,RAW_SCHEMA,COLUMN,EVENT_TABLE,TRANSFORMED_TABLE,REFERENCE_TABLE,KPI,REPORT`), 8 `EdgeKind`
  (`EMITS,DECLARES,DESCRIBES,MATERIALIZES,FEEDS,JOINS_INTO,COMPUTED_FROM,USES`), traversal API
  `GET /catalog/graph?from&depth&direction&kinds&edgeKinds&overlay`, per-node `OperationalOverlay`.
- **Provenance (P2′) partly works:** `inspecto/flow/exec/DbProvenanceStore` → `inspecto_flow_provenance(flow_id,
  batch_id,node_id,rel,row_count,run_ts)` = per-batch×per-step×per-rel **counts** (not per-record), API
  `GET /provenance?flow=&batch=` + `/provenance/batches`, built to paint a Sankey. **Separate ingest model:**
  `BatchAuditWriter` + `LineageRow(batchId,srcId,inputFile,outputFile,partition,rowCount)` (file→partition rows).
  `BatchEvent`/`FileRow` give **File ⊆ Batch ⊆ Run**.
- **Gaps:** (1) renderer is pluggable but **sources are not** — no unified `GraphSource`/query seam; (2) **no
  business Entity/Link graph**; (3) the two provenance stores **aren't joined** (flow provenance has no
  `sourceFileId`; transform→sink lineage not captured; no per-record tracing).

## The graph-plane model + new vocabulary (Phase 1 defines these)

Four planes, named so `graph/node/edge/link/relationship/lineage` never blur:

| Plane | Relates | Node / Edge words | Status |
|---|---|---|---|
| **P1 Artifact** (Registry) | authored Components | Component / Part — `part-of`, `uses` | exists (FE-derived) |
| **P2 Lineage** | data assets | Asset / `EMITS,FEEDS,…` | exists (`MetadataGraphService`) |
| **P2′ Provenance** | a batch's records through Steps | Batch/Step — `flowed-through` (+counts) | partial (`DbProvenanceStore`) |
| **P3 Entity/Link** (business) | records as entities | **Entity** / **Link** (+attrs) | **deferred — scope undecided** |

New canonical terms: **Entity** / **Link** (business graphs only; never for artifacts/assets) · **Provenance**
(the fact) vs **Lineage** (the derived asset graph) · **Graph** (a queryable relationship object) · **GraphSource**
(where a Graph's nodes/edges come from: `component-registry | lineage | provenance | entity-projection`).

**Collisions to resolve in the glossary:**
- Lineage `USES` (Report→KPI) vs component `uses` (composite→part) → rename the lineage edge to **`CONSUMES`**
  (Report consumes KPI); keep `uses` for component reference.
- "lineage" overloaded: asset graph = **Lineage**; file→partition `LineageRow` = **Provenance** (rename row/concept).
- Backend `EVENT_TABLE / TRANSFORMED_TABLE / REFERENCE_TABLE` → canonical `Table / Derived Table / Reference
  Dataset` (add to §12 rename map; note these are API enum values = external contract).

---

## Phase 1 — Glossary extension (doc-only, zero risk) — **prerequisite**

Edit `docs/GLOSSARY.md`:
- Add a **§ Graphs & Relationships** plane: P1/P2/P2′/P3 + Entity/Link/Provenance/Lineage/Graph/GraphSource, each
  with its node/edge vocabulary and which plane it belongs to.
- Record the collision resolutions above; extend the **§12 rename map** with: `USES→CONSUMES` (lineage),
  `EVENT_TABLE/TRANSFORMED_TABLE/REFERENCE_TABLE → Table/Derived Table/Reference Dataset`, `LineageRow→Provenance`.
- Mark **P3 (business Entity/Link + schema relationships) as DEFERRED, scope undecided** — vocabulary reserved.
- Cross-link `COMPONENT_GRAPH.md`.

## Phase 2 — Pure renames (UI → model → backend), gated, no behavior change

Mechanical but with real blast radius; do **UI-first**, one banned term per PR, each gated by lint+build+tests.

- **Flow → Pipeline** (largest): UI `/flows` route + nav + `flow-graph.ts` + flow-editor components/services;
  backend `com.gamma.flow` (`FlowGraph,FlowExecutor,FlowTrigger,FlowJobRunner`), `JobType.FLOW`. ⚠️ **External
  contracts** need migration care: API route `/flows`, table `inspecto_flow_provenance`, query param `flow=`,
  `-Dprovenance.backend` — keep back-compat aliases or coordinate a breaking bump.
- **Issue → Incident:** `/issues` route + nav + issue components/services; backend issue store/API (`ops/`).
- **Data Store → Dataset:** mostly UI label audit (Studio already uses "Dataset"); **keep `ComponentStore`** (it
  is the physical store — correct per glossary). Grep UI for stray "store" labels on relations.
- **Metric → Measure (BI only):** Studio chart/KPI config field names; **keep** ops `MetricRegistry`/
  `MetricsService` as Metric.
- **Collector → Source:** label-only.
- **Lineage NodeKind/EdgeKind renames** (`EVENT_TABLE`→`Table`, `USES`→`CONSUMES`, …): `MetadataGraphService`
  enums + `catalog-graph.ts` + `/catalog/graph` response. ⚠️ external enum values — version or alias.

Representative files: `inspecto-ui/src/app/app.routes.ts`, `mock-api/common/navigation/data.ts`,
`inspecto/src/main/java/com/gamma/flow/**`, `inspecto/src/main/java/com/gamma/catalog/MetadataGraphService.java`.

## Phase 3 — GraphSource abstraction + Link Analysis design (design-first)

- **Design doc** `docs/superpower/link-analysis-and-graphsource.md`: define
  `interface GraphSource { query(q: GraphQuery): G6GraphData }` generalizing today's three mappers; the existing
  lineage `GraphQuery` is the seed. One renderer (exists) + one query seam + N sources.
- **Optional scaffold (contained refactor):** introduce the interface in `inspecto/graph/`, make
  `catalog-graph.ts` / `flow-graph.ts` / `component-graph.ts` implement it. No new UI behavior.
- **Reserve** the `entity-projection` source: confirm the design (a **Graph Visualization Type** whose **Widget**
  binds a **Dataset** via a `mapping` wiring: col→source Entity / col→target Entity / cols→Link attrs) — but
  **do not build P3** (deferred; pairs with schema relationships, scope undecided).

## Phase 4 — Provenance file↔step join (per-batch counts)

- Join `DbProvenanceStore` (per-step counts) with ingest `LineageRow` (file→partition) on `batch_id`; add an
  optional `sourceFileId` to flow provenance so file→step→sink is answerable per-batch.
- Expose a combined read (extend `JobRoutes` `/provenance`) and **render via the existing `flow-graph.ts` count
  overlay** (Sankey). Keep it behind `-Dprovenance.backend=duckdb`.
- **Out of scope:** per-record tracing.

---

## Deferred (scope undecided — reserved only)
- **P3 business-object relationship graph + schema relationships** — vocabulary + GraphSource seam reserved
  (Phases 1 & 3); modeling confirmed as Graph-Viz-over-Dataset; build deferred to the Link Analysis Studio track.

## Verification
- **Phase 1:** peer-read; `grep` for banned terms (⛔) to confirm none reintroduced.
- **Phase 2:** per PR — `npm run lint:tokens` + prod `build` + `test:ci` green; backend `mvn -o test`
  (DuckDB native-access flag); live-verify each renamed screen; confirm no route/API/enum breakage (or aliases work).
- **Phase 3:** unit tests for `GraphSource` impls = byte-identical `G6GraphData` to current mappers (no UI change).
- **Phase 4:** with `-Dprovenance.backend=duckdb`, run a flow over a known file; assert joined counts match
  `LineageRow` + per-step counts; verify the Sankey renders edge weights.

## Suggested execution order
Phase 1 (prereq) → then Phase 2 (UI-first, one term per PR) and Phase 4 (small) in parallel; Phase 3 as parallel
design work that lands before any Link Analysis build.
