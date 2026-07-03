# Review sheet — Alerts pane (Ops)

**Wave:** 4 (Ops) · **Date:** 2026-07-03 · **Files:**
`modules/admin/alerts/{alerts.component.ts,.html, alerts.routes.ts}` + new `alerts.component.spec.ts`;
mock improvements in `inspecto/mock/handlers/ops.handler.ts` (+spec).

The core alert engine's surface (v4.1, B5): recent fired alerts (`GET /alerts`) over the armed
`*_alert.toon` rules (`GET /alerts/rules`), with a manual evaluation sweep (`POST /alerts/evaluate`).
Read-mostly — rules are authored as reviewed `.toon` files (drafted by the diagnose-and-alert assist
skill), not on this pane, so the authoring form rules don't apply. Rule authoring in the UI is **C2
(Expectation builder)** scope, not an Alerts-pane gap.

## R1 — Glossary

Canonical: **Alert Rule** (never bare "Rule" — ⛔), **Fired alert** (a breach occurrence), **Severity**
(INFO/WARNING/CRITICAL), **Pipeline** ✓, **Measure/metric**: the `metric` field here is an *engine
counter* (`failed_batches` etc.), not a BI Measure — the ⛔ *Metric (BI) → Measure* ban targets the BI
sense, so the operational column name stays. No GLOSSARY change.

## R2 — Attribute audit

Fired alerts are engine-emitted and immutable — column audit only. Grid: when · severity (badge) ·
rule · pipeline · metric · value · message; the armed-rules strip shows each rule's full predicate
(`metric comparator threshold / window`). Covers `FiredAlert`/`AlertRule` with nothing speculative
(`comparator`/`threshold`/`window` appear in the message and rule chips rather than as 3 more columns —
deliberate density choice).

## R3 — UX pass

Single `<h1>`; icon-only Refresh has `aria-label`; Evaluate-now disabled while sweeping with a spinner;
armed-rules summary reads as text + mono chips (neutral grays — not status-tinted). Grid empty state via
the data-table's `noRowsTitle/noRowsHint`. **Fixed:** "When" column now uses the shared `fmtDateTime`
(was a locale-dependent `toLocaleString`).

## R4 — Reuse pass — 2 violations fixed

1. **Severity was plain text** → now rendered with `statusBadgeHtml` (the one sanctioned status-color
   owner), matching the Events pane's level column.
2. **Banned per-screen unreachable toast** — the load-error path said "Could not load alerts — is
   ControlApi running?", pre-dating the connectivity banner (§8: status-0 messaging belongs to the
   banner alone). Now a plain "Failed to load alerts" error toast.

Otherwise on the design system: `<inspecto-data-table tier="pro">`, `apiErrorMessage` on the evaluate
path (503-style per-screen errors are legit there).

## R5 — Logic extraction

94 lines, pure glue — nothing to extract.

## R6 — Mock contract — 2 gaps fixed

1. **`POST /alerts/evaluate` always returned `[]`** — the Evaluate-now button was a no-op in mock dev.
   Now breaches the first armed rule: builds a real-shaped `FiredAlert`, persists it to the store
   (survives reload), and returns it — the pane visibly gains a row per sweep.
2. **`GET /alerts` ignored `limit`** — now honored (the pane requests 100).

## R7 — Interview / decisions made

1. **No rule authoring here by design** — rules arrive as reviewed `.toon` files; the UI path for
   authoring DQ/alert rules is C2 (Expectation builder), Wave-4 P1. This pane stays the observation
   surface.
2. **Mock evaluate always breaches** (first armed rule) — chosen for demo value over realism; flag if a
   "nothing breached" path should be reachable in mock (e.g. only breach when a WARN+ event exists).

## R8 — Verify (evidence)

- **Gap closed:** no spec existed (no a11y gate). Added `alerts.component.spec.ts`: init load +
  armed-rules summary, evaluate sweep (toast + reload), load-failure degradation, axe on the loaded
  state. `ops.handler.spec.ts` gained the evaluate-persists-and-limits case.
- **Automated:** `lint:tokens` ✓ · `test:ci` — see the combined Wave-4 run recorded in `events.md` R8
  (both panes verified in the same run) · prod `build` ✓.
- **Live smoke** (`:4204`): severity badges render; Evaluate now adds a persisted row + info toast;
  armed-rules chips show the seeded rules. 0 console errors.

**Definition of Done: met.**
