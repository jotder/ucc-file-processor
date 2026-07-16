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
  `If-Match`. See [component registry](../components/component-registry.md).
* **Bootstrap (W3/W6)** — `GET /bootstrap` returns the metadata-first boot document, including
  `features.authMode`, which drives the UI's OIDC flow (no-op on Personal).
* **Queries (W4)** — the query catalog + `POST /queries/{id}/run`; see [queries](queries.md).
* **Async runs (W5/W5b)** — job triggers *and* pipeline triggers return **`202` + `{runId, status…}` +
  `Location`**; poll the run by id; `Idempotency-Key` gives at-most-once replay. See [jobs](jobs.md).
* **AuthN/AuthZ seam (W6)** — the `Authenticator`/`Subject`/`TokenRelay` SPIs gate v1 routes on Standard;
  see [auth & security](../editions/auth-security.md).
* **Multi-space** — the space segment sits **after** the version: `/api/v1/spaces/{id}/…`
  (see [multi-space](multi-space.md)).
