# Review sheet — W4 Lens shell, Phase 1 (switcher + Workbench read-only gating)

**Wave:** 2 (Builder: Studio), W4 item · **Date:** 2026-07-03 · **Files:**
`inspecto/api/lens.service.ts` (+spec) · `layout/common/lens-switcher/lens-switcher.component.ts`
(+spec) · `layout/layouts/vertical/classic/classic.component.{ts,html}` ·
`modules/admin/connections/connections.component.{ts,html,spec.ts}` ·
`modules/admin/jobs/jobs.component.{ts,html,spec.ts}` ·
`modules/admin/pipelines/pipeline-editor.component.{ts,html,spec.ts}`.

**This is Phase 1 of W4, not the full item.** Plan §4's W4 scope is "Nav model tagged with lenses;
header 'View as' switcher (persisted per user); per-lens home page." This pass ships the **switcher +
persistence** and **wires the confirmed Business-read-only rule** onto the Workbench panes reviewed in
Wave 1. It does **not** build per-route nav filtering or a per-lens home page — see R7 for the scoping
rationale and what's deferred.

## Product-owner clarification (resolved before starting)

The plan's original persona→surface map (§1) lists Workbench panes only under **Builder** — implying
Business shouldn't see them in nav. The later Wave-1 interview answer says "Business is read-only across
the Builder/Workbench panes" — implying Business *can* browse them, just without write actions.
**Confirmed with the product owner (2026-07-03): Business sees the same nav as everyone; only
authoring actions are hidden.** This is what's implemented — no nav-level lens filtering.

## R1 — Glossary

**Lens** (persona-scoped view, not a permission — `docs/GLOSSARY.md` §1-A), **Business/Builder/Ops**.
All pre-existing, binding vocabulary; no new terms needed.

## R2 — Attribute audit

`LensService` mirrors `SpacesService`'s shape exactly (signal + localStorage restore/persist), scoped to
three fixed values — no attribute set to audit.

## R3/R4 — UX pass + reuse

`LensSwitcherComponent` ("View as") is a structural clone of `SpaceSwitcherComponent` — same
`mat-stroked-button` + `mat-menu` pattern, mounted immediately to its left in the classic layout header.
Unlike the space switcher, switching lens does **not** reload the page: a lens is a pure UI-side
annotation (visibility/read-only), not a data-scoping key like the active space, so components just
re-render reactively off the `LensService.readOnly()` signal. No hardcoded colors; reuses
`heroicons_outline:briefcase/wrench-screwdriver/server-stack` + the space-switcher's check-circle/chevron
convention.

**Gating applied** (Business lens hides these; every other lens is unaffected):
- **Connections**: "New connection" button, per-card "Edit"/"Delete" icon buttons.
- **Jobs**: "New job" button; the **Reschedule/Edit/Delete** row actions. **Run now** and the
  **Enable/Disable** toggle stay available in every lens — they're operational, not authoring (same
  distinction the Wave-1 jobs review already drew for its own scope).
- **Pipeline editor**: "New pipeline" button (+ its inline create form), "Save" button, the "More pipeline
  actions" menu (Delete pipeline — its only item), and "Delete selected" (node/edge removal).
  **Activate/Deactivate, Dry-run, Validate** stay available — activation is operational, dry-run/validate
  are non-mutating checks.

## R5 — Logic extraction

`LensService` is the single source of truth (`readOnly` computed) — no duplicated gating logic. Jobs'
row-action list became a getter that conditionally includes the authoring entries, mirroring the existing
`InspectoRowAction[]` shape rather than adding a new "hidden" field to the shared interface (which has no
other users of a per-action-hidden predicate today — not warranted to add one for a single consumer).

## R6 — Mock contract

No mock/API changes — this is purely client-side visibility. Nothing to reconcile.

## R7 — Interview / decisions made

1. **Scope of "Phase 1"** — deferred, and why: (a) **per-route `lenses: [...]` nav filtering** (plan §1)
   isn't built; the confirmed decision this pass (see above) makes it unnecessary for Business, and no
   other lens×pane visibility rule has been specified yet, so building the tagging mechanism now would be
   speculative. (b) **Per-lens home page** isn't built — no lens-specific dashboard content exists yet to
   route to. (c) Sources/Enrichment need no gating — already fully read-only (no create/edit/delete
   surface exists there at all).
2. **Node/parser-config dialogs are not directly gated.** Opening a node's config dialog from the canvas
   still works in the Business lens (it's a local, unsaved edit), but the pipeline editor's "Save" button
   is hidden, so any such edit has no way to persist. This is an acceptable, if slightly inconsistent,
   consequence of gating at the list/toolbar level (consistent with every other pane this track) rather
   than threading the lens into every dialog. Flagged as a real, if minor, gap — not fixed this pass.
3. **Default lens = Builder** (not Business) — chosen so a fresh session isn't unexpectedly read-only;
   this is an internal admin console where most current usage is authoring.
4. **No page reload on lens switch** (unlike the space switcher) — deliberate: a lens never changes what
   data is fetched, only what's rendered, so a reactive re-render is correct and faster than the space
   switcher's necessarily-destructive reload.

## R8 — Verify (evidence)

- **New specs:** `lens.service.spec.ts` (restore/persist/readOnly), `lens-switcher.component.spec.ts`
  (label, selection, a11y). **`connections.component.ts` had no spec at all** — added
  `connections.component.spec.ts` (load, filter, default-lens buttons present, Business-lens buttons
  hidden, a11y) since the gating change touches it directly. Extended `jobs.component.spec.ts` and
  `pipeline-editor.component.spec.ts` with one gating case each.
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **517 passed / 0 failed / 5 skipped**
  (baseline 501/0/5; +16 across 5 spec files: `lens.service.spec.ts`, `lens-switcher.component.spec.ts`,
  the new `connections.component.spec.ts`, +1 case each in `jobs.component.spec.ts` and
  `pipeline-editor.component.spec.ts`). The new connections spec needed two follow-up fixes: a missing
  `ToastrService` provider (NG0201), and a `TestBed.inject()`-before-`configureTestingModule()` ordering
  bug in the Business-lens case — both caught by the verify loop, not shipped broken.
- **Live smoke** (`:4204`): the "View as" switcher appears in the header next to the space switcher;
  switching to Business hides New/Edit/Delete on Connections and Jobs, and New/Save/Delete on the
  Pipeline editor, without a page reload; switching back to Builder restores them instantly. No console
  errors.

**Definition of Done: met for Phase 1** — switcher + persistence + Workbench-authoring gating shipped and
verified. **Not done** (tracked as W4 remainder): per-route nav-lens tagging, per-lens home page, and
node/parser-config dialog-level gating.
