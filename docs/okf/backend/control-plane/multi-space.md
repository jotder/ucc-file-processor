---
type: Concept
title: Multi-Space Hosting
description: SpaceManager → SpaceContext → CollectorService (wrapped, not rewritten); MDC-based singleton isolation; the /spaces seam.
resource: inspecto/src/main/java/com/gamma/service/SpaceManager.java
tags: [control-plane, multi-space, isolation, mdc, spaces]
timestamp: 2026-07-07T00:00:00Z
---

# Multi-Space Hosting

One server hosts many isolated **spaces** (projects). The ~40-method per-instance `CollectorService` is
**wrapped, not rewritten**.

* `SpaceManager` (`inspecto/src/main/java/com/gamma/service/SpaceManager.java`) — `single(CollectorService)`
  (single-tenant, byte-identical to pre-multi-space) or `discover(spacesRoot)` (boots each `spaces/<id>/`
  with a `config/` subtree). Runtime CRUD with no restart: `create`, `createFromBundle`, `delete(id, purge)`,
  serialized on `lifecycleLock` (reads are lock-free).
* `SpaceContext` (`…/service/SpaceContext.java`) — a thin per-space holder (id + `SpaceRoot` + manifest + its
  own unchanged `CollectorService`).
* `SpaceMigrator` (`…/service/SpaceMigrator.java`) — migrates a space's DuckDB/state stores on boot.

## Singleton isolation via the `space` MDC

Five process-wide singletons — `EventLog`, `MetricRegistry` (its `space` label),
[`ConnectionRegistry`](../acquisition/connectors.md), `StabilityGate`, `AcquisitionLedgers` — resolve per-space
by reading `EventLog.currentSpaceId()`, which returns the `space` **SLF4J MDC** key or `"default"` when
absent. **The MDC does not cross thread-pool boundaries**, so every executor on the execution path must copy
the MDC onto its worker (see [gotchas](../gotchas/cross-cutting.md)). The `default` space sets no MDC → output
stays label-free (byte-identical single-space).

## API seam

`/spaces/{id}/…` is stripped + MDC-bound by [`ControlApi.dispatch`](control-api.md); un-prefixed paths use the
current/default space. In versioned URLs the space segment sits **after** `/v1`
(`/api/v1/spaces/<id>/…` — dispatch strips `/api/v1` first, then matches `/spaces/{id}/…`). Space CRUD
(server-global, un-prefixed): `GET/POST /spaces`, `DELETE /spaces/{id}?purge=`, and
`GET /spaces/_meta → {multiSpace}` (the UI capability probe — never infer from the space-list length). The UI
side is the [spaces feature](../../frontend/features/spaces.md) + the global
[space interceptor](../../frontend/conventions/multi-space.md).
