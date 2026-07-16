# Notification System + Security Audit Trail — Plan

> **Status: SHIPPED** to `origin/master` in commit `ddfa288`
> (`feat: notification system + security audit trail`, 2026-06-29). Full `inspecto` suite (886 tests)
> green; UI `lint:tokens` + production build + a11y specs green; live browser pass clean.
> Deferred (edition / follow-on, see end): digest batching, time-based retention sweep, the concrete
> email channel impl + delivery-status webhooks, GeoIP, auth-gated security-event triggers / RBAC.

## Context

The incoming requirement (Notification System + User Activity Logging & Security Audit
Trail) was written for a generic multi-tenant SaaS — it names SendGrid/SES, Redis/RabbitMQ/Kafka,
GeoIP, S3 Glacier, GDPR/CAN-SPAM, per-user preferences, and 401/403 RBAC boundaries. **Inspecto's
reality is different**, and the value of this refinement is mapping the intent onto what actually
exists:

- **Framework-free Java** (JDK `HttpServer`, manual DI, ServiceLoader SPI) + **DuckDB** + **TOON** config.
- **Auth-free core**; this version assumes a single user `appUser`. No multi-tenant, no RBAC, no login/MFA events yet.
- **Editions = build flavors** (Personal / Standard / Enterprise) — not branches. Anything needing real auth, email infra, or cloud storage belongs to a higher edition as an SPI seam, not the core.
- A mature **append-only Event engine already exists** and covers ~80% of the audit requirement.

**Locked decisions (from clarification):** real-time = **SSE**; email = **In-App only in core + a
`NotificationChannel` SPI seam**; audit = **extend the existing Event engine**; scope = **right-sized
MVP**, with multi-user/RBAC/GeoIP/cold-storage/security-event triggers explicitly deferred to editions.

Outcome: in-app notifications (bell feed, real-time via SSE, preferences) and a compliance-grade
audit trail (actor/action/target + secret scrubbing + immutable store + viewer) that are *native to the
project's idioms*, while leaving clean seams for editions to add Email and auth-gated features later.

---

## Requirement → Project mapping (the refinement)

| Spec asks for | Refined to (this project) |
|---|---|
| WebSockets/SSE for real-time | **SSE** over JDK `HttpServer` (one-way server→client; `/notifications/stream`). UI falls back to 15s polling if the stream drops. |
| SendGrid/SES/Mailgun, webhooks, unsubscribe, CAN-SPAM/GDPR | **`NotificationChannel` SPI**; core ships **In-App** only. Email + delivery-status webhooks + unsubscribe = Standard/Enterprise flavor. |
| Redis/RabbitMQ/Kafka async broker | **No broker.** In-process **virtual-thread executor** (the `JobService` idiom) + append to the already-async `EventStore`. Never block the request/bus thread. |
| Time-series / write-heavy separate DB | **`ParquetEventStore`** (rolling Hive-partitioned Parquet queried via DuckDB) already *is* this. |
| Append-only w/ DB creds lacking UPDATE/DELETE | `EventStore` is **append-only by contract** (no update/delete API). HTTP **405** on audit-mutation routes (free from dispatch). Physical credential separation = deployment/edition note. |
| Per-user preference grid | **Single global `appUser`** preference set. Per-user prefs deferred to the auth module. |
| Actor = UUID, impersonator, api_key | Default actor **`appUser`**; optional `X-Actor` header read in `ApiContext`; SPI seam for editions. |
| GeoIP location | Capture **IP + user-agent** from `HttpExchange` now; GeoIP lookup deferred (edition/connector). |
| Security events (login/MFA/password) + opt-out bypass | **Deferred** — triggers need the auth module. The *non-mutable category* flag is modelled now (critical bypass). |
| Unauthorized access (401/403) | No auth today → audit **unknown-route 404s + 405s on read-only routes** as `ACCESS_DENIED`; true 401/403 deferred. |
| S3 Glacier cold storage, multi-year retention | Core: bounded in-memory feed + existing Parquet rolling for audit. Cloud cold-storage = edition/connector. |

**Key storage distinction preserved:** the **audit trail is immutable** (extend `EventStore`,
append-only), but the **notification feed is mutable** (unread→read→archived) — so the feed uses the
mutable **`ObjectStore`** pattern, *not* `EventStore`.

---

## Backend (delivered)

Packages: `com.gamma.event` (audit extension), `com.gamma.notify` (notification engine), `com.gamma.control` (routes).

- **B1 — Audit foundation (extend the Event engine).** `AuditAttrs` (well-known attribute keys) +
  `Event.Builder` audit helpers (`actor`/`action`/`target`/`ip`/`userAgent`); `AUDIT` + `ACCESS_DENIED`
  in `EventType`; `SecretScrubber` wired into `EventLog.emit` (every event scrubbed before the
  append-only store; hot-path-safe, never throws); request-context capture in `ApiContext`
  (`actor` default `appUser` + `X-Actor` override, `ip` via `X-Forwarded-For`/peer, `user_agent`); a
  **central audit interceptor** (`AuditTrail`) in `ControlApi.dispatch` that records every successful
  state-changing request + non-GET forbidden-route attempts from one seam. 405 immutability guard is
  inherent to dispatch.
- **B2 — Notification core.** Mutable `NotificationStore` (+ `InMemoryNotificationStore`, bounded),
  `Notification`/`NotificationState`, `NotificationTemplate` (`{{var}}` interpolation), `NotificationRule`
  + built-in `NotificationRules` (operational failures → feed), `NotificationService` (an `EventLog`
  subscriber that **hands off to a virtual-thread executor** — avoids the sync-bus/`ingestLock`
  deadlock), and `NotificationRoutes` (feed list / unread-count / read / read-all / delete). Wired in
  `SourceService` + `ServiceStores`.
- **B3 — SSE.** `GET /notifications/stream` (`text/event-stream`, blocking handler on a virtual thread,
  heartbeat, deterministic shutdown via a close-hook registry).
- **B4 — Rate limiter.** `NotificationRateLimiter` — rolling per-hour cap on identical notifications
  (the anti-loop safeguard, beyond unread-dedup). Digest batching + time-based retention deferred.
- **B5 — Channel SPI.** `NotificationChannel` (ServiceLoader); in-app intrinsic, email = edition seam.
- **B6 — Preferences.** `NotificationCategory` catalog + `NotificationPreferences` (category × channel
  grid for the single `appUser`; critical categories bypass opt-out / locked) + GET/PUT routes;
  delivery is preference-gated in `NotificationService`.

## UI (delivered, `inspecto-ui`)

- **U1/U2 — bell.** `inspecto/api/notifications.service.ts` (signals) + `layout/common/notifications/`
  bell with unread badge + CDK-overlay feed (mark-read/all/delete, empty/skeleton states), live **SSE**
  (`EventSource`) with `visibleInterval` polling fallback. Mounted in the classic toolbar.
- **U3 — audit log viewer.** `modules/admin/audit-logs/` (`/audit`, under Operations) — flattens
  `AUDIT`/`ACCESS_DENIED` events into a `<inspecto-data-table tier="pro">` (SQL editor covers ad-hoc
  actor/action filtering).
- **U4 — preferences pane.** `modules/admin/notification-preferences/` (`/settings/notifications`) —
  Reactive-forms category × channel toggle grid; critical categories locked, unavailable shown "Coming soon".

## Verification

- Backend: `mvn -o test` — full `inspecto` suite **886 tests** green (real-HTTP audit, SSE frame push,
  feed CRUD, preferences, rate limiter, secret scrubbing).
- UI: `npm run lint:tokens` + production `npm run build` + `npm run test:ci` (a11y + logic specs) green.
- Live (dev server + backend): bell in toolbar, popover empty-state, `/audit` + `/settings/notifications`
  render (Security locked, Collaboration "Coming soon"), zero console errors.

## Out of scope (edition / future seams)

- **Email channel**: SES/SMTP/Mailgun send, delivery-status webhooks, unsubscribe, CAN-SPAM/GDPR → SPI, Standard/Enterprise.
- **Auth-gated**: per-user prefs, RBAC, security-event triggers (login/MFA/password), real 401/403 audit → security module.
- **GeoIP** location → edition/connector (IP captured now).
- **Cloud cold storage** (S3 Glacier) + multi-year retention → edition/connector.
- **Digest batching** + time-based retention sweep → most valuable with the email channel + durable store.
- **Message broker** (Kafka/Redis/RabbitMQ) → intentionally not used; in-process virtual-thread executor + append-only `EventStore` is the idiom.
