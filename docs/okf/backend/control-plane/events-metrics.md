---
type: Concept
title: Events & Metrics
description: EventLog (synchronous bus + the ingestLock deadlock seam), MetricRegistry, the StabilityGate, notifications + audit trail, and alert-rule authoring.
resource: inspecto/src/main/java/com/gamma/event/EventLog.java
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics

* **`EventLog`** (`inspecto/src/main/java/com/gamma/event/EventLog.java`) — the event bus. `global()` +
  per-space instances; `current()` routes by the calling thread's `space` MDC, falling back to global.
  **Emission is synchronous on the publishing thread** (`emit()` calls each subscriber inline). This is the
  deadlock seam: `SourceProcessor` holds `ingestLock` through a poll cycle, so a subscriber that triggered a
  new ingest **inline** would re-enter `ingestLock` and deadlock — hence event-triggered work is handed to an
  off-bus virtual-thread pool (see [jobs](jobs.md)). `emit()` uses no SLF4J (avoids re-entrant capture) and
  swallows subscriber errors. A startup store-swap (`InMemoryEventStore` → configured backend) drains the old
  store oldest-first so nothing is lost.
* **`MetricRegistry`** (`inspecto/src/main/java/com/gamma/metrics/MetricRegistry.java`) — counters/gauges/
  histograms keyed by name + sorted labels; `scrape()` runs registered collectors then renders Prometheus
  text. The per-space `space` label is supplied by callers as a label (no registry-level space awareness).
  Notable counter: **`inspecto_legacy_api_requests_total{route}`** — incremented by
  `ControlApi.recordLegacyUsage` on every hit to a legacy *unversioned* route that also exists under
  [`/api/v1`](control-api.md); it is the sunset signal for retiring the legacy surface.
* **`StabilityGate`** (`inspecto/src/main/java/com/gamma/acquire/StabilityGate.java`) — the acquisition
  file-readiness gate (not a health gate); one shared instance per space (see [acquisition](../acquisition/framework.md)).

## Notifications & audit trail (shipped 2026-06-29, `ddfa288`)

* **`NotificationService`** — an `EventLog` subscriber that **hands off to a virtual-thread
  executor** (never runs inline — the sync-bus/`ingestLock` deadlock seam above). Feed routes under
  `/notifications/*` (list / unread-count / read / read-all / delete), real-time via **SSE**
  `GET /notifications/stream` (blocking handler on a virtual thread, heartbeat, close-hook registry),
  and `NotificationRateLimiter` (rolling per-hour cap on identical notifications — the anti-loop
  safeguard). `NotificationTemplate` uses `{{var}}` interpolation; delivery is gated by
  `NotificationPreferences` (category × channel; critical categories locked on).
* **`NotificationChannel`** is a ServiceLoader SPI — in-app is intrinsic; **email is an edition
  seam**, deliberately not in core. A message broker is deliberately not used: in-process
  virtual-thread executor + append-only `EventStore` is the idiom.
* **Channel destinations admin CRUD** (2026-07-18) — `GET/POST /notifications/channels` +
  `PUT/DELETE /notifications/channels/{id}` manage persisted `ChannelConfig` records
  (`{id, kind, target, description?, enabled, createdAt}`) as a `channel` `ComponentStore` kind, per
  space (write-root 503 → 422 missing fields → 409 dup / 404 unknown; `canAuthorWorkbench`). This is
  **authored config only** — the live delivery path still resolves channels from the `notify.*` JVM
  flags via the SPI above; wiring persisted destinations into dispatch is the open follow-up.
* **`AuditTrail`** — a central interceptor in `ControlApi.dispatch` records every successful
  state-changing request plus non-GET forbidden-route attempts (actor/action/target, secret
  scrubbing, immutable store). One seam covers all routes; 405 immutability is inherent to dispatch.
* Deferred to editions/follow-ons: email channel impl + delivery webhooks, digest batching,
  time-based retention sweep, GeoIP, auth-gated security-event triggers / per-user prefs.

## Alert-rule authoring (shipped 2026-07-09)

* `AlertRoutes` — `POST/PUT/DELETE /alerts/rules[/{name}]` per the `endpoint` skill's fail-closed
  gate order; writes/deletes `<name>_alert.toon` via `ConfigCodec` + `AtomicFiles` under the write
  root, gated on the `canAuthorAlertRules` capability. The engine does **not** hot-load
  `*_alert.toon`: the write routes arm rules in the running `AlertService` **in-process** (always
  present, empty until armed); a restart re-arms from the persisted files.
  Gate coverage: `ControlApiAlertRuleWriteTest`.

The matching UI surfaces are the [events](../../frontend/features/events.md) and
[dashboard](../../frontend/features/dashboard.md) screens in the frontend bundle.
Production-investigation detail: [`docs/ADVANCED_GUIDE.md`](../../../ADVANCED_GUIDE.md).
