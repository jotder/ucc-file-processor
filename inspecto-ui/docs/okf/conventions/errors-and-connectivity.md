---
type: Convention
title: Errors & Connectivity
description: The global error interceptor, the persistent connectivity banner, and per-call toasts.
resource: inspecto-ui/src/app/inspecto/api/error.interceptor.ts
tags: [errors, connectivity, interceptor, offline, toastr]
timestamp: 2026-06-28T00:00:00Z
---

# Errors & Connectivity

## Global error interceptor

`errorInterceptor` (`inspecto/api/error.interceptor.ts`):

* **`status 0`** → `ConnectivityService.reportUnreachable()` (drives the persistent banner). **Do not** add a per-screen "unreachable" toast.
* Any success → `reportReachable()`.
* **`503` is per-screen** (e.g. assist disabled / writes disabled), NOT backend-down.
* No 401 handling — the [core is auth-free](api-and-data.md).

## Per-call errors

Surface `apiErrorMessage(err, fallback)` via `ToastrService`. Independent fetches **degrade gracefully** —
one failing call must not blank the whole page (fetch outside the core `forkJoin`).

## Connectivity banner

`ConnectivityService` (signals) + [`<inspecto-connectivity-banner>`](../design-system/connectivity-banner.md)
(`role="alert"`, Retry → `/health`) is already mounted in `layout.component` (its first child,
`display:contents`). Distinct from the per-screen [`<inspecto-alert>`](../design-system/alert.md).
