# Platform IA + Business-Data Vocabulary Reorg

## Context

The menu hierarchy and business-data vocabulary have drifted into confusion: **Workbench** and
**Studio** both read as "tooling/design," the **"Data Sources"** group is both a *banned* glossary term
and mislabeled (it holds pipelining features, not sources), two separate G6 relationship graphs exist
(**Catalog** and **Registry**), the top-level ops **"Dashboards"** group collides with **Studio
Dashboards**, and the six kinds of business data the user works with have no consistently-surfaced names.

**Goal:** a coherent **produce → catalog → consume** information architecture — Workbench builds
pipelines, data assets land in a Catalog, Studio visualizes them — with every label aligned to the
**binding** `docs/GLOSSARY.md`, plus the net-new data-plane features the user wants (persisted
Derived Tables/Cubes, Job templates, a source-grouped Schema catalog, and a Processing-Status/Provenance
page). "Cube"/summary assets are user-facing **Matrices** (structurally Derived Tables); external data
origins are **Streams**. Scope confirmed by the user: **full vision** (UI reorg **and** the backing model/backend work),
phased so each slice ships independently.

> **Repo-rule note:** per project `CLAUDE.md`, durable plans live in-repo. On approval, **first action** is
> to persist this as `docs/superpower/ia-vocabulary-reorg.md` and update `docs/GLOSSARY.md` §13. This
> profile-path plan file is the ephemeral plan-mode copy only.

## Vocabulary decisions (all glossary-grounded)

The six business-data types already have exact canonical names — surface them, don't invent:

| User's concept | Canonical term | Note |
|---|---|---|
| External files/dumps/streams | **Stream** (Catalog data-origin) — **NEW glossary term** | a named external feed browsed in the Catalog; populated by **Connection** (endpoint) + **Source** (collection task) in Workbench. ⛔ "Data Source" banned |
| Dimension / reference data (versioned, cached) | **Reference Dataset** | glossary's term for enrichment-lookup data |
| Detailed parsed data (parquet) | **Table** | |
| Summary / matrix / cube | **Matrix** (= a Derived Table) — **NEW glossary term** | user-facing label for the cube/rollup asset; **structurally a Derived Table**, so the Dataset umbrella stays coherent. ⛔ "Cube" is a verb (a Transform action) |
| Derived / filtered / persisted-or-view | **Dataset** = Table \| Derived Table \| View | one umbrella covers concepts 3–5 (a Matrix is the Derived-Table member) |
| Named tabular data feeding widgets | **Dataset** (Studio kind) | already built (physical/virtual/materialized) |

Two of these (**Stream**, **Matrix**) are **additions to the binding glossary**, not just UI labels — the
reorg amends `docs/GLOSSARY.md` to define them (Stream = Catalog data-origin; Matrix = labeled subtype of
Derived Table) and records both in §13. They add concepts; they do **not** rename Source or Derived Table.

Naming corrections baked into the reorg:
- **"Data Sources" group → dissolved** (banned + mislabeled). Its children move under **Workbench**.
- **"Cubes" (noun) → "Matrix"** — the user-facing catalog label; under the hood it's a Derived Table.
- **Ops "Dashboards" group → "Overview"** (or folded into Operations) — resolves the collision with Studio Dashboards (glossary "Dashboard" = a layout of Widgets).
- **Registry → folded into Catalog** as a "Usage/Reuse" lens (glossary: Catalog includes "usage"). Retire the standalone Registry nav item; keep both graphs as tabs (Lineage + Usage).
- **Stream** is the Catalog's data-origin concept; **Connection** (endpoint) + **Source** (collection task) stay in **Workbench** and populate a Stream. No rename of Source/Connection — Stream is additive.

## Target IA

```
Platform (parent group)
├─ Workbench   — Pipelines · Jobs · Components (incl. a Parsers facet) · Enrichment · Connections · Sources
├─ Studio      — Widget Builder · Dashboards        (Registry removed → Catalog)
└─ Catalog     — Streams (data origins) · Schemas (per Stream) · Datasets · Matrices
                 + Relationship graph: [Lineage lens] [Usage/Reuse lens]
Operations (flat) — Overview · Processing Status (NEW) · Events · Audit · Diagnoses · Alerts · Incidents · Cases
Settings (flat) · Assistant (flat) · KPI & Reports (flat; candidate to move under Studio — minor, defer)
```

Nesting depth (Platform → Workbench → Pipelines) equals today's (Workbench → Data Sources → Pipelines) —
no *new* depth, just relabeled. gamma/Fuse sidebar already supports this.

## Phased action plan

Independently shippable; ordered so the cheap clarity win lands first and backend-gated work is isolated.

- **Phase A — IA + vocabulary reorg (UI-only, no backend).** Rewrite
  `mock-api/common/navigation/data.ts` to the target tree; regroup routes; fold the Registry nav entry
  into Catalog; rename ops "Dashboards"→"Overview"; move Sources under Workbench; update
  `docs/GLOSSARY.md` §13 touchpoints + all UI labels. *This alone resolves the confusion.*
- **Phase B — Catalog data-plane.** **Stream**-grouped **Schema** tree + data-asset browse
  (Tables/Matrices/Datasets); the two graph lenses reuse the existing G6
  `catalog/graph-view.component.ts` + `component-model` `deriveComponentGraph()` (Usage lens) alongside
  the data-lineage graph. Needs a backend catalog *read* model (schemas-by-source).
- **Phase C — Matrices (persisted summary Derived Tables).** Backend materialization of a cube/rollup
  Transform's output as a Derived Table, surfaced in the Catalog as a **Matrix** + selectable as a Studio
  Dataset. **Backend-gated.**
- **Phase D — Job templates (trigger-condition-action).** Template model atop the existing
  `com.gamma.job.JobService` + `CronExpression`; authoring UI under Workbench > Jobs. **Backend work.**
- **Phase E — Processing Status / Provenance.** Promote lineage/provenance out of the Enrichment detail
  panel into a first-class Operations page (files processed, provenance + lineage graph, diagnostics).

**Backend-gating:** Phases C & D depend on the same seam as the Widget-Library **M2 backlog** — the closed
`ComponentStore.WRITABLE_TYPES` enum and the auth-free core. Sequence C/D after (or with) that M2 work;
A/B/E are mock/UI-feasible now.

## Critical files

- Nav / routes: `inspecto-ui/src/app/mock-api/common/navigation/data.ts`, `app/app.routes.ts`, `app/modules/admin/studio/studio.routes.ts`
- Registry→Catalog fold: `app/modules/admin/registry/{registry.component.ts,components-data-provider.ts,platform-kinds.ts}` + `app/modules/admin/catalog/{graph-view.component.ts,store-lineage.component.ts,catalog-graph.ts}`
- Studio datasets (Derived Table surfacing): `app/modules/admin/studio/datasets/dataset-types.ts`
- Enrichment / provenance source: `app/modules/admin/enrichment/**`
- Jobs backend: `inspecto/src/main/java/com/gamma/job/JobService.java`, `com/gamma/service/CronExpression.java`
- Vocabulary source of truth: `docs/GLOSSARY.md` (§13 touchpoint table)

## Verification

Per phase: `npm run lint:tokens` + `npm run build` + `npm run test:ci` green; live-preview nav walk
confirming every regrouped/renamed route still resolves and no dangling links; Catalog graph lenses render
(G6 can't mount in jsdom → assert on the empty/no-graph path in specs, verify graphs live in preview).
Backend phases (C/D) add `mvn -o test` for the new model + endpoints.
