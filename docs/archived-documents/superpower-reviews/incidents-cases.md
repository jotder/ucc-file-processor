# Review sheet — Incidents / Cases (operational objects) — list + detail (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/objects/{objects.component.ts,.html, object-detail.component.ts,.html,
object-create.dialog.ts, object-link.dialog.ts, incidents.routes.ts, cases.routes.ts}`
+ new `objects.component.spec.ts` + `object-detail.component.spec.ts`; a full `/objects` mock domain
added to `inspecto/mock/handlers/ops.handler.ts` (+spec).

One reusable `ObjectsComponent` drives both `/incidents` (type INCIDENT) and `/cases` (type CASE) via
route data; `ObjectDetailComponent` is type-agnostic (Overview · correlation Graph (G6) · Events
timeline by correlation id · Comments · Attachments, plus lifecycle transitions, object-to-object
links, and a one-click RCA skeleton).

## R1 — Glossary

Canonical: **Incident** (⛔ never "Issue" — routes/nav/UI all say Incidents ✓), **Case**,
**Operational object** (the umbrella: ALERT/INCIDENT/CASE on one table), **Transition** (workflow
action), **Correlation id/link**, **RCA**. No GLOSSARY change.

## R2 — Attribute audit

`OperationalObject` fully surfaced: list grid (title · status · severity · priority · assignee ·
correlation · created · updated) + detail overview (adds owner, closedAt, description, attributes
bag). Create dialog asks title (required) + optional description/severity/priority/assignee/SLA —
ask-the-minimum-compatible (title is the meaningful identifier; everything else optional). The
happy-path `NEXT` map (list) and `TRANSITIONS` map (detail) mirror the backend's per-type state
machines; the backend re-validates every transition.

## R3 — UX pass

List: single `<h1>` from route data, icon-only Refresh labelled, create as a primary flat button,
row-click → detail, per-row advance-lifecycle action with a dynamic hint and confirm. Detail:
breadcrumb (list → id), transition buttons from the legal-actions map, skeleton on overview load,
tabbed layout with lazy per-tab loads. **Fixed:** five bare "nothing to show" `text-secondary` divs
(graph, no-correlation, no-events, comments, attachments) → `<inspecto-empty-state>` — the **5th and
6th…9th instances** of the known violation class this track.

## R4 — Reuse pass

**Fixed:** list status + severity columns were plain text → `statusBadgeHtml` (consistent with
Events/Alerts). Already on the design system otherwise: `<inspecto-data-table tier="pro">`,
`<inspecto-skeleton>`, `<inspecto-status-badge>` (detail events tab), the shared catalog
`GraphViewComponent` for the correlation graph, `InspectoConfirmService`, reactive forms with inline
`<mat-error>` in all three forms (create/comment/attach), `apiErrorMessage` toasts.

## R5 — Logic extraction

List = 165L, detail = 281L — the detail is mostly per-tab loaders (thin glue). The mock-side workflow
map + list-query + graph-BFS logic went into pure exported fns (`OBJECT_ACTION_STATUS`,
`filterObjects`, `objectGraph` in `ops.handler.ts`). The duplicated NEXT/TRANSITIONS maps between list
and detail are intentional views of the same machine (single-action vs all-legal-actions); flagged,
not merged (they'd collapse naturally when the backend exposes legal actions on the object).

## R6 — Mock contract — the pane was ~80 % un-mocked; now complete

Only `GET /objects` (list, type filter) existed; every user-triggered mutation fell through to the
absent backend. Added the full domain to `ops.handler.ts`:

- `GET /objects` now honors status/severity/assignee/correlationId/`q`/`limit` too (was type-only).
- `POST /objects` — create (422 without title; `dueInMinutes` → a `dueAt` attribute).
- `GET /objects/{id}` — 404 envelope when unknown.
- `POST /objects/{id}/transition` — action→status map (assign/start/investigate/escalate/ack/resolve/
  close), 422 on unknown action, `closedAt` stamped on close.
- `GET|POST /objects/{id}/links` — link creation validates both ends (404/422).
- `GET /objects/{id}/graph?depth` — undirected BFS subgraph over the stored links.
- `GET|POST /objects/{id}/comments` and `/attachments` — one append-only notes collection
  (`object-note`), attachments carry `{name, uri, contentType}` attributes as the pane renders them.
- `POST /objects/{id}/rca` + `GET /rca/templates` — one comment per section ("incident-default",
  "case-fraud" templates), matching the pane's "seed an RCA skeleton" semantics.

New collections `object-link` / `object-note` (no existing shape changed → `MOCK_STORE_KEY` NOT
bumped). All round-trips persist across reload via the store.

## R7 — Interview / decisions made

1. **Mock workflow is action-keyed, not per-type-state-machine** — any legal-sounding action applies
   from any status in mock (the real backend enforces the per-type machines). Chosen for simplicity;
   the pane only ever offers legal actions anyway.
2. **NEXT/TRANSITIONS duplication** (see R5) — left as-is; unify when the API returns legal actions.
3. **Objects lens gating** — create/transition/link/comment stay available in every lens per the
   default heuristic (operational actions, not config authoring). Flag if Business should be
   read-only here too.

## R8 — Verify (evidence)

- **Gaps closed:** no specs existed for either component (no a11y gates). Added
  `objects.component.spec.ts` (route-typed load, next-action derivation, advance+reload, row-click
  nav, axe on loaded grid) and `object-detail.component.spec.ts` (load + legal transitions,
  in-place transition, comment validation guard, not-found degradation, axe on overview).
  `ops.handler.spec.ts` gained 3 object-domain cases (workflow round-trip, links+graph, notes+RCA).
- **Automated:** `lint:tokens` ✓ · `test:ci` **640 passed / 0 failed / 5 skipped** (626 → +14) ·
  prod `build` ✓.
- **Live smoke** (`:4204`): create incident → appears OPEN → advance assign→…→close persists across
  reload · link two objects → graph renders both + edge · comment + attachment + Apply RCA seed the
  notes thread · badges render in both columns. 0 console errors.

**Definition of Done: met** (mock contract built out; a11y gates added; empty-state class fixed).
