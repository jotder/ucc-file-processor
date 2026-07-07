# Alert-Rule authoring pane — plan (audit C3)

*2026-07-07 · resolves `superpower/reviews/user-guide-audit.md` C3: of the three rule engines,
Alert Rules — the head of the **Alert → Incident → Case** chain — were the only one with no UI
authoring (rules armed by hand-saving `*_alert.toon` next to the configs;
`alerts.component.ts` toast admits it). Product-owner decision 2026-07-07: **build the pane.***

## Approach — frontend-mock-first (the C1/C9 pattern)

Mirror the **Decision Rules** pane (list + capability-gated row actions + MatDialog form +
service CRUD) with the **W2 schema-form** pilot for the dialog (an `AlertRule` is flat — a
perfect `AttributeSpec[]` case). Real backend write endpoints are backlog (see §Backend); until
they land, a live server answers 503 and the dialog shows the standard writes-disabled banner —
byte-identical to how `job-form.dialog` handles the same gap.

## Contract (mirrors `/decision-rules`)

| Verb | Route | Behavior |
|---|---|---|
| GET | `/alerts/rules` | *(exists)* list armed rules |
| POST | `/alerts/rules` | create (409-equivalent: mock rejects duplicate name) |
| PUT | `/alerts/rules/{name}` | update (name immutable — it is the storage key) |
| DELETE | `/alerts/rules/{name}` | delete |

`AlertRule` shape is unchanged (`name · metric · comparator · threshold · window · severity ·
onPipeline?`) — no model ripple into the fired-alert view or the backend TOON.

## Touchpoints

1. `inspecto/api/lens.service.ts` — new named capability **`canAuthorAlertRules`** (the
   Lens→Role seam rule: a distinct authorization question gets its own signal; RBAC maps it to
   Operations / Power / Super). Derived `!readOnly()` today like its three siblings.
2. `inspecto/api/alerts.service.ts` — `AlertRuleUpsert` + `create` / `update` / `remove`.
3. `inspecto/mock/handlers/ops.handler.ts` — POST/PUT/DELETE over the existing
   `ALERT_RULES_COLL` (store-backed, per-space, survives reload).
4. `modules/admin/alerts/alert-rule-attributes.ts` — `AttributeSpec[]`: name (identifier,
   required) · metric (string, required) · comparator (select gt/gte/lt/lte/eq) · threshold
   (number) · window (select 5m/15m/1h/24h) · severity (select INFO/WARNING/CRITICAL) ·
   onPipeline (string, optional).
5. `modules/admin/alerts/alert-rule-form.dialog.ts` — schema-form dialog (uniqueNameValidator
   on create, name disabled on edit, 503 → writes-disabled alert).
6. `modules/admin/alerts/alerts.component.*` — replace the read-only chips strip with an
   **Alert Rules** section: mini data-table (name/metric/comparator/threshold/window/severity/
   scope) + edit/delete row actions and a **New rule** button, all gated on
   `canAuthorAlertRules`; empty state explains what a rule does. Fired-alerts grid unchanged.
7. Specs — extend `alerts.component.spec.ts` (rules table, lens gating, a11y) + new
   `alert-rule-form.dialog.spec.ts` (required fields, duplicate name, save→service).
8. Docs — USER_GUIDE Alerts entry gains the authoring sentence; audit C3 marked resolved;
   backlog entry below.

## Backend (backlog — recorded in `superpower/backend-backlog.md`)

`ControlApi` POST/PUT/DELETE `/alerts/rules[/{name}]` per the `endpoint` skill's fail-closed
gate order, writing/deleting the `*_alert.toon` via `ConfigCodec` next to the pipeline configs
(the engine already hot-loads them). Until then the UI authoring is mock-only and a real
deployment 503s into the writes-disabled banner.
