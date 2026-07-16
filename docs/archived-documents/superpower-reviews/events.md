# Review sheet — Events & Activity pane (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/events/{events.component.ts,.html, event-detail.dialog.ts, events.routes.ts}`
+ new `events.component.spec.ts`; mock fixes in `inspecto/mock/handlers/ops.handler.ts` (+spec).

The Operational Intelligence event stream (`GET /events/search`, newest-first): a filter toolbar
(min level / type / pipeline / free-text / limit), a 5-second live-tail toggle (`visibleInterval`,
pauses when hidden), CSV export (`GET /events/export`), operator-saved views (`/events/views`),
and a detail dialog with drill-down by correlation id or event type. Read-mostly — the only writes
are saved views, so the authoring form rules don't apply (view save is a one-field name, upsert
semantics are deliberate for views).

## R1 — Glossary

Canonical: **Event** (immutable operational fact — never "log entry" in UI), **Level** (TRACE→ERROR
severity ladder; the filter is a *minimum*), **Type** (extensible `Event.type` constant), **Pipeline** ✓
(no "Flow"), **Correlation id**, **Saved view** (an operator-saved filter set). The `Source` column is
the *emitting component* (`Event.source`, e.g. `engine`) — a different concept from the canonical
**Source** (acquisition input). Kept (it mirrors the backend field name) but flagged: if it confuses,
the column header could become "Emitted by". No GLOSSARY change made.

## R2 — Attribute audit

Events are backend-emitted and immutable — no `AttributeSpec` authoring set; the audit is a column/field
audit. Grid: time · level (badge) · type · pipeline · correlation · source · message; the detail dialog
adds eventId + the structured `attributes` bag as a key/value table. Full `EventRow` coverage, nothing
speculative. The saved-view "form" is a single name field inside the Views menu — ask-the-minimum by
construction.

## R3 — UX pass

Single `<h1>`; icon-only Refresh has `aria-label` + tooltip; live-tail is a labelled slide-toggle;
filter toolbar is dense `gamma-mat-dense` fields with enter-to-search; saved views collapsed into a
menu (not a second toolbar row); the correlation drill-down surfaces as a removable chip with a
labelled clear button; empty state via `<inspecto-empty-state>`. Detail dialog: badge + type title,
field grid, attributes table, drill-down actions with tooltips. No change needed.

## R4 — Reuse pass

On the design system: `<inspecto-data-table tier="pro">` (SQL/filter tooling over the stream),
`statusBadgeHtml` level renderer, `<inspecto-empty-state>`, `InspectoConfirmService.confirmDestructive`
(view delete), `fmtDateTime`, `visibleInterval`. The correlation chip uses primary-tinted classes
(`bg-primary-100/900`) — primary is not a status color, `lint:tokens` green; noted as a candidate for a
shared filter-chip primitive if one lands. No hardcoded colors.

## R5 — Logic extraction

`events.component.ts` is 259 lines but the logic is thin glue (filter build, saved-view apply, CSV blob
download); no state machine or shaping worth a pure lib. The **mock-side** query semantics were extracted
as pure, exported fns (`filterEvents`, `eventsCsv` in `ops.handler.ts`) and unit-tested. No component
split warranted.

## R6 — Mock contract — 4 real gaps found & fixed

The pane did NOT fully run on the W1 store; all fixed in `ops.handler.ts`:

1. **`/events/search` ignored every query param** — Search/filters were decorative in mock dev. Now
   applies the real semantics: `level` = minimum on the `EVENT_LEVELS` ladder, `type`/`pipeline`/
   `correlationId` exact, `q` case-insensitive substring over message|source, `limit` caps the page.
2. **`GET /events/export` unhandled** — Export CSV fell through to the (absent) backend and tripped the
   connectivity banner. Now serves CSV text (same column order as the real exporter, RFC-4180 quoting).
3. **`POST /events/views` body-shape mismatch** — the real service posts a **flattened** body
   (`{name, level, …}`) but the mock read `body.filters`, so every saved view persisted `{}` and
   apply-view restored nothing. Now accepts both shapes.
4. **`POST /events/views/{name}/delete` unhandled** — view delete failed in mock. Now deletes from the
   store.

## R7 — Interview / decisions made

1. **"Source" column naming** (see R1) — kept as the backend field name; rename to "Emitted by" only if
   the owner flags confusion with acquisition Sources.
2. **Saved views are upsert-by-name** (real API semantics) — no dup-name guard on purpose: re-saving a
   view under the same name is the intended "update view" gesture.
3. **Live-tail cadence 5 s** — hardcoded (`LIVE_TAIL_MS`); flag if it should be a Config-pane knob.

## R8 — Verify (evidence)

- **Gap closed:** the pane had **no spec** (no a11y gate — §12 DoD miss). Added
  `events.component.spec.ts`: init load, filter serialization (only set filters sent, `q` trimmed),
  saved-view apply→requery, correlation-chip clear, search-failure degradation, and an
  `expectNoA11yViolations` on the empty state. `ops.handler.spec.ts` gained 3 cases (query semantics,
  flattened view body + delete, CSV export).
- **Automated:** `lint:tokens` ✓ · `test:ci` **626 passed / 0 failed / 5 skipped** (baseline 612/0/5;
  +14 across the Events+Alerts reviews, one combined run) · prod `build` ✓ (pre-existing NG8011/CommonJS
  warnings only).
- **Live smoke** (`:4204`): min-level ERROR narrows the grid · saved view round-trips (save → reset →
  apply restores filters + rows) · view delete works · Export CSV downloads matching rows · live tail
  ticks with simulator appends. 0 console errors.

**Definition of Done: met** (mock contract now real; a11y gate added).
