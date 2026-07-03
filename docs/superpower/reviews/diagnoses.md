# Review sheet — Diagnoses pane (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/diagnoses/{diagnoses.component.ts,.html, diagnosis-detail.dialog.ts, diagnoses.routes.ts}`
+ new `diagnoses.component.spec.ts`.

Recent failure root-cause analyses from the assist agent (`GET /assist/diagnoses`): a limit-bounded
grid, row-click detail dialog with the full diagnosis. Read-only — no authoring rules apply.

## R1 — Glossary

Canonical: **Diagnosis** (an assist-produced RCA of one failure), **Pipeline** ✓, **Batch**,
**Severity**, **Heuristic** (produced without an LLM). No GLOSSARY change.

## R2 — Attribute audit

Column audit (assist-emitted, immutable): when · pipeline · batch · severity (badge) · root cause ·
heuristic flag; the detail dialog carries the rest of the `Diagnosis` shape. Nothing speculative.

## R3 — UX pass

Single `<h1>`; icon-only Refresh labelled; limit field in the header; row-click → detail dialog; grid
empty state via `noRowsTitle/noRowsHint`. No change needed.

## R4 — Reuse pass — same 3 violations as Alerts, fixed

1. **Severity plain text** → `statusBadgeHtml` (consistency with Events/Alerts/Objects).
2. **Banned per-screen unreachable toast** ("is ControlApi running?") → plain "Failed to load
   diagnoses" (§8: the connectivity banner owns unreachable messaging).
3. **`toLocaleString`** on the When column → shared `fmtDateTime`.

Otherwise on the design system: `<inspecto-data-table tier="standard">`.

## R5 — Logic extraction

81 lines of glue — nothing to extract.

## R6 — Mock contract

`GET /assist/diagnoses` is served by `demo.handler.ts` (10 static rows). Static (not store-backed) is
fine for a read-only reporting surface. No change.

## R7 — Interview / decisions made

1. Diagnoses are deliberately not linked to Incidents today; C2's "mock failures raise Incidents" will
   revisit the failure→object flow. No action here.

## R8 — Verify (evidence)

- **Gap closed:** no spec (no a11y gate). Added `diagnoses.component.spec.ts`: init load with limit,
  row-click dialog, failure degradation with the new plain toast, axe on the loaded state.
- **Automated:** `lint:tokens` ✓ · `test:ci` green (totals in `processing-status.md` R8, combined
  Wave-4 run) · prod `build` ✓.
- **Live smoke** (`:4204`): grid renders the 10 mock diagnoses with severity badges; detail dialog
  opens. 0 console errors.

**Definition of Done: met.**
