# Review sheet — Reconciliation MVP (C9, Wave 3)

**Wave:** 3 (Business), completion item C9 · **Date:** 2026-07-03 · **Files (all new unless noted):**
`inspecto/reconciliation/{reconciliation-types.ts,.spec.ts, reconciliations.service.ts,.spec.ts, index.ts}` ·
`modules/admin/reconciliation/{reconciliations.component.ts,.html,.spec.ts,
reconciliation-detail.component.ts,.html,.spec.ts, reconciliation-form.dialog.ts,.spec.ts,
reconciliation.routes.ts}` · wiring: `app.routes.ts`, `mock-api/common/navigation/data.ts`,
`inspecto/api/components.service.ts`, `inspecto/mock/handlers/components.handler.ts` (one line each) ·
seed: `inspecto/mock/seeds/default-space.seed.ts` + `studio/datasets/dataset-sources.ts` +
`inspecto/mock/mock-store.ts` (`MOCK_STORE_KEY` v3→v4).

**Net-new feature** (plan §5 C9, P1): define a **Dataset-vs-Dataset** match (key columns + per-column
tolerances) → **Break report** (cards) + **Break drill grid**, with a break lifecycle. Depends on W1/W2 +
the query lib (used to resolve virtual datasets to rows). Mirrors C1's structure — a pure engine lib + a
`reconciliation` Component kind + a pane — so the persistence/mock story is inherited, not rebuilt.

## Product-owner clarifications (resolved before starting, recorded in plan §6)

Both C9 interview questions answered 2026-07-03:
1. **Matching** — keys + a **configurable tolerance** (exact / absolute / percent) per compare column.
2. **Break lifecycle** — a break **auto-closes** when its key re-matches within tolerance on a later run;
   **manual resolutions are preserved** across runs.

## R1 — Glossary

New concepts: **Reconciliation**, **Break** (with types missing-left / missing-right / value-break),
**Tolerance**. Self-consistent, no collision with banned synonyms. **GLOSSARY.md not yet updated** — same
flagged fast-follow as C1's "Requirement" (R7 #4); not blocking.

## R2 — Attribute audit

`ReconciliationConfig { leftDataset, rightDataset, keyColumns[], compareColumns[] (column +
toleranceType + tolerance), breaks[], lastRunAt }`. `ReconBreak { key, type, column?, leftValue?,
rightValue?, diff?, status, note? }`. Closed, minimal; `id` machine-generated (nothing references a
reconciliation by id yet). No speculative fields.

## R3 — UX pass

**List** (`/reconciliation`): `<h1>`, Refresh, "New reconciliation", a data-table (name/left/right/keys/
last-run) + empty state. **Create dialog**: pick both datasets, key column(s) and compare column(s) with
per-column tolerance — column pickers driven by the left dataset's declared columns. **Detail**
(`/reconciliation/:id`): breadcrumb, a prominent "Run reconciliation" button, a 6-card **break report**
(matched keys, open, value/missing-right/missing-left, resolved + auto-closed), and the **break drill
grid** with a per-row Resolve/Re-open action. Progressive: report + drill appear only after the first run.

## R4 — Reuse pass

Fully on the design system: `<inspecto-data-table>` (list, drill), `<inspecto-empty-state>` (no
reconciliations / no open breaks), `statusBadgeHtml()` for the break-type and status cells,
`InspectoConfirmService` (resolve confirm), the shared **Query Core** `evaluateRows` to resolve a virtual
dataset to rows. No hardcoded colors. No new dependency — persistence rides the existing generic
`/components/{kind}` surface (one-line `ComponentType` + `STUDIO_KINDS` additions), no new endpoint.

## R5 — Logic extraction

The entire matching + lifecycle engine is the pure, framework-free `reconciliation-types.ts`
(`runReconciliation`, `withinTolerance`, `mergeBreaks`, `resolveBreak`, `summarize`, `matchedKeyCount`,
`buildReconciliation`) — no Angular, exhaustively unit-tested. The detail component only orchestrates
(resolve rows → run → merge → persist → summarize) and holds a `datasetRows()` resolver.

## R6 — Mock contract

Runs on the unified `MockStore` via `ComponentsService` (`reconciliation` kind). **Seeded end-to-end so
it demos on a fresh space**: two datasets (`switch_cdr` / `billing_cdr`) over two new `SAMPLE_SOURCES`
with a deliberate RA break scenario (one value break under tolerance-clean, one under-billed break, one
unbilled/missing-right, one billed-with-no-network/missing-left), plus a seeded `switch_vs_billing`
reconciliation. `MOCK_STORE_KEY` bumped v3→v4 so the new seed shape lands over stale snapshots.

## R7 — Interview / decisions made

1. **Create is open to every lens** (not Business-only) — same reasoning as C1 R7 #1: no identity model
   to restrict "who is Business," and no pane gates *creation* to a lens. Running/resolving are likewise
   open (they're the point of the pane and are non-destructive to source data).
2. **Datasets resolve to rows via `SAMPLE_SOURCES[sourceName]`** (+ the dataset's Query Core filter when
   virtual). This is the mock stand-in for a real query/DuckDB execution; the REST/config contract is
   faithful so a backend cutover replaces only `datasetRows()`. A reconciliation whose datasets point at a
   source not in `SAMPLE_SOURCES` yields empty sides (no crash) — flagged, acceptable for the mock phase.
3. **Auto-close is real but not live-demonstrable on static seed data** — `mergeBreaks` genuinely
   auto-closes a key that stops breaking, and this is unit-tested (`mergeBreaks` spec + the detail spec's
   "re-run is stable" case). Because `SAMPLE_SOURCES` don't mutate between runs, a live demo won't *show*
   an auto-close; it would require editing the source rows (or a real changing backend). The lifecycle
   logic is correct and tested regardless.
4. **Tolerance is per-compare-column** (exact / absolute / percent); percent with a left value of 0
   requires exact equality (no meaningful percentage) — an explicit, tested edge.
5. **No scheduled/recurring reconciliation runs** — running is manual (a button). Scheduling would tie
   into C6 (scheduled reports / triggers), a separate P2 item. Flagged, not built.
6. **GLOSSARY.md entry deferred** (see R1).

## R8 — Verify (evidence)

- **New specs across 5 files** — engine (`reconciliation-types.spec.ts`: tolerance modes, the full break
  scenario, `mergeBreaks` auto-close + preserved-resolution + bounded-history, `summarize`, build),
  service (create/save/list), form dialog (validity gate + emitted config + column pickers), list
  (load/empty/a11y), and the **detail integration** (`reconciliation-detail.component.spec.ts`: run over
  the seeded switch/billing rows → 3 open breaks of the right types + 4 matched keys, resolve moves a
  break out of open, a stable re-run auto-closes nothing, a11y).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **579 passed / 0 failed / 5 skipped**
  (baseline 555/0/5; +24 new cases across the 5 spec files). Two self-introduced bugs caught by the verify
  loop before shipping: `valid` was a `computed()` over non-signal ngModel fields (never recomputed → fixed
  to a plain method); the detail view rendered an empty ag-Grid pre-run (axe `aria-required-children` →
  fixed to a "Not run yet" empty-state).
- **Live smoke** (`:4204`): "Reconciliation" nav item; the seeded `switch_vs_billing` opens, Run produces
  3 open breaks (1 value / 1 missing-right / 1 missing-left) with 4 matched keys and 2002 correctly clean
  within tolerance; resolving a break updates the cards; the drill grid exports via the data-table.

**Definition of Done: met** — C9 ships as a working, seeded, demoable MVP with a fully-tested pure engine.
Deliberate scope cuts (live auto-close demo needs changing data; no scheduled runs; GLOSSARY entry) are
flagged, not silently dropped.
