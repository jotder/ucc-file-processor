# C2 — Expectation builder (Wave 4 completion item, P1) — SHIPPED

**Date:** 2026-07-04 · **Pane:** `modules/admin/expectations/` (`/expectations`, Workbench group) · **Lens:** Builder authoring, evaluation in every lens

## Owner decisions (AskUserQuestion, 2026-07-04)
1. **Placement:** new Workbench pane `/expectations` (peer to Jobs) — not a pipeline-editor tab.
2. **Attachment:** target picker in the form — `targetType (pipeline|job)` + `target` + `column`; kind-specific params via `dependsOn`.
3. **Failures:** "Run check" evaluation endpoints (`POST /expectations/{name}/evaluate` + sweep `POST /expectations/evaluate`), like the Alerts manual sweep — simulator hook deferred.
4. **Scope:** mock-first frontend only; `ExpectationRoutes.java` is backlog (real persistence = MoSCoW M2).

## What shipped
- **Model/API** (`inspecto/api/expectations.service.ts`, barrel-exported): `Expectation` — name (identity, like Jobs),
  targetType/target/column, `kind: non_null | range | regex | referential` (GLOSSARY canonical; never bare "Rule"),
  kind params (min/max · pattern · refDataset/refColumn), severity, enabled, `lastResult {status, violations, checkedAt}`.
  CRUD + evaluate/evaluateAll.
- **Mock domain** (`mock/handlers/expectations.handler.ts`, gated `mockOps`): CRUD (422/404/409) + evaluation.
  A FAILED check creates an INCIDENT `OperationalObject` correlated **`expectation:<name>`** — deduped while one is
  still open/unresolved — and fans out **EXPECTATION_FAILED** via the shared `mock/notify.ts` core (C4 chain).
  **Determinism:** no records exist in mock, so seeded rows may carry a mock-only `demoViolations` count
  (> 0 ⇒ FAILED with that count); user-authored expectations evaluate clean. Demos stay scriptable, specs exact.
- **Seeds** (`operations.seed.ts`): 4 expectations over the CDR surface — non_null (passes), range with
  `demoViolations: 12` (fails → incident), regex on a job, referential (disabled — sweep skips it).
  **`MOCK_STORE_KEY` bumped v4 → v5** (new seeded collection must appear in existing dev stores).
- **Pane** (`expectations.component`): standard-tier `<inspecto-data-table>`; severity/enabled/last-result via
  `statusBadgeHtml`; row actions Run-check (every lens) / Edit / Delete (Builder only — capability seam
  `canAuthorWorkbench`, actions AND header button gated); "Run all checks" sweep with failure-count toast;
  `<inspecto-empty-state>` (create action hidden outside Builder); `confirmDestructive` on delete; `apiErrorMessage` everywhere.
- **Dialog** (`expectation-form.dialog` + `expectation-attributes.ts`): fully SchemaForm-driven; kind params appear
  via `dependsOn`; severity/enabled collapsed (optional tier); id immutable on edit; inline dup-guard
  (`uniqueNameValidator` — **10th pane** on the product-wide rule); 503 → writes-disabled `<inspecto-alert>`.
- **Wiring:** lazy route in `app.routes.ts` + nav item (Workbench, `check-badge` icon); handler registered in
  `mock-api.interceptor.ts` (after ops, before jobs).

## R8 verification (2026-07-04)
- `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **679 / 0 / 5** (+13: 5 handler, 5 component incl. two axe
  gates + capability-seam test, 3 dialog incl. dependsOn param switching).
- **Live smoke** (:4204): `/expectations` renders the 4 seeded rows → "Run all checks" → row shows
  **FAILED 12** badge, toast "1 expectation(s) FAILED — Incidents were raised", localStorage has the OPEN
  MAJOR incident (`correlationId: expectation:cdr_duration_range`) + 1 EXPECTATION_FAILED notification;
  incident visible on `/incidents`; 0 console errors. Dedup on re-sweep covered by the handler spec.

## Follow-ups / gotchas
- `target` is a free-text field (job-form `onPipeline` precedent) — a select over the live pipeline/job lists
  would need async options in `AttributeSpec` (not supported yet; candidate SchemaForm enhancement).
- Simulator hook (random background failures) deliberately deferred — add on top of `evaluate()` if wanted.
- `ExpectationRoutes.java` (real backend) is backlog; C3 Decision Rule builder should copy this pane's shape.
- `demoViolations` is mock-only; keep it out of any future real API contract.
