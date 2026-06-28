---
type: Convention
title: API & Data Integration
description: Service per resource in inspecto/api, apiUrl/toParams, blob downloads, secrets, the auth-free core.
resource: inspecto-ui/src/app/inspecto/api/api-base.ts
tags: [api, http, services, downloads, secrets, auth]
timestamp: 2026-06-28T00:00:00Z
---

# API & Data Integration

* **Service per resource** in `inspecto/api/`, `@Injectable({providedIn:'root'})`, `private http = inject(HttpClient)`, returning `Observable<T>`. Build URLs with `apiUrl('/path')`, query with `toParams({…})` (both `api-base.ts`). Declare interfaces inline. **Export from the `index.ts` barrel** (`import { X } from 'app/inspecto/api'`). See the [API services catalog](../services/api-services.md).
* **Downloads** (CSV/blob) go through `HttpClient` (responseType `blob`/`text`) + an object URL — a plain `<a href>` to the API skips the token and 401s in edition builds.
* **Live tail / polling** uses `visibleInterval(ms)` / `auto-refresh.ts` (pauses when the tab is hidden); unsubscribe in `ngOnDestroy`/`takeUntilDestroyed`.
* **Secrets**: references only (`${ENV:…}`); never echo a raw secret back to the server — `***` sentinel means "keep stored value".

## Auth-free core

There is **no auth** in the core / Personal edition (removed 2026-06-16): no `authInterceptor`, `TokenStore`,
`InspectoAuthService`, route guard, or `/connect` screen. Requests carry no bearer; the app boots straight to
`/dashboard`. Don't reintroduce per-screen auth or `canControl`/`canAssist` gating. Standard/Enterprise
editions re-add auth out-of-band via the security module + an `Authenticator` SPI (not in this tree).

See [errors & connectivity](errors-and-connectivity.md) for the error interceptor, [multi-space](multi-space.md)
for request scoping, and [mock backends](mock-backends.md) for offline operation.
