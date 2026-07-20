---
type: Feature
title: Run Detail
description: One running Pipeline's batches, files, lineage, quarantine, commits, and report tabs, with a batch-detail dialog. Carries a breadcrumb.
resource: inspecto-ui/src/app/modules/admin/run-detail/run-detail.routes.ts
tags: [feature, runs, detail, breadcrumb]
timestamp: 2026-07-07T00:00:00Z
---

# Run Detail

Route `/runs/:name` (carries a list→name [breadcrumb](../conventions/routing-and-navigation.md)), dir
`modules/admin/run-detail/`. Drills into one running Pipeline across tabs — batches / files / lineage
(filterable by batch id) / quarantine / commits / report — in **standard**
[data-tables](../design-system/data-table.md); a row opens the **batch-detail dialog** (mini/single-select
grids inside). Backed by `RunsService`.

**Quarantine remediation (D-ETL, shipped 2026-07-20):** the Quarantine tab lists `GET /runs/{name}/quarantine`
rows (file name, batch id, reason, timestamp) via the same generic audit-row grid; each row's row-action set
now includes **Reprocess this batch** (previously Batches-tab only) alongside **Lineage & details**, gated
behind `lens.canOperateRuns()` like every other Runs mutation. Reprocess always asks
`InspectoConfirmService.confirm()` before calling `POST /runs/{name}/reprocess {batchId}` — a real mutating
action needs an explicit step, unlike the read-only tabs. On success the Quarantine tab reloads so a
successfully reprocessed batch drops off the list. Remaining known gap: no record-level replay — reprocess is
still whole-batch only (tracked separately if ever prioritized).
