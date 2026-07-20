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
* **Scheduled exports** (C6, 2026-07-04): a schedule IS a [Job](jobs.md) — `type: 'report'` with
  `params: {reportKind, dashboardId, format, recipients}`; no separate entity. Dispatch keys on
  `params.dashboardId` presence, *not* on `type === 'report'` (that type predates C6 and covers other
  report jobs). CSV export is real (serializes the dashboard's tiles); PNG is real since BI-4 (backend
  `TablePngRenderer`, JDK-native Graphics2D — a 50-row table snapshot; CSV stays the full export).
  **PDF shipped 2026-07-20** as the backlog's PNG-wrapped-in-PDF fallback (no PDF library on the
  classpath, offline-blocked — see `PdfRenderer`, `inspecto/src/main/java/com/gamma/job/`): reuses
  `TablePngRenderer.renderImage` for layout, then hand-writes a minimal single-page PDF (one Image
  XObject, FlateDecode + DeviceRGB, no text layer/fonts) — a snapshot like PNG, not a general-purpose
  PDF export. `ReportJob` dispatches `format: pdf` alongside `csv`/`png`/`json`.
* This pane is the **Business lens's home route** (`LENS_HOME.business = 'kpi-reports'`).
