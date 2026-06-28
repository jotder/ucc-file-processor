---
type: Concept
title: HTTP Control API
description: The JDK HttpServer control plane — manual DI, virtual-thread requests, the dispatch seam, route families, auth-free.
resource: inspecto/src/main/java/com/gamma/control/ControlApi.java
tags: [control-plane, http, api, routes, auth-free]
timestamp: 2026-06-28T00:00:00Z
---

# HTTP Control API

`ControlApi` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`) is the control plane. It builds a
`com.sun.net.httpserver.HttpServer` (JDK built-in, zero added deps), sets a
`newVirtualThreadPerTaskExecutor()` (a fresh virtual thread per request), registers routes, and binds a
single catch-all `dispatch` context on `/`.

## Launch

`ControlApi.main` is the server entry: if `-Dspaces.root` is set it `SpaceManager.discover(...)`s, else it
builds a single `SourceService` wrapped via `SpaceManager.single(...)`. Port from `-Dcontrol.port` (default
8080); a shutdown hook closes the API + spaces. (`com.gamma.util.MainApp` is a **separate** CLI pre-ETL tool
suite, not the server — see [build & run](../build-run/operations.md).)

## `dispatch`

Per request: strip an optional `/api` prefix (Angular dev-proxy); apply CORS if `-Dcontrol.cors` set; answer
`OPTIONS` preflight; match the `/spaces/{id}/…` seam (extract + validate id, rewrite path, bind the `space`
MDC — see [multi-space](multi-space.md)); match `routes` by pattern+method; fall through to static SPA assets
for unmatched GETs (`-Dui.dir`); always clear the space MDC in `finally`.

## Route families

Registered via `RouteModule`s: health/ready (`/health`,`/ready`), metrics (`/metrics`, Prometheus text),
spaces (`/spaces`,`/spaces/_meta`), pipelines, jobs (`/jobs/{name}/runs|trigger`), events
(`/events/search|export|views`), connections, components + flows ([registry](../components/component-registry.md)),
objects (ops), catalog, config/assist, enrichment.

## Auth-free

In the core/Personal edition **every route is open** — no token, guard, or login. Auth is an edition concern
added out-of-band by the security module behind an `Authenticator` SPI (see
[auth & security](../editions/auth-security.md)). The `-Dassist.write.root` 503 write-gate is **separate** from
auth and stays in all editions.
