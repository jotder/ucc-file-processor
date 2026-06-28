---
type: Feature
title: Alerts
description: Fired operational alerts in a pro data-table.
resource: inspecto-ui/src/app/modules/admin/alerts/alerts.routes.ts
tags: [feature, alerts, operations, pro]
timestamp: 2026-06-28T00:00:00Z
---

# Alerts

Route `/alerts` (Operations nav group). Lists fired alerts in a **pro** [data-table](../design-system/data-table.md)
(offline SQL editor + filter builder + the option to save a [rule](../design-system/rule.md)). Backed by
`AlertsService`; offline via the `mockOps` [interceptor](../conventions/mock-backends.md).
