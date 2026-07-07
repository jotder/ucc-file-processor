---
type: Feature
title: KPI & Reports
description: The operational KPI tiles + Reports gallery — run health, freshness, SLA — kept distinct from analytical Dashboards.
resource: inspecto-ui/src/app/modules/admin/kpi-reports/
tags: [feature, kpi, report, operations]
timestamp: 2026-07-07T00:00:00Z
---

# KPI & Reports

The gallery of **operational** reporting: **KPIs** (single-number Measures with a target/threshold,
rendered as headline tiles mini → standard → max) and **Reports** (run health, freshness, SLA).
Vocabulary ([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Report** is an operational deliverable —
distinct from the analytical **Dashboards** authored in the [Studio](studio.md); a BI aggregation is a
**Measure**, never a "metric".

* KPI tiles bind to Datasets/Result Sets like any Widget; the gallery lists and previews them.
* Scheduled report/export **delivery** is not built yet (REQUIREMENTS BI-4).
