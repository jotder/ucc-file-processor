# Control Plane

The HTTP control surface + the runtime services around the [engine](../engine): the API and its versioned
v1 contract, queries, observability, the job scheduler, and multi-space hosting.

# Concepts

* [Control API](control-api.md) - the JDK `HttpServer`, the `dispatch` seam, the route families, the
  editions-aware auth model (auth-free core; Standard OIDC via SPIs).
* [Versioned API (/api/v1)](api-v1.md) - the v1 business contract: envelope, error-code catalog,
  ETag/`contentHash`, bootstrap, async runs, OpenAPI enforcement.
* [Queries](queries.md) - Query as a Component: the Query Library, `$`-Parameters, Result Set, and
  `POST /queries/{id}/run` on DuckDB.
* [Decision rules](decision-rules.md) - `/decision-rules` CRUD + sample-driven `simulate` over the
  `query-types` condition tree, evaluated by the shared `ConditionTree` engine (query-eval.ts parity).
* [Events & metrics](events-metrics.md) - `EventLog` (synchronous bus), `MetricRegistry`, `StabilityGate`.
* [Jobs](jobs.md) - `JobService` cron/event/manual scheduling, the off-bus trigger handoff, and the
  v1 async run model (202 + `runId`).
* [Multi-space](multi-space.md) - `SpaceManager`/`SpaceContext`/`SpaceMigrator` and the MDC-based singleton isolation.
* [Exchange — cross-space sharing](exchange-sharing.md) - grant-mediated, read-only Dataset/Widget sharing across Spaces; offer/request/approve ledger, snapshot/live delivery, version pin + drift.
* [API stability policy](api-stability.md) - the Java `@PublicApi` surface contract (the HTTP counterpart is [api-v1](api-v1.md)).
* [Metadata bundle](metadata-bundle.md) - export/preview/import of authored config across installs (`BundleRoutes`; schema in `docs/api/schemas/`).
* [Onboarding authoring](onboarding-authoring.md) - the draft lifecycle (`/config/*`), stateless sample previews, the pipeline/enrichment register pair, and `produces: reference`.
