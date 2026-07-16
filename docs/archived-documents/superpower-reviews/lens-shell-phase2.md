# Review sheet — W4 Lens shell, Phase 2 (per-lens home page) — W4 COMPLETE

**Wave:** 2 (Builder: Studio), W4 item · **Date:** 2026-07-03 · **Files:** `app.routes.ts` (+spec).

Closes the final open item from `reviews/lens-shell.md` (Phase 1/1b). Plan §4's W4 scope was "Nav model
tagged with lenses; header 'View as' switcher (persisted per user); per-lens home page." The switcher and
Workbench read-only gating shipped in Phase 1/1b; **nav-lens tagging was explicitly declined** (see below);
this pass ships the **per-lens home page**, closing W4.

## Product-owner clarification (resolved before starting)

Phase 1's confirmed decision — Business sees the identical nav as every other lens, only authoring
actions are gated — undercuts the premise of the plan's original "every route declares `lenses: [...]`;
the switcher filters" nav-tagging mechanism. Asked directly: **confirmed 2026-07-03 — no nav filtering
for any lens (Business, Builder, or Ops).** Every lens can reach every route via nav; only actions are
gated where a rule exists (currently just Business/Workbench). **Per-route `lenses: [...]` tagging is not
built and is not planned** — there is no consumer for it now that nav filtering is off the table.

## R1 — Glossary

**Lens** (`docs/GLOSSARY.md` §1-A), no new terms.

## R2 — Attribute audit

`LENS_HOME: Record<Lens, string>` — one route per lens, chosen as the **first-listed pane** for that lens
in the plan §1 persona→surface map (the most direct, traceable source rather than a fresh product call):
- **Business** → `kpi-reports` (mission: "raise KPI/Report/Reconciliation requirements").
- **Builder** → `pipelines` (mission: author in the Workbench; Pipelines editor is the first-listed pane).
- **Ops** → `events` (mission: built-in operational features; Events is the first-listed pane).

## R3/R4 — UX pass + reuse

No new UI — a route-level redirect function (`redirectTo: lensHomeRedirect`, Angular's function-form
`Route.redirectTo`, available since v16) replaces the previous static `redirectTo: 'dashboard'` on the
root `''` path. No new component, no new dependency; `LensService.currentLens()` is read via `inject()`
inside the redirect function (executes in the router's injection context, same as a functional guard).

## R5 — Logic extraction

`LENS_HOME` is a plain exported map — the redirect function is a 1-line lookup, already about as
extracted as it can be. No further extraction warranted.

## R6 — Mock contract

No API/mock changes — purely a client-side routing decision.

## R7 — Interview / decisions made

1. **Nav-lens tagging declined entirely** (see clarification above) — the plan's original mechanism for
   this is now dead scope; not built, not deferred-with-a-plan-to-build, just off the table given the
   confirmed single-console decision.
2. **Home routes chosen from the plan's own persona table** (first-listed pane per lens) rather than
   inventing new picks — traceable, no new product decision needed. Flag if a different landing page is
   wanted per lens (e.g., Business → `dashboard` instead of `kpi-reports`).
3. **The space switcher still hard-reloads to `/dashboard`** on space change (unrelated pre-existing
   behavior, `SpaceSwitcherComponent.switch()`) — not updated to route through `LENS_HOME` in this pass.
   It's a legitimate, low-risk consistency improvement (land on the lens home after a space switch too)
   but is out of scope for "per-lens home page" as specified (that item is about the app's default
   landing route, `''`, not every navigation event) — flagged, not fixed.

## R8 — Verify (evidence)

- **New spec:** `app.routes.spec.ts` — `LENS_HOME` map contents, and `lensHomeRedirect()` resolves
  correctly for each of the three lenses (via `TestBed.runInInjectionContext`).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **522 passed / 0 failed / 5 skipped**
  (baseline 520/0/5; +2 new cases). No TS error on Angular's function-form `redirectTo` — the first use
  of this pattern in the codebase type-checked cleanly.
- **Live smoke** (`:4204`): switch to Business → reload at `/` → lands on KPI & Reports; switch to Ops →
  reload at `/` → lands on Events; switch to Builder → reload at `/` → lands on Pipelines. No console
  errors.

**Definition of Done: met.** **W4 (persona lens shell) is now fully complete**: switcher + persistence
(Phase 1), Workbench read-only gating incl. defense-in-depth on canvas mutation methods (Phase 1b),
per-lens home page (this phase). Nav-lens tagging was evaluated and explicitly declined, not deferred.
