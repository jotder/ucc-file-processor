# W7 — UI migration to `/api/v1` (global flip)

> Approved 2026-07-07 (user: global flip over context-by-context). Worklog row:
> `docs/superpower/api-contract-design.md` §10 W7.

## Context

W7 is the last open slice of the API-contracts-v1 track: move the Angular UI off the legacy
un-enveloped routes onto the versioned `/api/v1` envelope contract (`docs/api/openapi-v1.json`).
Research findings that shaped the approach:

- **Backend is fully generic already** — `ControlApi.dispatch` (ControlApi.java:355-382) detects and
  strips the `/api/v1` prefix and matches the *same* route table; `ApiContext.respondJson` branches
  into `Envelope.shape(...)` (Envelope.java). Every legacy route already serves under `/api/v1`.
  Nothing is blocked on backend work; legacy responses are pinned byte-for-byte unchanged
  (`ControlApiV1Test`).
- **v1 success shape** `{data, metadata, diagnostics, links?, permissions?}`; **error shape**
  `{error: {errorCode, message, recoverable, correlationId, details?}}`.
- **UI today**: 28 services / ~150 `apiUrl()` call sites, all legacy shapes; errors parsed as
  `err.error.error` (string) in `apiErrorMessage` (api-base.ts:19-29). Interceptor chain
  (app.config.ts:41): `[mockApiInterceptor, spaceInterceptor, authInterceptor, errorInterceptor]` —
  mock runs first and short-circuits.
- **X-Actor is not sent by the UI** — its retirement is backend-only, out of this slice.
- **Fallout is small**: 7 spec files hardcode `/api/...` URLs; mock handler regexes are un-prefixed
  (match `/api/v1/...` unchanged); no EventSource/SSE bypasses; no ETag-header dependencies in
  services today.

**User decision (2026-07-07): global flip in one slice** (not context-by-context) — the envelope
unwrap happens at one interceptor seam so all 28 services keep their signatures; rollback = revert
`apiUrl()`.

## Changes (all in `inspecto-ui/` unless noted)

### 1. TS contract types — new `src/app/inspecto/api/v1.ts`
`V1Envelope<T>` (`data`, `metadata{timestamp, apiVersion, durationMs?, etag?, pagination?, warnings?}`,
`diagnostics{correlationId}`, `links?`, `permissions?`), `V1ErrorObject`, `V1ErrorCode` (12-value union
mirroring `ErrorCodes.java` / openapi-v1.json), plus an `isV1Envelope(body)` shape guard
(`data` + `metadata.apiVersion === 'v1'` present). Export via the `index.ts` barrel.

### 2. Base path — `api-base.ts`
`apiUrl(path)` → `${environment.apiBaseUrl}/v1${path}`. (No new environment key needed; keep the
`/api` base + dev proxy untouched — `/api/v1` still routes through it.)

### 3. Envelope unwrap — new `v1.interceptor.ts`, FIRST in the chain
`withInterceptors([v1Interceptor, mockApiInterceptor, spaceInterceptor, authInterceptor, errorInterceptor])`
(app.config.ts). Response-side only: if `HttpResponse` body passes `isV1Envelope`, `clone({body: body.data})`.
Shape-guarded, so text (`/metrics` Prometheus), blobs (CSV downloads), 304s (empty body) and any
non-enveloped reply pass through untouched. First position = it also unwraps mock short-circuit
responses. No request-side logic.

### 4. Error parsing — `apiErrorMessage` (api-base.ts)
Handle both shapes: legacy `body.error` string (kept — mock cache/old fallbacks) and v1
`body.error.message` object; expose `errorCode` for callers that want it (optional second helper
`apiErrorCode(err)` only if a caller needs it — do not speculate).
`error.interceptor.ts` unchanged (status-0 connectivity only).

### 5. Space scoping — `space.interceptor.ts`
Teach it the v1 prefix: if the URL starts with `${base}/v1/`, compute `rest` after `/v1` and rewrite to
`${base}/v1/spaces/${id}${rest}` (backend strips `/api/v1` first, then matches `/spaces/{id}/...`).
Keep the legacy `/api/` branch working (vendored/non-apiUrl callers). SERVER_GLOBAL list unchanged.

### 6. Auth interceptor — `auth.interceptor.ts`
Check its `/auth/*` path matching (exempting exchange/refresh from bearer/401-refresh loops) still
matches the `/api/v1/auth/...` form; adjust the match if it's prefix-sensitive.

### 7. Mock layer speaks v1 — `mock-api.interceptor.ts` (+ small helper in `mock-http.ts`)
Mirror the backend's response-edge seam so all 12 handlers stay untouched (they keep returning raw
DTOs, exactly like backend route handlers):
- success short-circuit (line 79): wrap `res.body` in a v1 envelope (`timestamp`, `apiVersion:'v1'`,
  `diagnostics.correlationId: 'mock-…'`).
- error short-circuit (line 76): lift `{error: 'msg', ...extras}` into
  `{error: {errorCode: defaultFor(status), message, recoverable: status !== 500, correlationId, details?}}` —
  port of `Envelope.error()` incl. the status→code default map (404→NOT_FOUND, 409→CONFLICT,
  422→CONFIG_VALIDATION_FAILED, 503→CAPABILITY_UNAVAILABLE, …).
- Handler URL matching needs no change (regexes are relative). MockRequest keeps the raw URL.

### 8. Spec fallout — 7 files
Update hardcoded `${base}/...` expectations to `${base}/v1/...` (or switch them to build via
`apiUrl()`): alerts / assist / lineage / runs / session / spaces `.service.spec.ts` +
`auth.interceptor.spec.ts`. `space.interceptor.spec.ts` gains v1-form cases. New specs:
`v1.interceptor.spec.ts` (unwrap + pass-through guards: text, blob, non-envelope JSON, error channel)
and mock-envelope cases (one success + one error through the mock interceptor asserting v1 shapes).

### 9. Docs
- `docs/superpower/api-contract-design.md` §10: mark W7 shipped with stated deviations (global flip
  not context-by-context — generic dispatch made per-context migration pointless; X-Actor already
  absent from UI).
- **Deliberately deferred, noted in the worklog**: backend legacy-alias usage logging + sunset, and
  the ADVANCED_GUIDE Control-API regen — retirement work starts after the UI has proven v1 in anger.

## Not in scope
Per-service typed metadata (pagination/etag/permissions consumption), backend legacy-route retirement,
ADVANCED_GUIDE regen, X-Actor backend rejection, `permissions[]` wiring into LensService (capability
signals already work off bootstrap).

## Verification (angular-ui DoD)
1. `npm run lint:tokens` · `npm run build` · `npm run test:ci` (1039 baseline + new specs, 0 fail).
2. Preview (launch.json dev server, offline/mock mode): app boots login-free (bootstrap enveloped →
   unwrapped), dashboard + a grid pane render, one mutation round-trips (e.g. job toggle), one error
   path surfaces a readable toast message (mock 4xx → v1 ErrorObject → `apiErrorMessage`);
   `preview_console_logs` clean.
3. Optional live check: boot the real backend (build-verify skill) and hit one route through the dev
   proxy to confirm real-envelope unwrap end-to-end.
4. Commit: `feat(ui): W7 UI migration to /api/v1 envelope` — master-only (feat), push only on
   explicit ask.
