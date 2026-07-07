---
type: Convention
title: Routing & Navigation
description: Lazy feature routes, the nav data file, breadcrumbs, and the client-side global search.
resource: inspecto-ui/src/app/app.routes.ts
tags: [routing, navigation, lazy-loading, breadcrumbs]
timestamp: 2026-06-28T00:00:00Z
---

# Routing & Navigation

* **Lazy-load every feature route**: `{ path, loadChildren: () => import('app/modules/admin/<f>/<f>.routes') }` in `app.routes.ts` (no auth guard — the core is auth-free). Each `<f>.routes.ts` is `export default [...] as Routes`. Default route → `dashboard`.
* **Adding a page = two edits**: the lazy route in `app.routes.ts` **and** the nav item in `mock-api/common/navigation/data.ts`.
* **Nav groups**: **Operations** · **Platform** (Workbench / Studio / Catalog) · **Settings**, plus Dashboard + Assistant — filtered by the active **Lens**. See the [features index](../features) for the screen-to-group mapping.
* **Detail pages carry a breadcrumb** (list → id), e.g. [run-detail](../features/run-detail.md).
* **Global search** (`layout/common/search`) is a client-side jump-to-page palette over the nav — **not** a backend search.

## Pane reuse across routes

A single component can serve multiple routes via `ActivatedRoute.snapshot.data`. Example:
[Cases and Issues](../features/objects.md) are one `ObjectsComponent` parameterized by route data
(`cases.routes.ts` / `issues.routes.ts`).
