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
