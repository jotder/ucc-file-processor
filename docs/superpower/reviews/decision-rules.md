# C3 — Decision Rule builder (P2) — SHIPPED

**Date:** 2026-07-04 · **Pane:** `modules/admin/decision-rules/` (`/decision-rules`, Workbench group) · **Lens:** Builder authoring, simulate in every lens

## Owner decisions (AskUserQuestion, 2026-07-04)
1. **Placement:** new Workbench pane `/decision-rules` (peer to Expectations) — the `transform.route` node stays as the execution site; the rules are now first-class (the C3 goal).
2. **When clause:** reuse the shared recursive condition-tree editor (`inspecto/query/query-condition-group.component`) — the same `ConditionGroup` model as dataset queries and rule templates.
3. **Consequences:** **multiple per rule** (owner deviation from the single-consequence recommendation) — a `consequences[]` FormArray, each `{action: route|tag|quarantine|drop, destination?}`; ≥ 1 required (422 in mock).
4. **Evaluation:** dry-run **simulate** (matched/total preview) — routing is not a failure, so unlike C2 nothing raises an Incident.

## What shipped
- **Model/API** (`inspecto/api/decision-rules.service.ts`, barrel-exported): `DecisionRule` — name (identity),
  targetType/target, `when: ConditionGroup` (query lib), `consequences: DecisionConsequence[]`
  (route→branch = the `route:*` edge vocabulary · tag→value · quarantine→optional reason · drop),
  `priority` (lower fires first), enabled, `lastSimulation {matched,total,checkedAt}`. CRUD + simulate.
- **Mock domain** (`mock/handlers/decision-rules.handler.ts`, gated `mockOps`): CRUD (422 no-name/no-consequence,
  409 dup, 404) + `POST /decision-rules/{name}/simulate` — deterministic via mock-only `demoMatched`/`demoTotal`
  on seeded rows (user-authored rules simulate 0/1000). List sorted priority-then-name. **No incident side effects.**
- **Seeds** (`operations.seed.ts`): 3 rules over the CDR stream — route EMEA tariffs (412/1000 matched),
  quarantine+tag high-cost short calls (7/1000, the stacked-consequences demo), drop zero-duration (disabled).
  **`MOCK_STORE_KEY` bumped v5 → v6.**
- **Pane** (`decision-rules.component`): standard-tier data-table — priority, when/then one-line summaries
  (`summarizeWhen`/`summarizeConsequences`, exported + spec'd), enabled badge, last-simulation "N / M matched";
  row actions Simulate (every lens) / Edit / Delete (Builder — capability seam); empty state; `confirmDestructive`.
- **Dialog** (`decision-rule-form.dialog` + `decision-rule-attributes.ts`): scalars via SchemaForm; **WHEN** =
  embedded `<inspecto-query-condition-group>` over a deep-cloned tree (the editor mutates in place — clone on
  edit or cancel would corrupt the grid row); **THEN** = consequences FormArray with action-dependent
  destination requiredness (route/tag required, quarantine optional, drop hidden), last row unremovable;
  inline dup-guard (**11th pane** on the rule); id immutable on edit; 503 → writes-disabled alert.
- **Wiring:** lazy route + Workbench nav item (`arrows-right-left`); handler registered after expectations.

## R8 verification (2026-07-04)
- `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **692 / 0 / 5** (+13: 4 handler, 5 component incl. axe ×2 +
  capability seam + summary helpers, 4 dialog incl. clone-isolation + destination-requiredness).
- **Live smoke** (:4204): 3 seeded rules render sorted by priority with correct summaries → Simulate on
  `quarantine_high_cost` → toast «would match 7 of 1000», grid cell "7 / 1000 matched", `lastSimulation`
  persisted in localStorage, **0 decision-correlated incidents** (by design), 0 console errors. New-rule
  dialog renders SchemaForm + condition builder (Add condition → field/operator selects) + consequence row
  (Action select, Branch destination).

## Follow-ups / gotchas
- The when-clause field select offers a static CDR column list (`RECORD_COLUMNS` in the dialog) — sourcing
  columns from the target's Catalog schema is the proper fix (shared follow-up with C2's free-text target).
- The condition editor **mutates the bound group in place** — any host must pass a clone (see dialog).
- Backend: no `/decision-rules` route exists; execution-side wiring (rule → `transform.route` branches) is
  the backend cutover's concern. `demoMatched`/`demoTotal` are mock-only.
