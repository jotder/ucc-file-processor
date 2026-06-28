---
type: Feature
title: Enrichment
description: Stage-2 enrichment jobs with a detail panel — runs, lineage, and a rollup report.
resource: inspecto-ui/src/app/modules/admin/enrichment/enrichment.routes.ts
tags: [feature, enrichment, operations]
timestamp: 2026-06-28T00:00:00Z
---

# Enrichment

Route `/enrichment` (Operations nav group). A **standard**, single-select [data-table](../design-system/data-table.md)
of stage-2 jobs; selecting a job opens a detail panel with tabs: **Runs** / **Lineage** (filter by run id) /
**Report** (a date-range rollup with percentile stats). The runs/lineage rows render in a **pro** data-table;
the report is a stat table. Backed by `EnrichmentService`; offline via the `mockOps`
[interceptor](../conventions/mock-backends.md).
