---
type: Concept
title: Events & Metrics
description: EventLog (synchronous bus + the ingestLock deadlock seam), MetricRegistry, the StabilityGate, notifications + audit trail, and alert-rule authoring.
resource: inspecto-event/src/main/java/com/gamma/event/EventLog.java
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics

* **`EventLog`** (`inspecto-event/src/main/java/com/gamma/event/EventLog.java`) — the event bus. `global()` +
  per-space instances; `current()` routes by the calling thread's `space` MDC, falling back to global.
  **Emission is synchronous on the publishing thread** (`emit()` calls each subscriber inline). This is the
  deadlock seam: `CollectorProcessor` holds `ingestLock` through a poll cycle, so a subscriber that triggered a
  new ingest **inline** would re-enter `ingestLock` and deadlock — hence event-triggered work is handed to an
  off-bus virtual-thread pool (see [jobs](jobs.md)). `emit()` uses no SLF4J (avoids re-entrant capture) and
  swallows subscriber errors. A startup store-swap (`InMemoryEventStore` → configured backend) drains the old
  store oldest-first so nothing is lost.
* **`MetricRegistry`** (`inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`) — counters/gauges/
  histograms keyed by name + sorted labels; `scrape()` runs registered collectors then renders Prometheus
  text. The per-space `space` label is supplied by callers as a label (no registry-level space awareness).
  Notable counter: **`inspecto_legacy_api_requests_total{route}`** — incremented by
  `ControlApi.recordLegacyUsage` on every hit to a legacy *unversioned* route that also exists under
  [`/api/v1`](control-api.md); it is the sunset signal for retiring the legacy surface.
* **`StabilityGate`** (`inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java`) — the acquisition
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
  space (write-root 503 → 422 missing fields → 409 dup / 404 unknown; `canAuthorWorkbench`).
* **Persisted channels are wired into dispatch** (2026-07-19) — `NotificationService.dispatch` reads a
  live `Supplier<List<ChannelConfig>>` (backed by `<write-root>/registry`, resolved per space, no
  restart needed on edits) alongside the SPI-discovered channels above. For each enabled `ChannelConfig`
  whose `kind` matches a discovered `NotificationChannel.id()` (case-insensitive), it delivers through
  that transport via a new `default void deliver(Notification n, String target)` (delegates to
  `deliver(n)` for impls that resolve their destination from `notify.*` flags instead). A `kind` with no
  matching transport, or a disabled destination, delivers nothing. **Still open**: `ChannelConfig` has
  no `template` field, so a persisted channel can't override the rule-level `NotificationTemplate` — that
  rides the notification-templating slice of the in-flight Signal Backbone plan
  (`docs/superpower/event-signal-backbone-plan.md` S2), not this seam.
* **`AuditTrail`** — a central interceptor in `ControlApi.dispatch` records every successful
  state-changing request plus non-GET forbidden-route attempts (actor/action/target, secret
  scrubbing, immutable store). One seam covers all routes; 405 immutability is inherent to dispatch.
* **Email/SMTP channel wired to `deliver(n, target)`** (2026-07-20) — `SmtpEmailChannel`
  (`inspecto-connectors/src/main/java/com/gamma/connect/notify/SmtpEmailChannel.java`, id `email`,
  already discovered via `ServiceLoader` and configured from `notify.smtp.*` system properties, the
  same idiom as `WebhookChannel`) now overrides `deliver(Notification n, String target)` to address
  the mail to the persisted `ChannelConfig`'s own `target` (comma-separated addresses supported),
  falling back to the fixed `notify.smtp.to` only when `target` is blank — so an operator-managed
  `email` destination actually reaches its own recipient instead of the single configured inbox.
  `ChannelConfig.fromMap` additionally fails closed (422) at channel-creation time when `kind=EMAIL`
  and `target` isn't a valid email address (or comma-separated list) — SMTP config/target problems
  surface at CRUD time, not silently at dispatch. Template rendering (`NotificationTemplate` via the
  shared `DottedPath` grammar) was already generic in `NotificationService.dispatch` and needed no
  channel-specific change. Tests: `SmtpEmailChannelTest` (message addressing, no live server),
  `ControlApiNotificationChannelsTest` (422 on an invalid EMAIL target). Still deferred:
  delivery-status webhooks, digest batching.
* **Conservation imbalance now notifies** (2026-07-23) — `NotificationRules.defaults()` gained a rule
  for `FLOW_CONSERVATION_IMBALANCE` (category `ops`, `minLevel=WARN` so both `LOSS`/ERROR and
  `AMPLIFICATION`/WARN reach the feed, matching that `EventObjectBridge` opens an ALERT object for both
  kinds). Closes the gap `docs/ops/provenance-conservation-verification.md` flagged (OPS-5 product Q).
* **Authorable notification rules** (2026-07-24) — an operator can now add/override notification rules
  at runtime, not just channels. `NotificationRule` gained an `id` + `enabled` flag and
  `fromMap`/`toMap` (mirrors `ChannelConfig`); `NotificationRoutes` exposes `/notifications/rules*`
  admin CRUD (`GET/POST` + `PUT/DELETE /{id}`, `canAuthorWorkbench`, the same 503/422/409/404 gate
  order as channels — id bound from the path, unknown `minLevel` → 422) persisting a new
  `notification-rule` ComponentStore kind (added to `ComponentStore.WRITABLE_TYPES` +
  `ComponentRegistry.TYPE_BY_DIR`). `NotificationRules` now takes a `Supplier<List<NotificationRule>>`
  overlay resolved at dispatch time and checked **ahead of** the built-in `defaults()` in `forEvent`,
  wired from `CollectorService.persistedRules()` (per-space, live-reloaded, best-effort — a missing
  root / unreadable registry / malformed entry yields no rule, never an exception). So an authored
  rule for an already-covered event type overrides the built-in's copy/routing, a rule for a new event
  type extends coverage, and `enabled:false` mutes a built-in via a shadowing authored rule — all
  without a recompile. Chose a ComponentStore kind over a boot-scanned TOON file to match the adjacent
  `channel` CRUD precedent. Tests: `NotificationRulesTest` (overlay-first ordering, disabled fall-
  through, new-type coverage), `NotificationRuleTest` (`fromMap`/`toMap`), `ControlApiNotificationRulesTest`
  (real-HTTP CRUD + gates). Still open: no UI editor yet (backend + HTTP only).
* Deferred to editions/follow-ons: delivery-status webhooks, digest batching, time-based retention
  sweep, GeoIP, auth-gated security-event triggers / per-user prefs.

## Alert-rule authoring (shipped 2026-07-09)

* `AlertRoutes` — `POST/PUT/DELETE /alerts/rules[/{name}]` per the `endpoint` skill's fail-closed
  gate order; writes/deletes `<name>_alert.toon` via `ConfigCodec` + `AtomicFiles` under the write
  root, gated on the `canAuthorAlertRules` capability. The engine does **not** hot-load
  `*_alert.toon`: the write routes arm rules in the running `AlertService` **in-process** (always
  present, empty until armed); a restart re-arms from the persisted files.
  Gate coverage: `ControlApiAlertRuleWriteTest`.
* **Alert → Incident promotion** (2026-07-19) — `AlertService.persistAlertObject` still always opens an
  `ObjectType.ALERT`; a **critical or error** severity rule additionally opens a deduped
  `ObjectType.INCIDENT` (one open Incident per rule+pipeline, same guard as the ALERT dedup), so a
  high-severity breach enters the triage workflow, not only the alert feed. Lower severities stay
  alerts. Reuses the `ExpectationRoutes` signal→Incident dedup+open pattern (`active(INCIDENT, corr)`
  then `open(INCIDENT, …)`) — see also `jobs.md` (recon breaches) and `decision-rules.md` (`create-alert`).

The matching UI surfaces are the [events](../../frontend/features/events.md) and
[dashboard](../../frontend/features/dashboard.md) screens in the frontend bundle.
Production-investigation detail: [`docs/ADVANCED_GUIDE.md`](../../../ADVANCED_GUIDE.md).
