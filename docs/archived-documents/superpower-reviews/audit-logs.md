# Review sheet — Audit log pane (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/audit-logs/{audit-logs.component.ts (inline template), audit-logs.routes.ts,
audit-logs.component.spec.ts}`; seed addition in `inspecto/mock/seeds/operations.seed.ts`.

The immutable "who did what, when, from where" trail: append-only `EventRow`s (`type = AUDIT` /
`ACCESS_DENIED`) whose actor/action/category/target/ip ride in `attributes`, flattened into columns
and handed to the **pro** data-table (its offline SQL editor + filter builder cover ad-hoc slicing).
Read-only by design — no authoring rules apply.

## R1 — Glossary

Canonical: **Audit log** (the pane), **Audit entry** (one event), **Actor / Action / Category /
Target**. Entries are Events under the hood but the pane deliberately presents them as an audit trail
— consistent with GLOSSARY (an Event is the umbrella operational fact). No change.

## R2 — Attribute audit

Column audit (backend-emitted, immutable): time · actor · action · category · target
(`target_type:target_id`) · ip · message, with user-agent as a hidden column (discoverable via the
data-table's column chooser). Covers the audit attribute anatomy fully; nothing speculative.

## R3 — UX pass

Single `<h1>`; Refresh is a labelled stroked button; grid empty state via `noRowsTitle/noRowsHint`.
Already `OnPush` + signals (the most modern pane style in the Ops group). No change needed.

## R4 — Reuse pass

Already fully on the design system: `<inspecto-data-table tier="pro">`, `fmtDateTime`, signals.
No hardcoded colors, no one-off widgets. No change.

## R5 — Logic extraction

123 lines; the only shaping logic is the static `toRow` flattener, covered by the existing spec.
Nothing to extract.

## R6 — Mock contract — 1 gap fixed (seed-side)

The seed had **no AUDIT/ACCESS_DENIED events**, so the pane was empty in mock dev — and before this
session's `/events/search` type-filter fix it was worse: both forkJoin queries returned all 30
operational events each, rendering every event twice with blank actor/action columns. Fixed by (a)
the search-filter fix (recorded in `reviews/events.md` R6) and (b) seeding a 10-entry audit trail
(8 AUDIT across config/operate/read/destructive categories + 2 ACCESS_DENIED, one per lens persona)
in `operations.seed.ts`. Note: existing dev localStorage stores are already marked seeded and won't
pick the new rows up until cleared — `MOCK_STORE_KEY` deliberately NOT bumped (no shape change;
an empty audit pane in a stale store is harmless).

## R7 — Interview / decisions made

1. **Audit entries in mock are seed-only** (the simulator doesn't emit AUDIT events on user actions).
   Wiring mock mutations to emit audit entries would be realistic but cross-cutting; flagged as a
   possible C7-style enrichment, not done.
2. The two ACCESS_DENIED seeds intentionally illustrate the lens/RBAC story (business lens denied a
   destructive action; unauthenticated denied read) — aligned with `rbac-groundwork.md`.

## R8 — Verify (evidence)

- Pane already had a spec **with** an axe gate (flattening + a11y) — the only Wave-4 pane that did.
  Verification here = the seed + filter behavior: `ops.handler.spec.ts` gained the
  audit-trail-by-exact-type case (8 AUDIT / 2 ACCESS_DENIED) and the seeded-events count updated
  30→40.
- **Automated:** `lint:tokens` ✓ · `test:ci` green (totals recorded in `incidents-cases.md` R8, same
  combined run) · prod `build` ✓.
- **Live smoke** (`:4204`, fresh store): Audit log renders the 10-entry trail with flattened
  actor/action/category/target columns. 0 console errors.

**Definition of Done: met.**
