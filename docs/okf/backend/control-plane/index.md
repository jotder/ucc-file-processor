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
* [Events & metrics](events-metrics.md) - `EventLog` (synchronous bus), `MetricRegistry`, `StabilityGate`.
* [Jobs](jobs.md) - `JobService` cron/event/manual scheduling, the off-bus trigger handoff, and the
  v1 async run model (202 + `runId`).
* [Multi-space](multi-space.md) - `SpaceManager`/`SpaceContext`/`SpaceMigrator` and the MDC-based singleton isolation.
* [API stability policy](api-stability.md) - the Java `@PublicApi` surface contract (the HTTP counterpart is [api-v1](api-v1.md)).
* [Metadata bundle](metadata-bundle.md) - export/preview/import of authored config across installs (`BundleRoutes`; schema in `docs/api/schemas/`).
