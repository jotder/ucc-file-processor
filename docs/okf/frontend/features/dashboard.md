---
type: Feature
title: Dashboard
description: The default landing screen — operational KPIs and charts; BI dashboards (filter bar, drill-through, time grain, PNG export) live in the Studio builder.
resource: inspecto-ui/src/app/modules/admin/dashboard/dashboard.routes.ts
tags: [feature, dashboard, kpi, charts, studio-bi]
timestamp: 2026-07-07T00:00:00Z
---

# Dashboard

Route `/overview` (renamed from `/dashboard` 2026-07-07 to stop colliding with Studio's BI Dashboards;
`/dashboard` redirects). No longer any lens's default landing: the root `''` route redirects per lens via
`LENS_HOME` (business → `kpi-reports`, builder → `pipelines`, ops → `events`; function-form
`redirectTo` in `app.routes.ts`). The operational snapshot page: health tiles and trend
[charts](../design-system/chart.md), backed by `AcquisitionMetricsService` / `ReportsService`. Independent
fetches degrade gracefully (one failing call doesn't blank the page).

**BI dashboards** (the studio-bi-improvements pass) are authored and viewed in the **Studio Dashboard
Builder** (`/studio/dashboards`, `modules/admin/studio/dashboards/`), which adds a **quick-filter bar**
(`dashboard-filter-bar`), a **drill-through drawer** (`dashboard-drill-drawer`), a **time grain** control
(shared `inspecto/viz/time-grain.ts`), and **PNG export** of tiles (`exportPngs()` in the dashboard editor).
