# Review sheet — Space Templates gallery (W5, Wave 3 companion)

**Wave:** 3 (Business) companion, platform workstream W5 · **Date:** 2026-07-03 · **Files (all new
unless noted):** `inspecto/mock/handlers/spaces.handler.ts,.spec.ts` ·
`inspecto/mock/seeds/{templates.ts,.spec.ts, seed-utils.ts, telecom-ra.seed.ts, fraud-mgmt.seed.ts,
financial-audit.seed.ts, link-analysis.seed.ts}` ·
`modules/admin/spaces/space-template-gallery.dialog.ts,.spec.ts` · edits:
`inspecto/api/spaces.service.ts` (`SpaceTemplateInfo`, `templates()`, `template?` on create),
`modules/admin/spaces/spaces.component.ts/.html` ("New from template" + switch offer),
`inspecto/mock/{mock-flags.ts,mock-api.interceptor.ts}` + `environments/environment.ts`
(`mockSpaces` flag), `mock/seeds/default-space.seed.ts` (helpers extracted to `seed-utils.ts`),
`studio/datasets/dataset-sources.ts` (per-vertical sample rows).

**Net-new feature** (plan §2 W5, D2): a **Space Template** is a blueprint bundle of Components that
instantiates a new Space. Four vertical seed packs — `telecom-ra`, `fraud-mgmt`, `financial-audit`,
`link-analysis` — each a coherent Connections→Pipelines→Datasets(+sample rows)→Widgets→Dashboard→
Requirement→Reconciliation/Rules + Ops (jobs/runs, events, alerts, incidents/cases) story. The
Spaces admin gains a **"New space from template"** gallery (cards with contents preview → an
ask-the-minimum naming step) and offers to switch into the created space.

## Product-owner decisions (AskUserQuestion, 2026-07-03 — before starting)

1. **Mock `/spaces`, on by default in dev** — `/spaces` previously fell through to the real backend,
   so mock dev mode was single-tenant and space creation impossible. New `mockSpaces` flag (dev:
   `true`): `spaces.handler.ts` serves `_meta` (multiSpace:true), list/create/delete, per-space
   `datasources`, and the W5 `GET /spaces/templates` catalog. Dev/demo boot is now genuinely
   multi-space (header switcher appears, per-space localStorage isolation is real).
2. **Seed packs full-rich now** (not lean-then-C7): every pack carries the complete vertical story
   incl. sample rows and Ops entities. C7 remains for continuous enrichment.
3. **Template model = static TS registry + REST endpoint** — deliberate deviation from the plan's
   "SpaceTemplate as a Component kind" wording: templates are server-global and carry seed
   *functions* (not serializable into the per-space, localStorage-persisted store). The UI stays
   API-shaped (`SpacesService.templates()` / `POST /spaces {template}`) so backend cutover is a flag
   flip. Plan §2 W5 updated to match.

## R1 — Glossary

**Space Template** already canonical (GLOSSARY / plan §1: "Template is the Type, the Space the
Instance") — implemented exactly so. No banned synonyms introduced ("blueprint bundle" used only as
prose description, not as a model/API name).

## R2 — Attribute audit

`SpaceTemplateInfo { id, name, tagline, description, icon, contents[] }` (wire, in
`spaces.service.ts`) + `SpaceTemplate extends … { seed(store, space) }` (mock registry). Closed and
minimal — no versioning/author/pricing speculation. `CreateSpaceRequest` gains exactly one optional
field (`template`).

## R3 — UX pass

Gallery dialog is two-step per the **ask-the-minimum** rule: step 1 = 2-col card grid (icon, name,
tagline, contents chips; cards are real `<button>`s with aria-labels), step 2 = naming (id
pre-filled from the template id, display name/description pre-filled from name/tagline, everything
optional except id). Skeleton while loading, empty-state if the catalog is empty, toast + inline
409/400 handling. After create: list reloads + a confirm offers switching (hard reload, same
contract as the header switcher). Empty-state on the pane now points first-time users at the
template path.

## R4 — Reuse pass

`<inspecto-skeleton>`, `<inspecto-empty-state>`, `InspectoConfirmService`, the KPI-gallery card
idiom (`bg-card rounded-2xl p-5 shadow hover:shadow-md`), the existing `uniqueNameValidator` shape
(7th pane on the product-wide dup-name rule — create-only, case-insensitive). No new dependencies,
no hardcoded colors. Seed packs reuse the extracted `seed-utils.ts` (`putComponent`, `seedIconMap`)
— also un-duplicating the default pack.

## R5 — Logic extraction

The whole domain is framework-free: `spaces.handler.ts` (pure handler), `templates.ts` (registry),
the four seed packs (pure functions over `MockStore`). The dialog only orchestrates
(load catalog → choose → POST); the pane adds one method pair.

## R6 — Mock contract

REST-faithful: `GET /spaces/_meta` → `{multiSpace}`, `GET/POST /spaces` (400 bad id, 409 dup —
snake_case request keys as the real ControlApi expects), `DELETE /spaces/{id}?purge=` → the real
`DeleteSpaceResult` shape, `GET /spaces/{id}/datasources`. New surface: `GET /spaces/templates`,
`template?` on POST — the contract proposal for the real backend. Registry lives in the reserved
`_server` pseudo-space (unreachable as a real space id). An **empty** space is marked seeded on
create so the default pack can't leak into it; template spaces are seeded synchronously on POST.
Space data survives reload (localStorage); delete clears the space's data. `MOCK_STORE_KEY` NOT
bumped — no existing collection changed shape; the `_server` registry self-heals on old snapshots.

## R7 — Interview / decisions made

1. **Plan-wording deviation** (template ≠ Component kind) — asked and approved, see decisions above.
2. **Sample data included by default** (standing interview Q6) — resolved by the plan's own W5 text
   ("+ sample rows + a few Incidents/Events for Ops realism") and the "full-rich" decision; no
   config-only toggle built.
3. **Bundle export/import remain un-mocked** — those buttons on the Spaces pane still require the
   real backend (blob/zip round-trips); out of W5 scope, they toast their error cleanly. Flagged.
4. **Link-Analysis pack has no graph viz yet** — the Entity/Link Graph Visualization Type is C5; the
   pack seeds `entities`/`links` datasets + chart widgets + a ring-candidate Case so the vertical
   still demos. The seeded Requirement records C5 as the blocker, deliberately.
5. **`default` space undeletable in mock mode** (400) — keeps demo boot safe; real backend may
   differ, revisit at cutover.
6. **Ops liveness**: the simulator ticks whatever space is active, so template spaces' Runs/Events
   keep moving after switch — no per-pack simulator wiring needed.

## R8 — Verify (evidence)

- **New specs:** `spaces.handler.spec.ts` (8 cases: meta/list/catalog-without-seed-fns, empty-space
  stays empty, template space fully seeded + datasources, 400/409/unknown-template, delete semantics,
  404s) · `templates.spec.ts` (5 coherence invariants × 4 packs: non-trivial blueprint; every dataset
  resolves to SAMPLE_SOURCES rows with real columns; every widget maps real fields of a seeded
  dataset; every tile references a seeded widget; every reconciliation joins seeded datasets on
  shared keys) · `space-template-gallery.dialog.spec.ts` (cards render, pre-fill, inline dup-id
  block, POST payload + close, axe).
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **612 passed / 0 failed / 5 skipped**
  (one self-introduced TS2352 in the handler spec caught and fixed by the verify loop; re-run green
  at HEAD).
- **Live smoke** (`:4204`, mock mode): header **space-switcher live** ("Default") — multi-space mock
  works; `/spaces` shows the default card + "New from template"; the gallery renders all 4 template
  cards with contents chips; choosing Telecom RA pre-fills `telecom-ra` / name / tagline; Create →
  success toast → **switch offer** → app reloads scoped to `telecom-ra`; the store holds the full
  blueprint (2 pipelines, 3+3 datasets/widgets, dashboard, recon, requirement, connections, jobs+runs,
  events/alerts/incidents) **and the liveness simulator is already appending runs/events to the new
  space**; `/kpi-reports` lists `ra_overview`; opening it renders the KPI (**6.9** — the correct
  `sum(cost_usd)` over the sample rows) + 2 chart canvases; `/reconciliation` lists the seeded
  `switch_vs_billing`. **0 console errors** throughout.

**Definition of Done: met** — W5 ships as the last Wave-3 companion: 4 coherence-tested vertical
blueprints, a working gallery-to-switch flow, and a genuinely multi-space mock runtime. Deliberate
scope cuts (bundle export/import un-mocked; no graph viz until C5) are flagged in R7, not dropped.
