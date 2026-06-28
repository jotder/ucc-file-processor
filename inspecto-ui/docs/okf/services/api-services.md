---
type: Reference
title: API Services Catalog
description: The inspecto/api resource services, shared HTTP helpers, interceptors, and async utilities.
resource: inspecto-ui/src/app/inspecto/api/index.ts
tags: [api, services, interceptors, catalog]
timestamp: 2026-06-28T00:00:00Z
---

# API Services Catalog

All services are `@Injectable({providedIn:'root'})`, inject `HttpClient`, return `Observable<T>`, and are
re-exported from the `inspecto/api` barrel. See [API & data conventions](../conventions/api-and-data.md).

## Resource services

| Service | Backs feature(s) |
|---|---|
| `PipelinesService` | [pipelines](../features/pipelines.md), [pipeline-detail](../features/pipeline-detail.md) |
| `SourcesService` | [sources](../features/sources.md) |
| `ConnectionsService` · `ConnectionProbeService` | [connections](../features/connections.md) |
| `FlowsService` · `ComponentsService` | [flows](../features/flows.md), [components](../features/components.md) |
| `CatalogService` | [catalog](../features/catalog.md) |
| `EventsService` | [events](../features/events.md) |
| `AlertsService` | [alerts](../features/alerts.md) |
| `ObjectsService` | [objects (cases/issues)](../features/objects.md) |
| `EnrichmentService` | [enrichment](../features/enrichment.md) |
| `JobsService` | [jobs](../features/jobs.md) |
| `AcquisitionMetricsService` · `ReportsService` | [dashboard](../features/dashboard.md), reports |
| `ConfigService` | [config](../features/config.md), [model-settings](../features/model-settings.md) |
| `AssistService` | [assist](../features/assist.md) |
| `HealthService` | connectivity probe (`/health`) |
| `SpacesService` | [multi-space scoping](../conventions/multi-space.md), [spaces](../features/spaces.md) |
| `IconMapService` | [icon-settings](../features/icon-settings.md) |

## Shared helpers

* `api-base.ts` — `apiUrl('/path')`, `toParams({…})`.
* `auto-refresh.ts` — `visibleInterval(ms)` (pauses while the tab is hidden) for live-tail/polling.
* `optimistic.ts` — `optimisticMutate({…})` (see [forms & state](../conventions/forms-and-state.md)).
* `connectivity.service.ts` — signals driving the [connectivity banner](../design-system/connectivity-banner.md).
* `models.ts` — shared DTO interfaces.

## Interceptors (registered in `app.config.ts`)

* `errorInterceptor` — global error → connectivity / per-screen handling ([errors & connectivity](../conventions/errors-and-connectivity.md)).
* `spaceInterceptor` — multi-space path rewrite ([multi-space](../conventions/multi-space.md)).
* `connectionMockInterceptor` · `flowMockInterceptor` · `opsMockInterceptor` — env-gated offline [mock backends](../conventions/mock-backends.md).
