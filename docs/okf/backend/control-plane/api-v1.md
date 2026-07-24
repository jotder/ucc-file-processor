---
type: Concept
title: Versioned API (/api/v1)
description: The v1 business contract — response envelope, error-code catalog, ETag/contentHash concurrency, bootstrap, async runs, OpenAPI enforcement.
resource: inspecto/src/main/java/com/gamma/control/ControlApi.java
tags: [control-plane, api, v1, envelope, etag, openapi, async]
timestamp: 2026-07-07T00:00:00Z
---

# Versioned API (/api/v1)

The Control API's stable, versioned **business contract** (W1–W8, shipped 2026-07-06/08), designed so a
WSO2-style gateway + external IAM can front it later without reshaping routes. Design of record:
[`api-contract-design.md`](../../../archived-documents/plans-archive/api-contract-design.md) (§10 worklog).

* **Envelope + errors (W1)** — every `/api/v1` response is enveloped; errors carry a machine-readable
  `errorCode` from the catalog in `ErrorCodes.java`; authenticated envelopes include `permissions[]`.
  Write routes pass through fail-closed `WriteGates`. Every response carries a `Correlation-ID`;
  responses are gzip-compressed when accepted.
* **Legacy compatibility + sunset (W8, API-5)** — the unversioned routes stay **byte-for-byte unchanged**;
  each legacy hit is counted by `inspecto_legacy_api_requests_total{route}`
  (see [events & metrics](events-metrics.md)). Legacy responses carry `Deprecation` +
  `Link: rel="successor-version"` headers; `-Dapi.legacy.sunset=YYYY-MM-DD` adds a `Sunset` header, and
  `-Dapi.legacy.routes=off` flips the whole unversioned surface to **410 Gone** (infra probes exempt; the
  metric keeps counting). Runbook: soak to 30 consecutive days at zero → flip `off` → physical route deletion
  one release later. Physical removal is the only part still outstanding, deliberately soak-gated.
* **Per-resource `permissions[]` (SEC-7b)** — a single-resource handler declares its applicable capability
  set via `ApiContext.resourcePermissions(ex, Set)`; `Envelope.success` emits
  `subject.capabilities() ∩ applicable` (fail-closed affordance signal, never the security boundary —
  enforcement stays `requireCapability` on writes). No declaration ⇒ session-wide array; Personal (no
  Subject) ⇒ no `permissions` key at all. List-row permissions and stored per-object ACLs are deliberately
  out of scope (see [auth & security](../editions/auth-security.md)).
* **OpenAPI-first (W2)** — the contract lives at [`openapi-v1.json`](../../../api/openapi-v1.json)
  (+ canonical examples) and is **enforced** by `ApiContractTest` against `ErrorCodes.java` and the live
  server.
* **Optimistic concurrency (W3)** — Components carry a `ContentHash` (parity-pinned with the UI's
  `content-hash.ts`); reads return `ETag`, conditional reads honor `If-None-Match`, writes require
  `If-Match`. See [component registry](../components/component-registry.md). The read-side idiom is a
  one-line `ETags.respond(ex, body)` wrapper (`ETags.java`) — hash the body → `If-None-Match` 304 →
  set the header → return body-or-`HANDLED`; the hash captures any body variance, so a changed body
  never yields a false 304. **Extended 2026-07-24** beyond `/bootstrap` + `GET /components/{type}/{id}`
  to the per-space authored config/metadata singleton documents the UI re-reads on load/space-switch:
  `GET /nav/menus`, `/settings/branding`, `/settings/geo`, `/config/icon-map`, and
  `/access/roles|policies|catalog|profiles`. (List/paginated routes are deliberately left out — the
  cursor page varies per query; further singleton reads can adopt `ETags.respond` as demanded.)
* **Bootstrap (W3/W6)** — `GET /bootstrap` returns the metadata-first boot document, including
  `features.authMode`, which drives the UI's OIDC flow (no-op on Personal).
* **Queries (W4)** — the query catalog + `POST /queries/{id}/run`; see [queries](queries.md).
* **Async runs (W5/W5b)** — job triggers *and* pipeline triggers return **`202` + `{runId, status…}` +
  `Location`**; poll the run by id; `Idempotency-Key` gives at-most-once replay. See [jobs](jobs.md).
* **AuthN/AuthZ seam (W6)** — the `Authenticator`/`Subject`/`TokenRelay` SPIs gate v1 routes on Standard;
  see [auth & security](../editions/auth-security.md).
* **Cursor pagination (§7)** — list routes expose opaque keyset cursors via `metadata.pagination`
  (`{cursor, nextCursor, limit, total}`). A route declares the block with `ApiContext.pagination(ex, …)`
  (mirrors the `resourcePermissions`/`ATTR_SELF_PATH` attribute seam) and `Envelope` emits it on v1
  responses only; the opaque token is URL-safe-Base64 over the JSON keyset (`com.gamma.control.Cursor`,
  decode-total — a garbage cursor means "from the top", never a 400). First adopter: `GET /jobs/runs`
  over the DuckDB run projection (`DbJobRunStore.recentRuns(limit, job, afterStartTime, afterRunId)` +
  `countRuns`, `ORDER BY start_time DESC, run_id DESC`, keyset SQL dialect-neutral for DuckDB + Postgres).
  Legacy/unversioned callers get the same bare list as before. Other list families adopt the same seam
  on demand. **Second adopter (2026-07-19): `GET /objects`** — with a twist: unlike `/jobs/runs`, this
  route has a SEC-7d visibility post-filter (`ObjectRoutes.visibleOnly`), so an SQL-side keyset would
  make `total`/page sizing wrong or leaky under that filter. The keyset (`createdAt DESC, id DESC`)
  instead runs **in-route over the already-visibility-filtered set** — acceptable because operational
  objects are explicitly low-volume by design (`ObjectQuery.unbounded()` widens the query, the route
  slices/encodes the cursor itself). Legacy offset view unchanged. **Third + fourth adopters
  (2026-07-19): `GET /jobs` and `GET /events`** — one of each variant. `/jobs` follows the `/objects`
  in-route pattern (the registry is an in-memory materialized `JobView` list, low-volume; single-part
  keyset `name` — unique, so name order is total; `JobRoutes.jobsPage`). `/events` follows the
  `/jobs/runs` store-side pattern (events are high-volume rolling Parquet): `EventStore` gained
  `page(limit, afterTs, afterId)` + `count()` (defaults for API compat; exact overrides in both bundled
  stores — ring walk in `InMemoryEventStore`, SQL keyset predicate
  `(ts_ms < ? OR (ts_ms = ? AND event_id < ?)) ORDER BY ts_ms DESC, event_id DESC` merged with the
  unflushed buffer in `ParquetEventStore`). Note the v1 `/events` view pages the **full retained
  history**, unlike the legacy view which only serves the live-tail ring. Tests:
  `ControlApiJobsPageTest` · `ControlApiEventsPageTest` (incl. a shared-timestamp id-tiebreak resume).
* **Multi-space** — the space segment sits **after** the version: `/api/v1/spaces/{id}/…`
  (see [multi-space](multi-space.md)).
