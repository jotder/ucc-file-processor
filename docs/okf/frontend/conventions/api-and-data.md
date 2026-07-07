---
type: Convention
title: API & Data Integration
description: Service per resource in inspecto/api, apiUrl → /api/v1, the v1Interceptor envelope unwrap, blob downloads, secrets, auth by edition.
resource: inspecto-ui/src/app/inspecto/api/api-base.ts
tags: [api, http, services, v1, interceptors, downloads, secrets, auth]
timestamp: 2026-07-07T00:00:00Z
---

# API & Data Integration

* **Service per resource** in `inspecto/api/`, `@Injectable({providedIn:'root'})`, `private http = inject(HttpClient)`, returning `Observable<T>`. Build URLs with `apiUrl('/path')`, query with `toParams({…})` (both `api-base.ts`). Declare interfaces inline. **Export from the `index.ts` barrel** (`import { X } from 'app/inspecto/api'`). See the [API services catalog](../services/api-services.md).
* **Downloads** (CSV/blob) go through `HttpClient` (responseType `blob`/`text`) + an object URL — a plain `<a href>` to the API skips the token and 401s in edition builds.
* **Live tail / polling** uses `visibleInterval(ms)` / `auto-refresh.ts` (pauses when the tab is hidden); unsubscribe in `ngOnDestroy`/`takeUntilDestroyed`.
* **Secrets**: references only (`${ENV:…}`); never echo a raw secret back to the server — `***` sentinel means "keep stored value".

## The `/api/v1` surface

Per `api-base.ts`'s own header comment: `apiUrl()` prefixes the configured base (`''` in prod, `/api` behind
the dev proxy) **plus the `/v1` version segment** — every route is dispatched under `/api/v1` with envelope
shaping. The **first-position `v1Interceptor`** unwraps the success envelope (shape-guarded — non-envelope
bodies pass through untouched), so **services keep their raw-DTO signatures**. v1 errors arrive as
`{ error: { errorCode, message, … } }`; `apiErrorMessage()` handles both that and the legacy
`{ error: 'msg' }` shape. The `spaceInterceptor` then inserts the space id **after** `/v1` (see
[multi-space](multi-space.md)).

## Auth by edition

`auth.interceptor.ts` **exists** but the OIDC login flow is a **no-op on Personal** — it is driven by
`GET /bootstrap` → `features.authMode` (`none` on Personal, `oidc` on Standard, where the backend's
`inspecto-security` module enforces it). Don't reintroduce per-screen auth or `canControl`/`canAssist`
gating in features — auth stays at the interceptor/edition seam.

See [errors & connectivity](errors-and-connectivity.md) for the error interceptor, [multi-space](multi-space.md)
for request scoping, and [mock backends](mock-backends.md) for offline operation.
