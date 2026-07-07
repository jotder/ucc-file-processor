---
type: Feature
title: Dashboard
description: The default landing screen — operational KPIs and charts; BI dashboards (filter bar, drill-through, time grain, PNG export) live in the Studio builder.
resource: inspecto-ui/src/app/modules/admin/dashboard/dashboard.routes.ts
tags: [feature, dashboard, kpi, charts, studio-bi]
timestamp: 2026-07-07T00:00:00Z
---

# Dashboard

Route `/dashboard` (the default route). The landing page: operational KPI tiles and trend
[charts](../design-system/chart.md), backed by `AcquisitionMetricsService` / `ReportsService`. Independent
fetches degrade gracefully (one failing call doesn't blank the page).

**BI dashboards** (the studio-bi-improvements pass) are authored and viewed in the **Studio Dashboard
Builder** (`/studio/dashboards`, `modules/admin/studio/dashboards/`), which adds a **quick-filter bar**
(`dashboard-filter-bar`), a **drill-through drawer** (`dashboard-drill-drawer`), a **time grain** control
(shared `inspecto/viz/time-grain.ts`), and **PNG export** of tiles (`exportPngs()` in the dashboard editor).
