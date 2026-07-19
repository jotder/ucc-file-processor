---
type: Feature
title: Reconciliation
description: Dataset-vs-Dataset (and 3-way) reconciliation — Board aggregate tree with banded Δ%, Breaks drill with live record sets, usable as a Widget.
resource: inspecto-ui/src/app/modules/admin/reconciliation/
tags: [feature, reconciliation, breaks, board, tree-table, dat-7]
timestamp: 2026-07-16T00:00:00Z
---

# Reconciliation

Route `/reconciliation` (Business + Builder lenses). Vocabulary is locked
([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Reconciliation** compares **Datasets** on key columns
with per-column tolerances; a **Break** is `missing_left | missing_right | value_break` with an
`open/resolved/auto_closed` lifecycle (auto-close on re-match within tolerance). Never a parallel
"comparison" concept.

* **Board** (`:id` default view) — the aggregate dimension-order tree on
  [`inspecto-tree-table`](../design-system/tree-table.md): unified dimension/measure selection, parents
  carry rollups, Δ% columns **banded** ok/warn/breach (defaults 1/2 %; independent of record-level
  tolerance). Δ% is **Anchor-relative** (`datasets[0]`; 0-anchor ⇒ exact).
* **Breaks page** — drill from a Board cell to the three live record sets (only-in-A / only-in-B /
  common-but-different) with path scoping; 3-way adds a **Presence Pattern** filter bar.
* **3-way** — N=3 anchor reconciliation shipped (DAT-7 P4); N>3, non-additive aggs, and fuzzy key
  matching are explicit non-goals for now.
* **Reusable** — a Reconciliation renders as a **Widget** and rides bundle export/template flows
  (DAT-7 P3); persisted as the `reconciliation` component kind with real backend routes
  `/recon/columns|run|breaks` (DAT-7 P0 — the query gate pattern from the Data Browser).
* Runs are manual from the Board (no auto-refresh), **or scheduled**: the `recon.run` built-in Job Type
  (`ReconRunJob`, 2026-07-18) runs a saved `reconciliation` on a `cron:` and emits a `recon.run.completed`
  Signal carrying the Break counts (`WARNING` when any break exists) — it builds the identical
  `ReconService.Spec` the interactive route does, via the shared `ReconConfigLoader`. **A breach
  (`breaks > 0`) also opens a managed `ObjectType.INCIDENT`** (2026-07-19), deduped to one open Incident
  per reconciliation (correlationId = the reconciliation id), reusing the `ExpectationRoutes`
  dedup+open pattern; `ObjectService` reaches `ReconRunJob` via a `Supplier` `JobService` wires
  post-construction (`JobService.objects(...)`, resolved lazily since the built-in is constructed
  before the Object Engine exists). Break-level assignment stays with Cases.

As-built design (archived):
[`reconciliation-board-design.md`](../../../archived-documents/plans-archive/reconciliation-board-design.md) ·
review sheet: [`reviews/reconciliation.md`](../../../superpower/reviews/reconciliation.md).
