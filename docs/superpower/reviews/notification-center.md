# Review sheet — Notification center (C4, Ops) — NEW pane

**Wave:** 4 (Ops, P1 completion item) · **Date:** 2026-07-03 · **Files:**
`modules/admin/notification-center/{notification-center.component.ts,.html, channel-form.dialog.ts,
channel-attributes.ts, notification-center.routes.ts}` (+2 specs) · shared mock core
`inspecto/mock/notify.ts` (+spec) · routes in `demo.handler.ts`, fan-out hooks in `ops.handler.ts` +
`simulator.ts` · channel/delivery API in `api/notifications.service.ts` · the old
notification-preferences pane embedded as a tab (route redirects).

**Owner decisions (AskUserQuestion, 2026-07-03):** (1) shape = **new Ops pane with 3 tabs**
(Channels · Deliveries · Preferences; `/settings/notifications` → redirect); (2) delivery triggers =
**fired alerts + opened incidents**.

## R1 — Glossary

Canonical: **Notification** (one in-app fact, feeds the bell), **Channel** (a configured delivery
endpoint — EMAIL/WEBHOOK; mock, never actually contacted), **Delivery** (one ledger entry: a
notification handed to one channel), **Preference** (per-user category × channel opt-in). "Channel"
here = delivery channel; the preference grid's per-category toggles keep their existing
`ChannelToggles` name (same concept, user-scoped). No banned terms.

## R2 — Attribute audit

`CHANNEL_ATTRIBUTES` (SchemaForm): id (required, pattern) · kind (required select EMAIL/WEBHOOK) ·
target (required — the kind flips which field shows via `dependsOn`: address vs URL) · description
(optional) · enabled (optional, default on). Nothing speculative; delivery ledger is immutable
(column audit: when · channel · kind · target · trigger · subject · status badge).

## R3 — UX pass

One `<h1>` (pane header), tabs for the three surfaces; the embedded prefs pane's heading demoted to
`<h2>` (it no longer owns a page). Channels: New-channel primary button + labelled refresh; row
actions edit / enable-disable / delete (destructive confirm). Deliveries: read-only with refresh.
Form rules honored: **ask-the-minimum** (5 fields, only id+kind+target required) and
**duplicate-id = inline block** (8th pane on the `uniqueNameValidator` pattern; id locked on edit;
server 409 as backstop).

## R4 — Reuse pass

`<inspecto-data-table tier="standard">` ×2 · `statusBadgeHtml` (enabled-state + delivery status) ·
`<inspecto-schema-form>` (the whole channel form — no hand-built fields) · `InspectoConfirmService`
· `fmtDateTime` · **`optimisticMutate` for enable/disable** (reversible toggle per §10; create/delete
stay request→refetch) · `apiErrorMessage` toasts. No new primitives, no hardcoded colors.

## R5 — Logic extraction

The fan-out core is a **pure, framework-free lib** (`mock/notify.ts` — `fanOut()` + the collection
constants), unit-tested standalone and shared by ops.handler, demo.handler and the simulator.
`NOTIFICATIONS_COLL` moved there (demo.handler re-exports for the seeds). Pane = 190 lines of glue.

## R6 — Mock contract

- `GET/POST /notifications/channels`, `PUT/DELETE /notifications/channels/{id}` — store-backed CRUD,
  422 on missing fields, **409 on duplicate id**.
- `GET /notifications/deliveries?limit` — newest-first ledger.
- **`PUT /notifications/preferences` now persists** (was a static no-op returning the default grid —
  a pre-existing R6 bug in the old pane); GET falls back to the default grid until first save.
- Fan-out wired at all three alert/incident origins: manual sweep (`/alerts/evaluate`), the liveness
  simulator's fired alerts, and `POST /objects` for INCIDENTs. Each creates one in-app notification
  (bell moves) + one SENT delivery per enabled channel.
- New collections `notification-channel` / `notification-delivery` / `notification-pref` —
  `MOCK_STORE_KEY` NOT bumped (no existing shape changed; empty channels list is the correct fresh
  state).

## R7 — Interview / decisions made

1. Pane shape + triggers — owner-decided (header above).
2. **No channel seeds** — a fresh space starts with zero channels so the empty-state hint teaches the
   feature; flag for C7 if the vertical seed packs should ship a demo channel.
3. **Deliveries always SENT** (mock never fails); a failure state would need a real backend.
4. Prefs are per-user in name but single-`appUser` in the mock (matches the pane's existing framing).

## R8 — Verify (evidence)

- Specs: `notify.spec.ts` (fan-out: notification always recorded; enabled-channels-only),
  `demo.handler.spec.ts` +2 (channel CRUD w/ 409; prefs persistence), `ops.handler.spec.ts` +1
  (alert + incident fan out; CASE doesn't), `channel-form.dialog.spec.ts` (kind-specific target
  mapping, dup-id block, id locked on edit, axe), `notification-center.component.spec.ts` (loads,
  optimistic toggle + rollback, destructive delete, axe).
- **Automated:** `lint:tokens` ✓ · `test:ci` green (totals in `SESSION_STATUS.local.md`) · prod
  `build` ✓.
- **Live smoke** (`:4204`): create email channel → Evaluate-now on Alerts → Deliveries shows the SENT
  entry + bell increments · disable channel → next sweep delivers nothing new · prefs edit survives
  reload · `/settings/notifications` redirects. 0 console errors.

**Definition of Done: met.**
