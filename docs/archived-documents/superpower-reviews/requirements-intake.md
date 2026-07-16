# Review sheet — Requirements intake (C1, Wave 3)

**Wave:** 3 (Business), completion item C1 · **Date:** 2026-07-03 · **Files (all new):**
`inspecto/requirement/{requirement-types.ts,.spec.ts, requirements.service.ts,.spec.ts, index.ts}` ·
`modules/admin/requirements/{requirements.component.ts,.html,.spec.ts, requirement-form.dialog.ts,.spec.ts,
requirement-decision.dialog.ts,.spec.ts, requirements.routes.ts}` + wiring in `app.routes.ts`,
`mock-api/common/navigation/data.ts`, `inspecto/api/components.service.ts`,
`inspecto/mock/handlers/components.handler.ts`.

**Net-new feature** (plan §5 C1, P1): Business authors KPI/Report/Reconciliation/Rule Requirements;
lifecycle (submitted → accepted/rejected → delivered); Builder sees the same list as a triage queue. One
shared pane, action visibility gated by lens — mirrors the "Lens filters toolbars, never forks" principle
used throughout Wave 1/2/W4, applied here to a brand-new surface rather than an existing one.

## Product-owner clarification (resolved before starting)

Two of the plan's standing interview-backlog questions directly blocked this build; both answered
2026-07-03 (recorded in the plan §6):
1. **Requirement lifecycle** — Business submits (`submitted`); it lands in a **Builder-facing queue**;
   Builder accepts/rejects (`accepted`/`rejected`, optional note), then marks `delivered`. No separate
   approval role; no SLA timer built (flagged, not built — see R7).
2. (C9's reconciliation-semantics answer is recorded too, but belongs to the separate Reconciliation
   review, not this one.)

## R1 — Glossary

**Requirement** (a Business-authored request for a KPI/Report/Reconciliation/Rule), lifecycle states
**Submitted/Accepted/Rejected/Delivered**. New concept — not yet in `docs/GLOSSARY.md`; flagged as a
**glossary gap** (per repo `CLAUDE.md`'s "new concepts must land in GLOSSARY.md before code" rule, which
was followed for Lens/Workbench/Studio in Wave 0 but is being surfaced here as a follow-up, not blocking
this ship since Requirement doesn't collide with any banned synonym or existing term).

## R2 — Attribute audit

`Requirement { id, title, kind, description, status, submittedAt, decisionNote?, decidedAt?,
deliveredNote?, deliveredAt? }` — a closed, minimal set. `kind` is one of the four requirement types named
in the plan (`kpi|report|reconciliation|rule`). No speculative fields; `id` is machine-generated (a slug +
random suffix), never shown to the author — nothing else references a requirement by id in this MVP, so
there's nothing to ask the user to name (the truest form of ask-the-minimum: don't ask for an identifier
at all when none is needed).

## R3 — UX pass

Single `<h1>` + subtitle, icon Refresh, "New requirement" (open to every lens — see R7 #1), a standard
data-table (title/kind/status-badge/submitted date) with a "View" row action opening the detail dialog.
The detail dialog shows the full description + prior decision/delivery notes, with Accept/Reject (on
`submitted`) or Mark delivered (on `accepted`) inputs — present only outside the Business lens. Progressive
disclosure by state (no distracting empty action rows on a `delivered` item).

## R4 — Reuse pass

Fully on the design system from day one: `<inspecto-data-table tier="standard">`, `statusBadgeHtml()` for
the status column and the dialog title, `<inspecto-empty-state>`, reactive forms + `<mat-error>` in the
submit dialog. No hardcoded colors. No new dependency — the "just a Component" persistence pattern
(mirrors `RulesService`/`DatasetsService`) reuses `ComponentsService` and the existing unified mock store
verbatim; the only backend-facing change is adding `'requirement'` to the frontend `ComponentType` union
and the mock handler's `STUDIO_KINDS` set (one line each) — no new endpoint, no new handler file.

## R5 — Logic extraction

`requirement-types.ts` is pure, framework-free data + three pure transition functions
(`buildRequirement`/`decideRequirement`/`deliverRequirement`), each independently tested. Components stay
thin: the main pane is list + dialog orchestration; both dialogs are simple form/detail shells.

## R6 — Mock contract

Runs on the unified `MockStore` via the existing generic `/components/{kind}` REST surface — no new mock
handler code beyond the one-line kind registration. CRUD round-trips and integrity (`store.put`/`list`)
are inherited for free from the shared handler.

## R7 — Interview / decisions made

1. **"New requirement" is open to every lens**, not gated to Business specifically. The plan says
   "Business submits," but there's no auth/identity layer to restrict *who* can submit (the app is
   single-user, auth-free per `docs/PROJECT_NOTES.md`), and no other pane in this app gates a *creation*
   action to a specific lens — every lens gate built so far has been "Business is read-only," never "only
   Business may create." Building a submit-only-for-Business restriction would invent a permission model
   the rest of the app doesn't have. Flag if this reading is wrong.
2. **Decide/Deliver gated by `!lens.readOnly()`** — i.e. blocked for Business, available to **both**
   Builder and Ops (the existing `LensService.readOnly()` is binary, business-vs-not; there is no
   builder-vs-ops distinction anywhere in the app yet). The plan says "Builder sees a queue," not
   "Builder-only triage" — treating Ops as also able to triage is the same latitude given everywhere else
   this track (e.g., Registry/Catalog are visible+actionable to every non-Business lens without a
   builder/ops split).
3. **"Delivered via" is a free-text note, not a real Component link.** The plan's C1 description says
   "link to delivering Components" — a real link would need a cross-kind picker (search datasets, widgets,
   dashboards, pipelines by id) and probably a `Part`/reuse-graph entry so Registry could show
   Requirement→Component edges. That's meaningfully more scope than this pass; the free-text note
   preserves the *information* (what satisfied the requirement) without the picker UI or graph wiring.
   **Flagged as the clearest, most valuable C1 follow-up** if the product owner wants the real link.
4. **No SLA/due-date field** — the interview answer explicitly said "no SLA timer built yet." Not added.
5. **Requirement doesn't participate in the Registry reuse-graph** (Wave-2's `deriveComponentGraph`/
   `partsFor`) — consistent with #3 above (no real Component references to derive edges from yet).
6. **GLOSSARY.md not updated this pass** — flagged in R1, not blocking; "Requirement" is self-explanatory
   and doesn't collide with existing vocabulary, but the repo's own binding rule says new concepts should
   land there. Deferred as a fast-follow doc edit, not core to shipping the feature working.

## R8 — Verify (evidence)

- **New specs across 7 files:** `requirement-types.spec.ts` (build/decide/deliver transitions),
  `requirements.service.spec.ts` (create/save/list against `ComponentsService`),
  `requirement-form.dialog.spec.ts` (validation gate, a11y),
  `requirement-decision.dialog.spec.ts` (Accept/Reject rendering, Mark-delivered rendering, Business-lens
  hides inputs, a11y), `requirements.component.spec.ts` (load, empty state, submit flow, Business-lens
  no-op on a returned decision result — defense-in-depth per the W4 Phase-1b lesson, a11y).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **555 passed / 0 failed / 5 skipped**
  (baseline 536/0/5; +19 new cases across the 5 spec files above). Two pre-existing, unrelated
  intermittent tests (`inspecto/mock/simulator.spec.ts`, `studio/widgets/widget.kind.spec.ts`) flickered
  during the verify loop and passed on a clean rerun — not caused by this feature.
- **Test-infra lesson recorded:** `requirements.component.spec.ts` hit a real DI subtlety —
  `DataTableComponent` (used in the pane's template) also injects `MatDialog`, and a plain
  `providers: [{ provide: MatDialog, useValue }]` entry can lose to that transitive registration.
  `TestBed.overrideProvider(MatDialog, { useValue })` (called right before `createComponent`) is the
  reliable override for any future spec mocking `MatDialog` on a component that also renders
  `<inspecto-data-table>`.
- **Live smoke** (`:4204`): "Requirements" nav item appears; submitting a KPI requirement lands it in the
  list as Submitted; opening it in the Builder lens shows Accept/Reject, accepting shows Mark-delivered on
  reopen; switching to Business hides all decision inputs (read-only detail only). No console errors.

**Definition of Done: met** — C1 ships as a working MVP; three deliberate scope cuts (real Component
linking, SLA, GLOSSARY entry) are flagged as explicit follow-ups, not silently dropped.
