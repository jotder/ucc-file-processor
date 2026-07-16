# Review sheet — Processing Status pane (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/processing-status/{processing-status.component.ts,.html,.routes.ts,.spec.ts}`.

The cross-pipeline rollup (`GET /status`): every pipeline's committed/quarantine counts and last-batch
outcome in one grid with a 4-card summary strip; a row action opens that pipeline's Run detail for the
full provenance/lineage breakdown. Read-only.

## R1 — Glossary

Canonical: **Pipeline** ✓, **Batch**, **Quarantine**, **Run** (the detail link target). No change.

## R2 — Attribute audit

Column audit: pipeline · state (RUNNING/PAUSED badge) · committed batches · quarantine files ·
last-batch status (badge) · last-batch id/time. Covers `RunStatus` fully; the report totals feed the
summary cards. Nothing speculative.

## R3 — UX pass · R4 — Reuse pass · R5 — Logic extraction

**Already fully compliant — no changes.** Single `<h1>`; labelled icon-only Refresh; metric cards;
`statusBadgeHtml` on both status columns; shared `fmtDateTime`; `<inspecto-data-table tier="standard">`
with empty state; graceful degradation on load error (banner handles unreachable). 91 lines of glue —
nothing to extract. The cleanest pane in the Ops wave; it (with audit-logs) is the reference style.

## R6 — Mock contract

`GET /status` served by `demo.handler.ts` (static report). Fine for a read-only rollup. No change.

## R7 — Interview / decisions made

None needed — every field's semantics are self-evident and no lens question arises (read-only,
row action is navigation).

## R8 — Verify (evidence)

- Spec with an axe gate already existed (`processing-status.component.spec.ts`) — nothing to add.
- **Automated (combined Wave-4 batch-2 run, Diagnoses + Processing-status + Audit/Objects follow-ups):**
  `lint:tokens` ✓ · `test:ci` **644 / 0 / 5** (whole Wave-4 batch-1 run) · prod `build` ✓.
- **Live smoke** (`:4204`): cards + grid render from the mock report; row action navigates to
  `/runs/<pipeline>`. 0 console errors.

**Definition of Done: met (review-only, no code changes).**
