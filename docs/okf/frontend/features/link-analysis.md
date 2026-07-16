---
type: Feature
title: Link Analysis
description: The graph investigation studio — Entity Projection over Datasets rendered on the shared G6 host, with layout/algorithm toolboxes and saved Link-Analysis Views.
resource: inspecto-ui/src/app/modules/admin/studio/link-analysis/
tags: [feature, studio, graph, entity, link, g6, investigation]
timestamp: 2026-07-07T00:00:00Z
---

# Link Analysis

The Builder-lens studio at `/studio/link-analysis` for graph investigation. Keep the four graph planes
distinct ([`GLOSSARY.md`](../../../GLOSSARY.md) §11): this studio works on **P3 — Entity/Link graphs**
(records as business entities), never on artifact/lineage graphs.

* **Sources** — a **GraphSource** feeds one renderer through one query seam; the P3 source is
  **Entity Projection**: a mapping (not a store) that folds a Dataset's rows into Entities + Links
  (column → source/target Entity, optional columns → Link type/attributes).
* **Rendering** — the shared G6 host (`src/app/inspecto/graph/`), reused by the Catalog graph and the
  Geo co-location bridge. Nodes are canvas-drawn — verify inspector logic in unit tests, not preview clicks.
* **Toolboxes** — Layout (11 G6 layouts; tree shapes gated to acyclic data) and Algorithm (Louvain
  community detection, graph pattern matching), plus paths/neighborhood/centrality analysis.
* **Saved investigations** — a **Link-Analysis View** (Component kind `link-analysis-view`) via the
  shared `inspecto/investigation` lib; when its source is `entity-projection` it is renderable as a
  **Widget** (a Graph Visualization Type bound to a Dataset).
* **Status** — UI shipped mock-first; the backend Entity Projection over real Datasets is open
  (REQUIREMENTS INV-1).

Design (archived):
[`link-analysis-and-graphsource.md`](../../../archived-documents/plans-archive/link-analysis-and-graphsource.md) ·
plans: [`link-analysis-studio-plan.md`](../../../archived-documents/plans-archive/link-analysis-studio-plan.md)
(§6–7 carry the open V1/V2 backlog),
[`link-analysis-toolboxes-plan.md`](../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).
