---
type: Feature
title: Pipeline Detail
description: One pipeline's files and audit grids, with a batch-detail dialog. Carries a breadcrumb.
resource: inspecto-ui/src/app/modules/admin/pipeline-detail/pipeline-detail.routes.ts
tags: [feature, pipelines, detail, breadcrumb]
timestamp: 2026-06-28T00:00:00Z
---

# Pipeline Detail

Route `/pipelines/:id` (carries a list→id [breadcrumb](../conventions/routing-and-navigation.md)). Shows a
pipeline's files and audit records in **standard** [data-tables](../design-system/data-table.md); a row opens
the **batch-detail dialog** (mini/single-select grids inside). Backed by `PipelinesService`.
