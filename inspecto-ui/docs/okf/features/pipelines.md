---
type: Feature
title: Pipelines
description: The pipeline list with run/history actions; rows open the pipeline detail.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipelines.routes.ts
tags: [feature, pipelines]
timestamp: 2026-06-28T00:00:00Z
---

# Pipelines

Route `/pipelines` (Pipelines nav group). Lists configured pipelines in a **standard**
[data-table](../design-system/data-table.md) (`autoHeight`) with per-row run/history actions; row-click opens
[pipeline detail](pipeline-detail.md). Backed by `PipelinesService`.
