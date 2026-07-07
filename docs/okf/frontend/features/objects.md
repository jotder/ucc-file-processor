---
type: Feature
title: Cases & Issues (Objects)
description: Operational objects — one ObjectsComponent serving two routes (cases, issues) via route data.
resource: inspecto-ui/src/app/modules/admin/objects/cases.routes.ts
tags: [feature, objects, cases, issues, operations, pro, pane-reuse]
timestamp: 2026-06-28T00:00:00Z
---

# Cases & Issues (Objects)

Routes `/cases` and `/issues` (Operations nav group) — a single `ObjectsComponent` parameterized by
`ActivatedRoute.snapshot.data` (`cases.routes.ts` / `issues.routes.ts`), the canonical
[pane-reuse pattern](../conventions/routing-and-navigation.md). Lists operational objects in a **pro**
[data-table](../design-system/data-table.md) with eye/advance row actions. Backed by `ObjectsService`; offline
via the `mockOps` [interceptor](../conventions/mock-backends.md).
