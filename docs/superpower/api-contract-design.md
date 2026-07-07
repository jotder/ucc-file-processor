# API Contracts v1 — the Control API as stable business contracts

> **Status:** design exercise, agreed direction 2026-07-06. **DESIGN ONLY — no code changes ship with this
> doc**; implementation lands as the Standard-edition worklog (§10). · **Owner:** product owner + backend
> track. · **Input:** the product owner's 33-point API design guidelines (business-capability contracts,
> bootstrap/metadata-first, envelope, fine-grained permissions, WSO2/Keycloak gateway) — mapped point-by-point
> in §9. · **Companions:** [`../EDITIONS.md`](../EDITIONS.md) (Standard = OIDC resource server behind an IAM),
> [`rbac-groundwork.md`](rbac-groundwork.md) (Capability seam), [`living-operational-system.md`](living-operational-system.md)
> (seven networks), [`metadata-network-design.md`](metadata-network-design.md) (refs graph + bundle v2
> `contentHash`), [`backend-backlog.md`](backend-backlog.md) (`ComponentStore` widening),
> [`../api-stability.md`](../api-stability.md) (Java embedding API policy — this doc is its HTTP counterpart).

**Why now.** The UI talks to an in-process JDK `HttpServer` control plane today (Personal edition:
localhost, auth-free). Standard edition must meet security compliance — HTTPS, CORS, RBAC, an API gateway
(WSO2) / IAM (Keycloak) — and the cheapest time to shape the HTTP surface for that world is before the
security module exists. This doc designs the **v1 business contracts** so that gateway, IAM, and RBAC drop
in *around* the API without reshaping it.

---

## 1. Posture & non-negotiables

1. **APIs are versioned business contracts, not implementation mirrors.** Endpoints answer business
   capabilities (`POST /pipelines/{id}/dry-run`, `GET /datasets/{id}/lineage`), never CRUD over internals.
2. **One codebase, editions as build flavors** (`EDITIONS.md`): the *contract* is identical in every
   edition. Personal serves it auth-free on localhost; Standard serves the **same contract** behind
   WSO2/Keycloak with permissions resolved from IAM claims. No `if (edition == …)` in handlers — the
   `Authenticator` SPI and the permission resolver are the only seams.
3. **The gateway is transport only.** WSO2 owns AuthN (OAuth2/OIDC, JWT validation), TLS termination,
   rate limiting/throttling, IP filtering, request-size limits, API analytics, and version routing.
   Business logic, authorization decisions, validation, and audit **stay in the backend**.
4. **Canonical vocabulary is binding** (`docs/GLOSSARY.md`) — in paths, DTO names, and parameter names.
   The guideline examples translate: *rule* → **Expectation / Alert Rule / Decision Rule**; *workspace* →
   **Space** (`$workspace` → `$space`); *flow* → **Pipeline**; *issue* → **Incident**; *GIS* → **Geo Map**;
   *AI* → **Assist**.
5. **Transport constraint stated up front:** the JDK `HttpServer` speaks HTTP/1.1. HTTP/2/3, Brotli, and
   connection multiplexing are delivered **at the gateway** (WSO2 terminates HTTP/2/3 client-side and
   proxies HTTP/1.1 upstream). This is a deliberate consequence of the framework-free core — not a gap to
   fix with a server framework migration.

---

## 2. Honest audit — the surface today (2026-07-06)

Inventory taken from source (`ControlApi.java` + 18 `RouteModule`s): **121 routes**.

| Convention | Today | v1 target |
|---|---|---|
| URL versioning | none (`/api` prefix is a dev-proxy affordance, stripped in dispatch) | `/api/v1/…` (§3) |
| Response envelope | raw domain JSON, no wrapper | `{data, metadata, links, permissions, diagnostics}` (§4) |
| Errors | `{"error": "<string>"}` + central status mapping | structured error object + error-code catalog (§5) |
| Status codes | 400/403/404/405/409/422/500/503 — correct, but code-only | same codes, machine-readable `errorCode` + guidance |
| Fail-closed gates | hand-repeated per write module (write-root 503 → 422 → path-jail 403 → 409) | one shared gate chain = the future middleware seam (§10 W1) |
| DTOs | mixed: ad-hoc `Map`s + partial `toMap()` shaping (connections mask secrets) | explicit DTO records per bounded context (§6) |
| Pagination | ad hoc `?limit=&offset=` on a few routes | uniform cursor/offset envelope (§7) |
| Caching | none | ETag (= config `contentHash`) + `If-None-Match` on all metadata (§7) |
| CORS | opt-in `-Dcontrol.cors=<origin>` | dev flag stays; production CORS at the gateway (§8) |
| AuthN/AuthZ | none (auth-free core); `X-Actor` header = audit hint only | IAM JWT (Standard) via `Authenticator` SPI; `X-Actor` retired for authenticated actor (§8) |
| Correlation | `/events/search?correlationId=` exists; nothing generated per request | dispatcher-issued `Correlation-ID`, propagated into Signals (§7) |
| Idempotency | per-route dedupe checks only | `Idempotency-Key` on retryable POSTs (§7) |
| Async | all triggers synchronous; SSE only for notifications | 202 + Run id + poll/stream (§7) |
| Space scoping | `/spaces/{id}/…` prefix-strip seam | explicit `/api/v1/spaces/{s}/…` in the contract (§3) |

The good news: the hard architectural work is already done in the metadata layer. The R1 refs graph gives
hypermedia links for free; the R3 Query/$-parameter/Result Set design *is* guidelines 18–21; the R4 Signal
envelope already carries `correlationId`; bundle v2's `contentHash` is the ETag/version seam; the Capability
seam is the fine-grained-permission model. v1 is largely **wiring existing designs into the HTTP layer**.

---

## 3. Versioning & URL structure

- **Base:** `/api/v1/…`. URI versioning, consistent platform-wide (the gateway routes by prefix; media-type
  versioning was declined — harder to route/throttle/analyze at WSO2).
- **Within v1: additive only.** Never remove or rename a field; deprecate with a documented sunset
  (`metadata.warnings` carries deprecation notices). Breaking change ⇒ `/api/v2`, with v1 served in
  parallel through the sunset window. This extends the SemVer discipline of `api-stability.md` to HTTP.
- **Legacy surface:** today's 121 unversioned routes are frozen as-is and become **aliases** of their v1
  successors during migration; they are retired only after the UI is fully on v1 (Personal can drop them a
  major version later; the gateway simply never exposes them in Standard).
- **Scoping:** space-scoped resources live under `/api/v1/spaces/{spaceId}/…` explicitly (no implicit
  active-space). Platform-scoped: `/api/v1/bootstrap`, `/api/v1/session`, `/api/v1/spaces`. Server
  validates space membership/grants on **every** request (guideline 25) — never trusts the client's claim.
- **IDs are immutable** (guideline 17): component ids, run ids, signal ids never change; display names and
  labels may. Already the store convention — v1 makes it a stated contract.

---

## 4. The response envelope

Every v1 JSON response (success and error) uses one envelope. Streams are exempt: SSE events are R4
**Signal** envelopes instead; `/metrics` stays Prometheus text (ops plane, not routed by the gateway).

```jsonc
{
  "data":        { /* the DTO — or [ … ] for collections */ },
  "metadata": {
    "timestamp":   "2026-07-06T12:00:00Z",
    "durationMs":  12,
    "apiVersion":  "v1",
    "etag":        "sha256:…",              // = the config contentHash where applicable
    "cache":       { "cacheable": true, "maxAgeSeconds": 300, "scope": "space" },
    "pagination":  { "cursor": "…", "nextCursor": "…", "limit": 100, "total": 1234 },
    "warnings":    [ { "code": "DEPRECATED_FIELD", "message": "…", "sunset": "2027-01-01" } ]
  },
  "links": {                                 // hypermedia — derived from the R1 refs graph
    "self":         "/api/v1/spaces/ra/pipelines/enrich_roaming",
    "related": [ { "rel": "triggers", "kind": "job",     "href": "/api/v1/spaces/ra/jobs/nightly" },
                 { "rel": "binds",    "kind": "dataset", "href": "/api/v1/spaces/ra/datasets/cdr" } ]
  },
  "permissions": [ "read", "edit", "delete", "execute", "export", "clone", "approve" ],
  "diagnostics": { "correlationId": "…", "requestId": "…", "node": "…" }
}
```

- **`links.related` is not hand-authored** — it is `ComponentKind.deriveRefs` (R1) rendered as hrefs. One
  derivation, now four+1 consumers: reuse-graph, bundle closure, delete-protection, fit-check, hypermedia.
- **`permissions` is the verb set for *this resource*, for *this subject*** — never a role name
  (guideline 13). Derivation in §8.
- **`metadata.etag`** doubles as the concurrency token: conditional writes send `If-Match`; a mismatch is
  `409 CONFLICT_STALE_VERSION` (optimistic locking without a new mechanism — it's the contentHash again).

---

## 5. Error model

Structured object, one shape for every non-2xx (guideline 31). The HTTP status stays honest (500 exists —
what's banned is an *unexplained* 500).

```jsonc
{
  "error": {
    "errorCode":        "CONFIG_VALIDATION_FAILED",
    "message":          "The pipeline config has 2 blocking findings.",   // user-facing
    "technicalMessage": "schema 'cdr_v2' references unknown field 'msisdn2'", // operator-facing
    "recoverable":      true,
    "suggestedAction":  "Fix the findings and retry; run POST /configurations/validate first.",
    "documentation":    "docs/configuration.md#validation",
    "correlationId":    "…",
    "details":          [ /* e.g. the 422 findings array, field errors */ ]
  }
}
```

**Error-code catalog** (maps today's central status handling + the fail-closed gate order onto codes):

| HTTP | errorCode | Today's source |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | bad body/params |
| 401 | `UNAUTHENTICATED` | *(Standard only — missing/invalid JWT; emitted by backend, enforced first at gateway)* |
| 403 | `PERMISSION_DENIED` | *(Standard — subject lacks the capability)* |
| 403 | `PATH_JAIL_VIOLATION` | write path escapes the write-root |
| 404 | `NOT_FOUND` | unknown resource/route |
| 405 | `METHOD_NOT_ALLOWED` | matched path, wrong verb |
| 409 | `CONFLICT_EXISTS` / `CONFLICT_IN_USE` / `CONFLICT_STALE_VERSION` | duplicate id · referential protection (R1 refs) · `If-Match` mismatch |
| 422 | `CONFIG_VALIDATION_FAILED` | `ConfigSafetyValidator` findings (in `details`) |
| 429 | `THROTTLED` | *(gateway-issued)* |
| 500 | `INTERNAL` | uncaught — always with `correlationId` |
| 503 | `CONTROL_PLANE_READ_ONLY` / `CAPABILITY_UNAVAILABLE` | write-root unset · optional module absent (assist) |

---

## 6. Bounded contexts & the v1 surface

One business capability per endpoint; each context owns its data; no context reaches into another's store.
The contexts follow the seven networks and today's 18 route modules — this is a **regrouping and renaming
of the existing 121 routes**, not a new backend. Representative endpoints below (not exhaustive; the full
per-route migration map is produced with W2, §10).

| # | Context (owner network) | v1 base | Subsumes today | Representative capabilities |
|---|---|---|---|---|
| 1 | **Session & Identity** (Security) | `/api/v1/session` | — (new) | `GET /session` → subject, roles, resolved capabilities, space grants. **AuthN itself is the IAM's** (Keycloak/WSO2 hosts login/token endpoints — the app never does). |
| 2 | **Platform & Spaces** (Metadata) | `/api/v1/spaces`, `/api/v1/bootstrap` | SpaceRoutes | space CRUD/import; platform bootstrap (§6.1) |
| 3 | **Metadata & Catalog** (Metadata) | `/api/v1/spaces/{s}/catalog`, `…/components` | CatalogRoutes, ComponentRoutes, ConfigRoutes | catalog + metadata graph; component CRUD by kind (versioned, ETag'd); `POST …/components/{kind}/{id}/test`; `POST …/configurations/validate`; `GET …/components/{kind}/{id}/dependencies` (reuse graph) |
| 4 | **Acquisition** (Data/Execution) | `…/connections`, `…/sources` | ConnectionRoutes, AcquisitionRoutes | connection CRUD (secrets always masked); `POST …/connections/{id}/test-reachability`; source list + collection health |
| 5 | **Pipelines** (Execution) | `…/pipelines` | PipelineRoutes, RunRoutes (pipeline-scoped ops) | pipeline CRUD; `POST …/pipelines/{id}/dry-run` → **execution plan** + sandbox preview; `POST …/pipelines/{id}/run` → 202 + Run; pause/resume; `GET …/pipelines/{id}/lineage · /quarantine · /batches` |
| 6 | **Execution & Scheduling** (Execution) | `…/jobs`, `…/runs` | JobRoutes, RunRoutes (run reads) | job CRUD (schedule is a job field, per the R2 decision — **no separate scheduler API**); `POST …/jobs/{name}/run` → 202; `GET …/runs/{runId}` (status/progress); `GET …/runs?job=&status=&cursor=`. **Fixes today's conflation**: `/runs/{name}` currently means *pipeline*; in v1 a Run is always *one execution* (GLOSSARY §6-A). |
| 7 | **Data** (Data) | `…/datasets` | DataSourceRoutes (reads), ViewRoutes, LineageRoutes, provenance | dataset descriptor (schema + Result Set metadata); `GET …/datasets/{id}/lineage`; `GET …/datasets/{id}/sample`; provenance queries |
| 8 | **Quality & Decisions** (Decision) | `…/expectations`, `…/alert-rules`, `…/decision-rules`, `…/reconciliations` | AlertRoutes (rules), R5 mock surface | typed facades over the component store (never bare "rule"); `POST …/expectations/{id}/simulate`; `POST …/alert-rules/{id}/evaluate`; `POST …/decision-rules/{id}/apply` (R5 — returns the `Consequence[]` executed); reconciliation runs → Breaks |
| 9 | **Signals & Notifications** (Signal) | `…/signals`, `…/notifications` | EventRoutes, AlertRoutes (fired), NotificationRoutes | one ledger (R4): `GET …/signals?severity=&type=&source=&correlationId=&cursor=`; `GET …/signals/stream` (SSE of Signal envelopes); `GET …/alerts` = the `ALERT_FIRED` **projection**; notification feed/read-receipts/preferences |
| 10 | **Query & Results** (Metadata/Presentation) | `…/queries` | R3 mock surface (Query Library) | query CRUD; `POST …/queries/{id}/run` (§6.2); `POST …/queries/preview` (Builder-gated ad-hoc) — **never `POST sql` from the UI in Standard** |
| 11 | **Presentation** (Presentation) | `…/widgets`, `…/dashboards` | Studio mock surface | widget/dashboard CRUD; `GET …/dashboards/{id}/configuration` → the one declarative payload (layout + widgets + their queries + filters + actions + toolbar + permissions — guideline 6/16); `POST …/widgets/load` (bulk by ids) |
| 12 | **Investigation** (Presentation/Decision) | `…/investigations`, `…/incidents`, `…/cases` | ObjectRoutes | saved views (`link-analysis-view`, `geo-map-view` kinds); `POST …/investigations/graph/query` (entity/link expansion); `POST …/investigations/geo/query`; Incident/Case lifecycle (`ack/resolve/transition`), links, comments, attachments, RCA |
| 13 | **Assist** (Decision) | `…/assist` | AssistRoutes | `POST …/assist/{intent}` (503 `CAPABILITY_UNAVAILABLE` when the module is absent); `POST …/assist/propose-decision` → `Consequence[]` proposals (human approval = the consequence gate, R5); diagnoses; settings |
| 14 | **Transfer & Promotion** (Metadata) | `…/transfer` | DataSourceRoutes (export/import) | `POST …/transfer/export` (selection + closure → Metadata Bundle v2); `POST …/transfer/fit-check` (new/exists/**drifted** + requires satisfied/missing); `POST …/transfer/import` (per-item skip/overwrite) |
| 15 | **Audit & Compliance** (Security) | `…/audit` | events (audit slice) | `GET …/audit?actor=&action=&from=&cursor=` — actor-attributed in Standard (from the JWT subject, replacing `X-Actor`), tamper-evident log per `EDITIONS.md` |

Enrichment routes fold into contexts 6/7 (they are job runs + lineage reads). `/health`, `/ready`,
`/metrics` stay unversioned at the root — infrastructure plane, gateway-internal only.

### 6.1 Bootstrap — metadata once, then operate

Two calls per login, both aggressively cached (guidelines 3/5/22; targets §9):

- **`GET /api/v1/bootstrap`** (platform + session): edition + feature flags (module presence: assist,
  security…), the **kind registry** (every `ComponentKind`: config schema, ref rels, part kinds), the
  **Visualization Type registry** (VizPlugin descriptors + `VizFit`), enumerations (severities, statuses,
  Attribute Types), the **`$`-parameter definitions** (R3 namespace), config **specs** (today's
  `GET /config/spec/{type}` folded in), theme/icons, session subject + capabilities, space list.
- **`GET /api/v1/spaces/{s}/bootstrap`** (one space): dataset descriptors, lookup values, navigation per
  Lens, space-level defaults/limits, saved-view indexes.

Both return strong ETags (hash of the assembled payload); the UI sends `If-None-Match` and gets 304 on the
hot path. Config invalidation bumps the ETag; a `bootstrap.changed` **Signal** on the stream tells a live
UI to refetch. Nothing in the bootstrap is transactional; nothing transactional is ever cached.

**Metadata-first corollary (guidelines 4/14):** field labels, validation, visibility, allowed actions,
icons, and supported renderers all ride the bootstrap payload (kind schemas + specs + viz registry +
permissions) — the UI renders from metadata, it does not hardcode. This is already the UI's direction
(kind registry, VizPlugin registry, schema-driven job form); bootstrap makes it the wire contract.

### 6.2 Query execution — the Result Set contract

`POST /api/v1/spaces/{s}/queries/{id}/run` (guidelines 18–20; this is R3's design promoted to the wire):

```jsonc
// request
{ "parameters": { "$day": -7, "region": "BD" },      // $-namespace + declared params
  "pagination": { "cursor": null, "limit": 500 },
  "sort":       [ { "field": "cost", "dir": "desc" } ],
  "projection": [ "msisdn", "cost", "day" ] }
// response data
{ "resultSet":  { "columns": [ { "name": "cost", "type": "decimal", "role": "measure" }, … ],
                  "cardinality": 8123 },
  "rows":       [ … ],
  "statistics": { "rowCount": 500, "elapsedMs": 41, "truncated": false },
  "renderings": [ "table", "kpi", "line-chart", "heatmap" ],   // Show-Me matched, UI never infers
  "drillActions": [ { "id": "open-lineage", "target": "dataset", … } ],
  "exportOptions": [ "csv", "parquet" ] }
```

- **Parameters resolve server-side** (guideline 19) from a `ParameterContext` (session/subject, scheduler,
  investigation, previous-run output, AI decision — merged in priority order). The R3 client-side resolver
  becomes the offline/mock stand-in of the same contract. Namespaces stay separate by design:
  `$param` (runtime) ≠ `:fieldValue` (rule template) ≠ `${ENV:…}` (config-time secret).
- Query ids are the resource; raw SQL from the client is Builder-only (`queries/preview`), capability-gated,
  and disabled in Standard unless the deployment explicitly allows it.
- Large results: pagination is mandatory (`return all rows` is banned); server streams/chunks beyond a
  threshold and sets `statistics.truncated`.

---

## 7. Cross-cutting conventions

- **Correlation (guideline 30):** the dispatcher generates `Correlation-ID` if absent, echoes it, and
  hands it to every downstream write — including **emitted Signals** (the R4 envelope already has the
  field). One id then links: HTTP request → Run → Signals → Notifications → Audit. `Request-ID` is
  per-hop; `User-ID`/actor comes from the JWT in Standard (never client-asserted).
- **Idempotency (guideline 29):** retryable POSTs (`…/run`, `…/import`, `…/spaces`) accept
  `Idempotency-Key`; the backend keeps a short-lived key→result store and replays the original response on
  retry. (New backend mechanism — W5, §10.)
- **Async (guideline 24):** anything that can outlive a request returns `202` + `{runId}` +
  `Location: /api/v1/spaces/{s}/runs/{id}`. Progress: poll `GET …/runs/{id}` or watch `…/signals/stream`
  (Run status ticks are Signals). No blocking HTTP on long work; today's synchronous triggers migrate here.
- **Pagination (guideline 23):** `cursor` (preferred) or `offset`+`limit`, plus `sort` and `projection`,
  uniformly; totals in `metadata.pagination`.
- **Caching (guideline 22):** every metadata GET is ETag'd (config ETag = **`contentHash`** — the same
  SHA-256 bundle v2 computes, one hash for versioning + drift detection + HTTP caching + optimistic
  locking). Transactional reads (`runs`, `signals`, rows) are never cached.
- **Compression (guideline 32):** gzip in-app for JSON bodies over a threshold (JDK server needs a small
  manual encoder — W1); Brotli/HTTP2+3 at the gateway.
- **DTO discipline (guidelines 8–10):** every v1 response body is an explicit Java `record` DTO per
  bounded context (`com.gamma.control.dto.*`), mapped from engine objects — engine/entity types are never
  serialized directly, `Map<String,Object>` is banned outside true pass-through payloads. The existing
  `toMap()` shaping (e.g. secret-masked `ConnectionProfile`) is the seed of this layer, formalized.
  Business concepts only — nothing leaks storage (no DuckDB/Parquet/partition internals in contracts;
  those surface only as *lineage/provenance business objects*).

---

## 8. Security architecture (Standard) — who does what

```
Browser ── HTTPS/HTTP2 ──> WSO2 API Gateway ── HTTPS/HTTP1.1 ──> Inspecto (OIDC resource server)
                 │                                    │
                 └── OIDC Auth Code + PKCE ──> Keycloak/IAM (users, LDAP/AD federation, SAML brokering)
```

| Concern | Gateway (WSO2) | Backend (inspecto + `inspecto-security` module) |
|---|---|---|
| AuthN | OAuth2/OIDC enforcement, JWT signature/expiry pre-check | JWT validation (Nimbus + JWKS: issuer/audience/expiry) via `Authenticator` SPI — *defense in depth, never trust the gateway blindly* |
| AuthZ | none (scopes at most) | **all of it**: claims → Roles → **Capabilities** → per-resource permission verbs |
| TLS | terminates client TLS (HTTP/2/3) | HTTPS upstream (`HttpsServer` + keystore; FIPS provider for Gov) |
| CORS | production CORS policy | dev `-Dcontrol.cors` flag only |
| Rate limiting / throttling / IP filtering / size limits / analytics | ✅ | — |
| Version routing | `/api/v1` vs `/api/v2` | serves both during sunset |
| Validation, business rules, audit, persistence | **never** | ✅ |

**Permission derivation** (guideline 13, on the [`rbac-groundwork.md`](rbac-groundwork.md) seam): the
envelope's `permissions` array is computed per resource as *subject's grants ∩ resource state* (e.g. no
`execute` on a disabled job, no `edit` without `canAuthorWorkbench`). In the auth-free core the resolver
answers from Lens capabilities (honor system, same wire shape); in Standard one class re-derives from JWT
claims — **the UI does not change**, it already gates on capability signals, and the envelope keeps it from
ever asking "which role?". Case-type **data-scoped** grants (a fraud analyst sees fraud cases) are
row-level filters inside the owning context's queries — server-side, never a UI filter
(rbac-groundwork open Q2).

Server validates on every request: token → subject → space grant → resource ownership → capability →
only then the fail-closed write gates. The gate chain (write-root 503 → validation 422 → path-jail 403 →
conflict 409) is today copy-pasted across 5 modules; W1 extracts it into one shared chain so AuthN/AuthZ
slot in as steps 0/1 without touching 121 handlers.

---

## 9. Guideline compliance map (the 33 points)

**Adopted as-is:** 1 (business capabilities) · 2 (bounded contexts, §6) · 3/5 (bootstrap, §6.1) · 4/14
(metadata-first) · 6/16 (declarative one-payload screens — dashboard configuration is the exemplar) ·
8/9/10 (DTO/mapper, no leakage, strong typing) · 11 (envelope) · 12 (hypermedia from refs graph) · 13
(capability verbs, never roles) · 15 (bulk load) · 17 (immutable ids) · 18/19/20/21 (queries as resources,
server-side `$`-parameters, Result Set contract, declared renderings — R3 promoted to the wire) · 22/23
(ETag/cursor) · 24 (202 + Run) · 25 (server validates everything) · 26/27 (gateway transport-only) · 29/30
(idempotency, correlation) · 31 (error model) · 33 (additive-only within a major).

**Adapted (with reasons):**

| # | Guideline | Adaptation |
|---|---|---|
| 7 | version every configurable object (`id/version/created/modified/checksum/published/draft`) | Adopted, staged: `contentHash` (checksum) + created/modified land with the widened `ComponentStore` (W3); **draft/published + never-overwrite version history** is a real backend feature — designed here, scheduled behind store widening ([`backend-backlog.md`](backend-backlog.md)). ETag/`If-Match` gives no-lost-updates from day one. |
| 2 | "Authentication API" category | Not ours to build — the IAM owns login/token/user-management (`EDITIONS.md`). The app exposes only `GET /session` (whoami + capabilities). |
| 2 | "Scheduler API" category | Deliberately absent: the R2 decision made schedule/trigger **job fields**, not kinds — scheduling capabilities live on the Jobs context. |
| 31 | "never return 500" | 500 stays a truthful transport status — the ban is on *unexplained* errors; every 500 carries the structured body + correlationId. |
| 32 | HTTP/2 or HTTP/3 | At the gateway; the JDK core stays HTTP/1.1 upstream (§1.5). |
| 28 | OpenAPI-first | Adopted going forward: v1 contracts are authored as OpenAPI per context in `docs/api/` **before** implementation (W2); the existing surface is documented as-built rather than retro-specced route-by-route. |

**Performance targets (guideline 32), adopted:** bootstrap ≤ 500 KB compressed / ≤ 3 s cold ·
metadata ≤ 100 ms from cache (304 hot path) · one bootstrap + parallel data queries per screen ·
config fetched once per session unless invalidated (Signal-driven) · gzip in-app, Brotli+HTTP/2/3 at
gateway · large query results streamed/paginated, never unbounded.

**Offline testability (guideline 28):** three layers, all offline — (1) the Angular **mock handlers become
the contract mock**: generated/validated against the same OpenAPI + JSON Schemas, so mock-first UI
development *is* contract-first development; (2) backend **real-HTTP JUnit tests** per route class covering
every gate (the `endpoint` skill's existing mandate, extended to envelope/error assertions); (3) **schema
validation round-trips** example payloads from `docs/api/examples/` on both sides. Security tests (authz
matrix per capability) and performance tests (bootstrap size/latency budgets) join the Standard gate.

---

## 10. Standard-edition worklog (implementation deferred — this is the backlog)

Ordered so every slice is independently shippable and nothing blocks the UI track. Backend DoD throughout:
`mvn -o test` + the `endpoint` skill's real-HTTP gate tests; GAUNTLET before any commit.

| W | Slice | Contents | Depends on |
|---|---|---|---|
| **W1** | **Transport spine — ✅ SHIPPED 2026-07-06 (core, edition-neutral)** | `/api/v1` seam in `ControlApi.dispatch` (same route table, v1 presentation via exchange attributes); envelope + error shaping in `Envelope.java` (permissions block deferred to W6); error-code catalog `ErrorCodes.java`; shared gate chain `WriteGates.java` adopted by **six** write sites (Config/Run/Connection/Pipeline/Component routes — two had a copy-pasted "connection write disabled" 503 message on non-connection routes, now correct; SpaceRoutes turned out to carry no write-root/jail gates, nothing to extract); per-request `Correlation-ID` (issued/echoed on every surface, on the SLF4J MDC so `EventStoreAppender`-bridged events inherit it, exposed via CORS); gzip negotiation ≥1 KiB in `ApiContext.respondJson`. Legacy routes byte-for-byte unchanged (pinned by `ControlApiV1Test`). Streams/`/metrics` exempt by design. | — |
| **W2** | **Contract-first tooling — ✅ SHIPPED 2026-07-06 (core)** | `docs/api/openapi-v1.json` (OpenAPI 3.1: shared components Envelope/ErrorObject+ErrorCode/Pagination/Signal/Ref, 15 context tags, as-built exemplar paths with `x-probe` markers) + `docs/api/examples/` + authoring workflow in `docs/api/README.md`; `ApiContractTest` pins doc ↔ `ErrorCodes.java` ↔ examples ↔ live server. **Two stated deviations:** (1) **JSON, one file** — not per-context YAML: the lean SBOM has no YAML parser and there is no offline `$ref` bundler; JSON is machine-checkable on both sides with zero new deps (rationale in the README). (2) **Angular mock-layer wiring moved to W7** — the mocks cannot speak v1 before the UI unwraps envelopes; the TS contract types ship with their consumer (the migration), not before. | W1 |
| **W3** | **Versioned metadata — ✅ SHIPPED 2026-07-06 (core)** | Widened `ComponentStore.WRITABLE_TYPES` + `ComponentRegistry.TYPE_BY_DIR` with `dataset`/`widget`/`dashboard` (the single shared seam the backend-backlog waited on — verified nothing special-cased the closed set; `isComponentType` is unused, so `use:`-resolution blast radius is nil). `ContentHash.java` mirrors the UI `content-hash.ts` (canonical JSON + SHA-256; parity pinned by `ContentHashTest`); component read shape gains `contentHash`/`created`/`modified`. `ETags.java` + component GET/PUT: strong ETag (=`"sha256:<hash>"`), `If-None-Match`→304, `If-Match`→409 `CONFLICT_STALE_VERSION` (new code, added through the contract-first loop). **`GET /bootstrap`** (`BootstrapRoutes`) folds edition/features + all config specs + platform enumerations + spaces + session-stub into one ETag'd call. **Stated deviations:** (1) the bootstrap serves only **backend-owned** metadata — the ComponentKind / Visualization-Type / `$`-parameter registries + theme are compile-time SPA constants (no backend authority; UI merges them), documented in `BootstrapRoutes`; (2) exact UI↔backend hash parity for **floats** is deferred to a conformance test when backend bundle export lands (W3 uses the hash backend-side only, where self-consistency suffices); (3) **draft/published version history = W3b**, deferred as planned (the store widening proves out first). ETag scoped to components + bootstrap this slice (catalog/config-spec ETag can follow). | W1 |
| **W4** | **Query & results on DuckDB — ✅ SHIPPED 2026-07-06 (core; catalog scope)** | Widened the store again with **`query`** (join `dataset` from W3); new `com.gamma.query` package: `Parameters` (server-side `$`-resolver mirroring the UI `parameters.ts` — built-ins + declared defaults + caller overrides → SQL literals, parity-pinned; other namespaces untouched), `ResultSetDescriptor` (typed columns + roles + cardinality from JDBC `ResultSetMetaData`, mirrors `result-set.ts`), `DatasetRelation` (dataset → relation SQL: `view` → `ViewDefinition.derivedSql`, or `physicalRef` → `read_parquet` glob), `QueryExecutor` (ephemeral `SqlSandbox`, register dataset as trusted view, run the SqlGuard-checked user SQL, wrap projection/sort/limit). `QueryRoutes` `POST /queries/{id}/run` returns the full §6.2 Result Set contract (typed columns, statistics, renderings, export options); `ApiContext.dataRoot()` added. Real-DuckDB tests (`ControlApiQueryRunV1Test`) + parity unit tests. **User chose the "build the catalog" scope** (over view-only/engines-only) — the blocker was that no dataset/query registry existed. **Stated boundary:** this is the query-time **read** path only — Matrices *materialization* stays a separate backend-backlog item, untouched. **Deliberate cuts:** `type:sql` executes server-side (structured queries 422 — compile to SQL client-side); safety = the existing lexical `SqlGuard` (single read-only SELECT, no file/extension funcs) over the resolved text, with the dataset's `read_parquet` confined to the trusted server-built view; renderings are a coarse role-based candidate list (the UI `recommend()` refines); cursor pagination is offset-based this slice. | W1, W3 |
| **W5** | **Async & idempotency — ✅ SHIPPED 2026-07-06 (jobs; pipeline-async = W5b)** | **`Idempotency-Key`** (`Idempotency.java` + a per-instance TTL/LRU store, captured in `ApiContext.respondJson`, replayed in `ControlApi.dispatch`) on any POST/PUT/DELETE — a retry with the same key replays the first response (`Idempotency-Replayed: true`), so a retried trigger/create doesn't run twice. **Async job pattern:** `JobService` now hoists the `runId` to submit-time (`triggerRun`) + a bounded live-run registry (`runById`, RUNNING→terminal); `POST /jobs/{name}/trigger` returns **202 + `{runId}` + `Location`** on v1 (legacy 200 body unchanged); **`GET /jobs/runs/{runId}`** polls status. Tests: `JobServiceTest` (registry) + `ControlApiAsyncV1Test` (real trigger→202→poll→SUCCESS, idempotency replay). **Stated scope:** jobs are the async Executable (already off-thread); **pipeline triggers stay synchronous** — making them async is an *engine* change (they block under `ingestLock` on the whole ETL run, no run id), deferred as **W5b**. Poll path uses `/jobs/runs/{id}` (the legacy `/runs/{name}` space is pipeline-registry, reconciled in W7). Idempotency covers retry-after-response, not simultaneous in-flight dupes (documented; the gateway throttles). "Run status ticks as Signals on the stream" stays a frontend R4 concern (backend `/events` is the projection). | W1 |
| **W6** | **Security module — ✅ SHIPPED 2026-07-06 (backend + gateway blueprints; UI = W6d, follow-on)** | **Core (edition-neutral):** `Authenticator`/`Subject` SPI + `Authenticators` ServiceLoader resolver (mirrors `SourceConnectors`' "absent module ⇒ no-op wins"); AuthN gate in `ControlApi.dispatch` (resolved eagerly at boot — fail-closed); `ApiContext.requireCapability`/`withCapability` AuthZ wired onto every write route (`canAuthorWorkbench`: Connections/Components/Pipelines/Config writes; `canOperateRuns`: Run/Job trigger-pause-resume-reprocess); `ErrorCodes.UNAUTHENTICATED`/`PERMISSION_DENIED`; envelope `permissions[]`; `BootstrapRoutes.session()` flips from the resolved `Subject`; HTTPS via pure-JDK `HttpsServer`+keystore (`-Dhttps.keystore`). **`inspecto-security` module** (new, Standard-only — reactor-gated behind the `edition-standard` Maven profile so a routine `mvn -o clean test` never touches it): `OidcAuthenticator` (Nimbus JOSE+JWT 10.9.1 — signature/issuer/audience/expiry against a JWKS) + `RoleMapper` (claims → Roles → Capabilities, mirroring `rbac-groundwork.md` §3's table 1:1). `package.ps1 -Edition Standard` builds + bundles it; `serve.sh`/`serve.bat` auto-detect its jar and turn on `-Dauth.mode=oidc` from `AUTH_OIDC_*` env vars. WSO2 API definition + Keycloak realm blueprint under `docs/api/deployment/`. Tests: `ControlApiAuthV1Test` (core gate, via an `Authenticators.forTest` seam — a real `META-INF/services` registration in `inspecto`'s own test classpath would poison every other test) + `OidcAuthenticatorTest` (9 cases, fully offline — an in-memory RSA key pair stands in for the JWKS). **Stated deviations:** (1) **actor-attributed audit** — `ApiContext.actor()` now prefers the resolved `Subject` over `X-Actor`, but `X-Actor` is not yet *rejected* on Standard (full retirement is a follow-on once the UI stops sending it, W7). (2) **Envelope `permissions[]` is session-wide**, not the full per-resource ∩ resource-state refinement §8 describes (e.g. no `execute` on an already-disabled job) — that needs per-context resource logic; the UI already treats capability signals identically to the Lens honor system, so no UI change was needed for this slice. (3) **`canTriageRequirements` has no backend route** to gate (no Requirements write endpoint exists yet) — `RoleMapper` still resolves it so the capability is ready when one lands. (4) **Case-type data-scoped grants** (rbac-groundwork §4 open Q2) and the **`canOnboardConnections` split** (open Q1) are explicitly not modeled — open product questions, not backend gaps. (5) The embedded jlink runtime's module set was derived from the Personal-only jar via `jdeps`; not re-verified against `inspecto-security`'s Nimbus dependency — a Standard bundle should pass `-NoRuntime` until confirmed. **W6d backend (SHIPPED 2026-07-06): the backend-mediated session (BFF)** — user decision: refresh tokens never reach the browser. Core: `TokenRelay` SPI (+`TokenRelays`, same ServiceLoader/no-op pattern) and `AuthRoutes` (`POST /auth/exchange|refresh|logout` — public paths; the httpOnly+`SameSite=Strict` `inspecto_rt` cookie holds the refresh token; only access tokens in bodies; CSRF = SameSite=Strict + Origin check per decision, no double-submit token; `503 CAPABILITY_UNAVAILABLE` on Personal). `inspecto-security`: `KeycloakTokenRelay` (JDK `HttpClient` → token endpoint; PKCE public client by default, optional confidential secret via a **`SecretResolver` reference** — the `SecretsProvider` SPI in EDITIONS.md turned out to be aspirational, `${ENV:…}` references are the shipped seam). Contract: `/auth/*` paths + `AuthExchangeRequest`/`AuthSession` schemas in `openapi-v1.json`. Tests: `ControlApiAuthSessionV1Test` (7) + `KeycloakTokenRelayTest` (7, offline fake IAM). **W6d UI SHIPPED 2026-07-07** — the Angular login flow consuming these routes, **with offline mode preserved via a switch** (user constraint): `SessionService`/`authInterceptor`/`authGuard` + PKCE util + `sign-in`/`callback` screens, all no-ops on Personal (driven by `GET /bootstrap` `features.authMode`); the mock `auth.handler` answers bootstrap as Personal by default so the app still boots login-free with no backend, and flips to a full offline sign-in demo via `mockAuthMode:'oidc'` (fake tokens). angular-ui DoD green (lint:tokens/build/test:ci 1039 pass + preview-verified both switch directions). Unit-tested (pkce/session/interceptor/a11y) since a real IAM is unverifiable here. Follow-ons: a **sign-out affordance** in the shell user-menu (SessionService.logout() exists + unit-tested but isn't surfaced yet), and X-Actor retirement (W7). | W1 (chain), W2 (contracts to publish at the gateway) |
| **W7** | **v1 migration — ✅ SHIPPED 2026-07-07 (UI; sunset deferred)** | The UI now speaks `/api/v1` end-to-end (plan: `w7-ui-v1-migration.md`). **Stated deviation: one global flip, not context-by-context** — W1's dispatch seam turned out to be fully path-generic (every route already served under `/api/v1`; verified by `ControlApiV1Test` + a backend survey), so per-context migration bought nothing; instead the whole change concentrates at three seams. (1) `apiUrl()` builds `/api/v1/…`; (2) a new first-position `v1Interceptor` unwraps success envelopes (`{data, metadata, diagnostics}` → `data`, shape-guarded via `isV1Envelope` so text/blob/304/legacy bodies pass through) — all 28 feature services keep their DTO signatures; TS contract types live in `inspecto/api/v1.ts`; (3) the mock layer envelopes at its response edge (`mock-api.interceptor.ts` + `v1SuccessBody`/`v1ErrorBody` porting `Envelope`/`ErrorCodes.defaultFor` — the 12 domain handlers stay raw-DTO, mirroring the backend seam). `apiErrorMessage` parses both error shapes; `spaceInterceptor` inserts the space id AFTER `/v1` (`/api/v1/spaces/{id}/…`). Jobs trigger consumers moved to the W5 async contract (202 + `{runId}`, "run started" toasts; mock aligned). **X-Actor needed no UI work** (never sent). **Deferred to a follow-on:** legacy-alias usage logging + retirement, ADVANCED_GUIDE Control-API regen — sunset starts once v1 has soaked in real deployments. | W2–W6 |

**Explicitly out of scope for this doc:** multi-node/Enterprise concerns (distributed scheduler, shared
stores, per-tenant ABAC — the contracts are stateless-JWT-ready, which is the horizontal-scalability
groundwork; the rest is `EDITIONS.md` Enterprise territory), a server framework migration (declined in
`EDITIONS.md` — incremental hardening on the framework-free core), and any change to the Java embedding
API (governed by [`../api-stability.md`](../api-stability.md)).
