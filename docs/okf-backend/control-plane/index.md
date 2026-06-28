# Control Plane

The HTTP control surface + the runtime services around the [engine](../engine): the API, observability, the
job scheduler, and multi-space hosting.

# Concepts

* [Control API](control-api.md) - the JDK `HttpServer`, the `dispatch` seam, the route families, the auth-free model.
* [Events & metrics](events-metrics.md) - `EventLog` (synchronous bus), `MetricRegistry`, `StabilityGate`.
* [Jobs](jobs.md) - `JobService` cron/event/manual scheduling and the off-bus trigger handoff.
* [Multi-space](multi-space.md) - `SpaceManager`/`SpaceContext`/`SpaceMigrator` and the MDC-based singleton isolation.
