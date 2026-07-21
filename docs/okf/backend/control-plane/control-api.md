---
type: Concept
title: HTTP Control API
description: The JDK HttpServer control plane — manual DI, virtual-thread requests, the dispatch seam, route families, the /api/v1 surface, editions-aware auth.
resource: inspecto/src/main/java/com/gamma/control/ControlApi.java
tags: [control-plane, http, api, routes, v1, editions]
timestamp: 2026-07-07T00:00:00Z
---

# HTTP Control API

`ControlApi` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`) is the control plane. It builds a
`com.sun.net.httpserver.HttpServer` (JDK built-in, zero added deps), sets a
`newVirtualThreadPerTaskExecutor()` (a fresh virtual thread per request), registers routes, and binds a
single catch-all `dispatch` context on `/`.

## Launch

`ControlApi.main` is the server entry: if `-Dspaces.root` is set it `SpaceManager.discover(...)`s, else it
builds a single `SourceService` wrapped via `SpaceManager.single(...)`. Port from `-Dcontrol.port` (default
8080); a shutdown hook closes the API + spaces. (`com.gamma.inspector.MainApp` is a **separate** CLI pre-ETL tool
suite, not the server — see [build & run](../build-run/operations.md).)

## `dispatch`

Per request: strip an optional `/api` prefix (Angular dev-proxy); apply CORS if `-Dcontrol.cors` set; answer
`OPTIONS` preflight; match the `/spaces/{id}/…` seam (extract + validate id, rewrite path, bind the `space`
MDC — see [multi-space](multi-space.md)); match `routes` by pattern+method; fall through to static SPA assets
for unmatched GETs (`-Dui.dir`); always clear the space MDC in `finally`.

## Route families

Registered via `RouteModule`s: health/ready (`/health`,`/ready`), metrics (`/metrics`, Prometheus text),
spaces (`/spaces`,`/spaces/_meta`), pipelines, jobs (`/jobs/{name}/runs|trigger`), events
(`/events/search|export|views`), connections, components ([registry](../components/component-registry.md)),
objects (ops), catalog, config/assist, enrichment, per-space settings docs (`/settings/branding|geo` and
`/nav/menus` — the Menu Builder tree; each a fixed-filename TOON in the space's config tree, PUT gated by
write-root 503 + `canAuthorWorkbench`, no jail/conflict gates since nothing caller-supplied touches a path)
— plus the v1-era additions: `GET /bootstrap`
(server capabilities incl. `features.authMode`), `/auth/*` (the Standard-edition BFF session routes),
`POST /queries/{id}/run` (the `com.gamma.query` catalog, W4), and async run polling
(`GET /jobs/runs/{runId}`, `GET /runs/runs/{runId}`).

## `/api/v1`

Every business route is also dispatched under the versioned **`/api/v1`** prefix with a success/error
**envelope** (structured errors from the `ErrorCodes` catalog), `WriteGates`, a `Correlation-ID` response
header, and gzip (W1). Legacy unversioned routes stay **byte-for-byte unchanged**; their use is counted by
`ControlApi.recordLegacyUsage` (see [events & metrics](events-metrics.md)). Contract detail:
[API v1](api-v1.md).

## Auth by edition

The core/Personal edition is **auth-free**: every route is open — no token, guard, or login. On **Standard**
the `inspecto-security` module (reactor-gated behind the `edition-standard` Maven profile) enforces OIDC by
implementing the `Authenticator` / `Subject` / `TokenRelay` SPIs in `com.gamma.control` (see
[auth & security](../editions/auth-security.md)). The `-Dassist.write.root` 503 write-gate is **separate** from
auth and stays in all editions.
