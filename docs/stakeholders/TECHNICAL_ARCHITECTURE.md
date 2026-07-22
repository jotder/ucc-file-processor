# Inspecto — Technical Architecture (for architects & integrators)

> Audience: solution architects, integrators, senior engineers · Status date: **2026-07-07**
> Deep dives: the [OKF knowledge bundle](../okf/index.md) (one concept per file) ·
> engine details [`../okf/backend/engine/stage1-architecture.md`](../okf/backend/engine/stage1-architecture.md) · ops internals
> [`../ADVANCED_GUIDE.md`](../ADVANCED_GUIDE.md).

## Shape of the system

Three cooperating applications, one product:

```
┌─ inspecto-ui (Angular 21) ──────────────────────────────────────────────┐
│  One operator console · Lens-filtered nav · offline-first (mock store)  │
│  → talks /api/v1 (envelope-unwrapping interceptor chain)                │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │ HTTPS (Standard) / HTTP (Personal)
┌─ Java backend (single JVM) ──▼───────────────────────────────────────────┐
│  ControlApi (JDK HttpServer) → /api/v1 versioned contract               │
│  CollectorService: acquisition → Stage-1 M..N ingest → Parquet lakehouse   │
│  Stage-2 enrichment · authored Pipelines (DAG) · Jobs/Scheduler         │
│  Component store + metadata graphs · Signal ledger · embedded DuckDB    │
│  Modules: core │ connectors │ agent │ agent-hosted │ security(Standard) │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │ LlmGateway bridge (offline-capable)
┌─ eoiagent (separate repo) ───▼───────────────────────────────────────────┐
│  Embeddable agent platform: model gateway, governed tools, audit        │
└───────────────────────────────────────────────────────────────────────────┘
```

**Design stance:** framework-free Java core (JDK `HttpServer`, manual DI, `ServiceLoader` SPIs, virtual
threads), embedded DuckDB, TOON config files, one fat JAR + jlink runtime. Small SBOM by policy.

## Backend

- **Data plane** — Stage-1 **M..N multiplexer** (M input files → typed, Hive-partitioned Parquet
  Tables), batch-atomic, crash-isolated, idempotent; DuckDB `Appender` ingest (~75× JDBC). Stage-2
  enrichment does joins/aggregations downstream. Authored **Pipelines** (DAGs of Steps) run as
  `type: pipeline` Jobs. [Engine concepts](../okf/backend/engine/index.md) ·
  [pipeline-graph](../okf/backend/pipeline-graph/index.md).
- **Control plane** — the versioned **`/api/v1`** business contract: response envelope + error-code
  catalog, `Correlation-ID`, gzip, ETag/`contentHash` optimistic concurrency, `GET /bootstrap`,
  query execution, async runs (`202`+`runId`, `Idempotency-Key`), OpenAPI 3.1 enforced by contract
  tests. Legacy routes stay byte-for-byte until a metered sunset.
  [api-v1](../okf/backend/control-plane/api-v1.md) · contract: [`../api/openapi-v1.json`](../api/openapi-v1.json).
- **Multi-space** — isolated Spaces (`spaces/<id>/…`), MDC-routed singletons, space segment after the
  version: `/api/v1/spaces/{id}/…`. [multi-space](../okf/backend/control-plane/multi-space.md).
- **Metadata spine** — everything authored is a `Component {kind, config, parts?, wiring?}`; reuse +
  lineage graphs are *derived*; single ref-derivation. [components](../okf/backend/components/index.md).

## Security & editions

Editions are **build flavors** (never branches): the common core has **no auth code**. Standard adds
the `inspecto-security` module behind three core SPIs (`Authenticator`/`Subject`/`TokenRelay`): OIDC
resource server (Nimbus/JWKS) against the customer's IAM (Keycloak/WSO2/Okta/Entra), `RoleMapper`,
HTTPS (pure-JDK `HttpsServer`), and a BFF session (`/auth/exchange|refresh|logout`; refresh token never
reaches the browser; SameSite + Origin CSRF). Write routes are separately fail-closed behind
`-Dassist.write.root` (503). [security module](../okf/backend/modules/security.md) ·
[`../EDITIONS.md`](../EDITIONS.md).

## Frontend

Angular 21 standalone components + signals, Material/Tailwind, ag-Grid, Chart.js, AntV G6 (graphs),
MapLibre GL + PMTiles (fully-offline geo). Interceptor chain: `v1Interceptor` (envelope unwrap) →
`spaceInterceptor` → error/connectivity → auth (no-op on Personal, OIDC when
`bootstrap.features.authMode` says so). **Offline-first**: one mock store serves the entire app with
v1-envelope parity — the UI is developable and demoable with no backend. Design system + a11y (WCAG
2.2 AA) + no-hardcoded-colors gates in CI. [Frontend section](../okf/frontend/index.md).

## Agentic layer

Inspecto embeds AI **narrowly and governably**: the assist module's reasoning layer is vendored
in-tree; **eoiagent** supplies the model transport (`LlmGateway` bridge — Ollama local, OpenAI-compatible,
hosted SDKs isolated in a module that air-gapped builds physically omit). Every assist skill is
read-only/draft-only today; the embedded-intelligence design adds a governed autonomy ladder later.
[Agentic section](../okf/agentic/index.md) · [integration seam](../okf/agentic/inspecto-integration.md).

## Integration checklist (for a deployment)

1. **API**: consume `/api/v1` only (envelope + error codes; OpenAPI file is the contract).
2. **Identity (Standard)**: point OIDC at your IAM; roles map via `RoleMapper`; gateway (WSO2-style)
   can front the API unchanged.
3. **Data**: land files via Connections/Sources (SFTP/FTP/FTPS/DB today; object storage on the MUST
   list); query the lakehouse via the warehouse layer (pg_duckdb) — [`../okf/backend/integrations.md`](../okf/backend/integrations.md).
4. **Observability**: scrape Prometheus metrics; consume the Signal ledger; ship the audit trail.
5. **Packaging**: `package.ps1 -Edition <personal|standard|…>` → fat JAR + jlink bundle; air-gap
   flavors omit hosted-AI SDKs. [build & run](../okf/backend/build-run/index.md).
