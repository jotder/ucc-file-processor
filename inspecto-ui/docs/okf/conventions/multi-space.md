---
type: Convention
title: Multi-Space Scoping
description: SpacesService holds the active space; the spaceInterceptor rewrites API paths so features stay space-agnostic.
resource: inspecto-ui/src/app/inspecto/api/space.interceptor.ts
tags: [multi-space, multi-tenant, interceptor, spaces]
timestamp: 2026-06-28T00:00:00Z
---

# Multi-Space Scoping

The server hosts many isolated **spaces** (projects). Do **not** re-roll per-space logic in features.

* `SpacesService` (`inspecto/api`) holds the active space as a signal (`currentSpaceId`, restored from `localStorage` in its ctor).
* The global `spaceInterceptor` rewrites `/api/<path>` → `/api/spaces/<id>/<path>` for every feature call — so feature services stay **space-agnostic**.
* It **no-ops** when there's no active space (single-tenant, byte-identical) and **exempts** server-global / already-scoped paths (`/health`, `/ready`, `/metrics`, `/spaces*`).
* Detect the mode via `GET /spaces/_meta` → `{multiSpace}` (never infer from the space-list length).
* The header **space-switcher** and the [spaces admin view](../features/spaces.md) are the only space-aware UI; switching reloads.
