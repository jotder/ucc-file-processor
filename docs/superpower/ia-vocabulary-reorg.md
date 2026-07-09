# Platform IA + Business-Data Vocabulary Reorg

> **Status:** Approved 2026-07-01, in progress. Phase A active. Durable in-repo home of the plan the user
> approved in a brainstorm (the plan-mode profile copy is ephemeral). Companion to
> [`widget-library-spec.md`](widget-library-spec.md); vocabulary is bound by [`../GLOSSARY.md`](../GLOSSARY.md).

## Context

The menu hierarchy and business-data vocabulary had drifted into confusion: **Workbench** and **Studio**
both read as "tooling/design"; the **"Data Sources"** group was both a *banned* glossary term and mislabeled
(it held pipelining features, not sources); two separate G6 relationship graphs existed (**Catalog** and
**Registry**); the top-level ops **"Dashboards"** group collided with **Studio Dashboards**; and the six
kinds of business data had no consistently-surfaced names.

**Goal:** a coherent **produce → catalog → consume** IA — Workbench builds pipelines, data assets land in a
Catalog, Studio visualizes them — with every label aligned to the binding glossary, plus the net-new
data-plane features (persisted **Matrices**, Job templates, a **Stream**-grouped Schema catalog, and a
Processing-Status/Provenance page). Scope: **full vision** (UI reorg + backing model/backend work), phased.

## Vocabulary (glossary-grounded)

The six business-data types the user works with map to canonical terms — surface them, don't invent:

| User's concept | Term | Note |
|---|---|---|
| External files/dumps/streams | **Stream** (Catalog data-origin) — **NEW** | a named external feed browsed in the Catalog; *populated by* **Connection** (endpoint) + **Source** (collection task) in Workbench. ⛔ "Data Source" banned |
| Dimension / reference data | **Reference Dataset** | enrichment-lookup data |
| Detailed parsed data (parquet) | **Table** | |
| Summary / matrix / cube | **Matrix** (= a Derived Table) — **NEW** | user-facing label for the cube/rollup asset; **structurally a Derived Table**. ⛔ "Cube" is a verb |
| Derived / filtered / persisted-or-view | **Dataset** = Table \| Derived Table \| View | umbrella covers concepts 3–5 (a Matrix is the Derived-Table member) |
| Named tabular data feeding widgets | **Dataset** (Studio kind) | already built |

**Stream** and **Matrix** are **additions to the binding glossary** (defined in `GLOSSARY.md`, recorded in
§13). They add concepts; they do **not** rename Source, Connection, or Derived Table.

Naming corrections in the reorg: "Data Sources" group **dissolved**; "Cubes" → **Matrix**; ops "Dashboards"
→ **Overview** (collision with Studio Dashboards); **Registry folded into Catalog** as a Usage/Reuse lens
(glossary defines Catalog as including "usage").

## Target IA

```
Platform (parent)
├─ Workbench   — Pipelines · Runs · Jobs · Components · Enrichment · Connections · Sources
├─ Studio      — Widget Builder · Dashboards
└─ Catalog     — Catalog graph (Lineage + Usage/Reuse lenses) · Datasets · Streams* · Schemas* · Matrices*
Operations (flat) — Overview · Processing Status* · Events · Audit · Diagnoses · Alerts · Incidents · Cases
KPI & Reports (flat) · Settings (flat) · Assistant (flat)      (* = net-new, arrives Phase B/C/E)
```

Max nesting = 3 levels (Platform → Workbench → Pipelines), identical to today's depth. In **Phase A** the nav
only wires items to routes that already exist; net-new pages (Streams/Schemas/Matrices/Processing Status)
join the nav as their phases land, never as dead links.

## Phased plan

- **Phase A — IA + vocabulary reorg (UI-only).** Rewrite `mock-api/common/navigation/data.ts`; move Sources
  & Jobs into Workbench; ops Overview rename; fold Registry nav into Catalog; add Stream + Matrix to
  `GLOSSARY.md` + §13. Routes unchanged (paths still resolve). *This alone resolves the confusion.* **DONE**
  (`22d377a`). **B.1 done** (same commit): Datasets nav item moved into the Catalog group; Catalog's graph
  tab relabeled 'Graph'→'Lineage'. **B.2 done:** Datasets URL moved `/studio/datasets`→`/catalog/datasets`
  (route now lives under `catalog.routes.ts`, component files unmoved under `studio/datasets/`; old
  `/studio/datasets` redirects for back-compat; `dataset.kind` side-effect import relocated with it).
- **Phase B — Catalog data-plane.** Stream-grouped Schema tree + data-asset browse (Tables/Matrices/
  Datasets); relationship graph with **Lineage** + **Usage/Reuse** lenses (reuse `catalog/graph-view.component.ts`
  + `component-model` `deriveComponentGraph()`); re-home Datasets into Catalog. Needs a backend catalog read model.
  **B.3 done (mock-only):** a 4th Catalog tab, **Streams**, lists each Source (+ Connection) as its
  Catalog-facing Stream identity; `demo-mock.interceptor.ts` now emits `SOURCE`-kind nodes + `EMITS` edges
  (Stream → its pipeline's output Table), so the Lineage graph and node-detail dialog also traverse
  Stream→Table. `CatalogService.streams()` hits a new mock-only `/catalog/streams`. No backend route yet —
  Schema-tree grouping remains open for a later slice.
  **B.4 done:** Registry folded into Catalog as a 5th tab, **Usage**, embedding the former standalone
  `RegistryComponent` (relocated `modules/admin/registry/` → `modules/admin/catalog/`, same filenames, so
  the embed is same-feature not cross-feature). `/registry` now redirects to `/catalog`; its nav item is
  gone. The embedded component's own `<h1>Registry</h1>` became `<h2>Usage &amp; reuse</h2>` (one h1 per
  page). **Phase B is now complete** (B.1–B.4 all shipped, mock-only where noted).
- **Phase C — Matrices (persisted summary Derived Tables). DONE** (backend shipped 2026-07-08 = DAT-4,
  reconciled here 2026-07-10 — this section previously said "Backend-gated"). `com.gamma.job.MaterializeTask`
  (`task: materialize`) persists a BI-7 spec-compiled SELECT/snapshot as Parquet with an atomic swap and
  registers it as a normal `dataset` component — selectable as a Studio Dataset per this phase's intent.
  Detail: `superpower/backend-backlog.md` §2.
- **Phase D — Job templates (trigger-condition-action). DONE** (= PIP-6, shipped 2026-07-08, reconciled
  here 2026-07-10 — this section previously said "Backend work"). `com.gamma.job.JobTemplate`
  (`*_job_template.toon`, `${param}` substitution) atop `JobService`/`CronExpression`; superseded/expanded
  by the job framework (`docs/job-framework-design.md` §7/§8.2/§14). Detail: `superpower/backend-backlog.md` §3.
- **Phase E — Processing Status / Provenance. DONE.** New Operations page
  `modules/admin/processing-status/` (`/processing-status`) — a cross-pipeline rollup (every pipeline's
  committed/quarantine counts + last-batch outcome, `GET /status` via the existing `ReportsService`) that
  didn't exist before (today's per-pipeline files/lineage/quarantine drill-down already lives in
  Runs > `run-detail`, and Enrichment's own Stage-2 "Lineage" tab is a distinct job-scoped concept — this
  page complements both with a summary view + a row action to jump into a pipeline's Run detail, rather than
  duplicating either). Mock-only (no new backend route; reuses `/status`).

**Backend-gating:** C & D depend on the same seam as the Widget-Library **M2 backlog** (closed
`ComponentStore.WRITABLE_TYPES` enum, auth-free core). A / B / E are mock/UI-feasible now.

## Critical files

- Nav / routes: `inspecto-ui/src/app/mock-api/common/navigation/data.ts`, `app/app.routes.ts`, `app/modules/admin/studio/studio.routes.ts`
- Registry→Catalog fold: `app/modules/admin/registry/{registry.component.ts,components-data-provider.ts,platform-kinds.ts}` + `app/modules/admin/catalog/{graph-view.component.ts,store-lineage.component.ts,catalog-graph.ts}`
- Studio datasets: `app/modules/admin/studio/datasets/dataset-types.ts`
- Enrichment / provenance source: `app/modules/admin/enrichment/**`
- Jobs backend: `inspecto/src/main/java/com/gamma/job/JobService.java`, `com/gamma/service/CronExpression.java`
- Vocabulary source of truth: `docs/GLOSSARY.md` (§13 touchpoint table)

## Verification

Per phase: `npm run lint:tokens` + `npm run build` + `npm run test:ci` green; live-preview nav walk (every
regrouped/renamed route resolves, no dangling links); Catalog graph lenses render (G6 can't mount in jsdom →
assert the empty/no-graph path in specs, verify graphs live). Backend phases add `mvn -o test`.
