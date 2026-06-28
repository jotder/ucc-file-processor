---
type: Feature
title: Dashboard
description: The default landing screen — operational KPIs and charts.
resource: inspecto-ui/src/app/modules/admin/dashboard/dashboard.routes.ts
tags: [feature, dashboard, kpi, charts]
timestamp: 2026-06-28T00:00:00Z
---

# Dashboard

Route `/dashboard` (the default route). The landing page: operational KPI tiles and trend
[charts](../design-system/chart.md), backed by `AcquisitionMetricsService` / `ReportsService`. Independent
fetches degrade gracefully (one failing call doesn't blank the page).
