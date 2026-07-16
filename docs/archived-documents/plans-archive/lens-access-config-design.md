# Lens Access configuration — tree-table matrix over menus & functionalities, RBAC-reusable

**Status:** P1 + P2 **SHIPPED & verified** 2026-07-14 (backend reactor 1226/0 incl. `ControlApiAccessTest`;
UI DoD green, test:ci 1262/0; offline preview walk: edit → save → mock persistence → lens switch →
filtered sidebar, both directions). P3 stays security-module scope. · **Companions:** `rbac-groundwork.md` (the binding
Lens/Role/Capability seam) · `../GLOSSARY.md` §1-A · `tree-table-design.md` (the matrix component) ·
`menu-builder-plan.md` (custom menus — a *different*, per-Space authored tree; untouched here).

## 1. What this is

A per-Space configuration that says, **for each Lens (Builder / Ops / Business), which menus, panes and
functionalities are shown** — edited in one tree-table matrix (rows = the product's menu/functionality
tree, one column per Lens), persisted to the backend through a new `/access/*` route family.

It is deliberately **not** a permission system today (GLOSSARY §1-A: a Lens is a self-selected view, never
an authorization). It is UX shaping with teeth: the same catalog + profile documents and the same matrix
UI are the artifacts real RBAC will consume — when the security module lands, the *subjects* become Roles
and enforcement moves server-side, but the tree, the grant semantics, the storage and the editor do not
change. That reuse path is the design's primary constraint (per `rbac-groundwork.md` §2: capabilities are
re-derived from role grants "in one file, no pane changes").

## 2. Vocabulary (GLOSSARY §1-A additions — binding)

- **Access Catalog** — the canonical tree of *gateable surface*: menu groups → panes → **functionalities**
  (action nodes bound to a named Capability). Derived from the live navigation config + the Capability
  seam; one per Space; versioned.
- **Access Profile** — one subject's sparse grant map over the Access Catalog. `subject.type` is `lens`
  today, `role` under RBAC. One document per subject (`lens-builder`, `lens-ops`, `lens-business`;
  later `role-admin`, …).
- **Grant** — a profile entry `nodeId → allow | deny`. Absent = **inherit** from the nearest ancestor with
  an explicit grant; the root default is **allow** (the auth-free core stays permissive-by-default, so an
  empty profile reproduces today's behavior exactly). This is the same word the Role model already uses
  ("a named grant set") — intentionally aligned.

UI copy for the Lens era says **Shown / Hidden / Inherit** (visibility language, honest about the honor
system); the persisted values are `allow`/`deny` (authorization language, what RBAC consumes).

## 3. Model

```toon
# spaces/<space>/config/registry/access-catalog/catalog.toon
access-catalog:
  name: catalog
  version: 1
  nodes:                      # tree — id/label/kind(+icon/link/capability)/children
    - id: business-group
      label: Business
      kind: menu
      children:
        - id: requirements
          label: Requirements
          kind: pane
          link: /requirements
          children:
            - id: requirements.triage
              label: Triage requirements (accept / reject / deliver)
              kind: action
              capability: canTriageRequirements
```

```toon
# spaces/<space>/config/registry/access-profiles/lens-business.toon
access-profile:
  name: lens-business
  subjectType: lens           # lens | role
  subjectId: business
  label: Business
  grants:
    workbench.author: deny
    system-maintenance-group: deny
```

- **Node kinds:** `menu` (a collapsable nav group) · `pane` (a routed leaf) · `action` (a functionality,
  bound to exactly one Capability). One action node per Capability — never one per pane sharing a
  capability (denying "author" on one pane while the same capability drives three panes would lie).
- **Effective grant** = walk self → root, first explicit grant wins, else `allow`. Denying a `menu` node
  therefore hides its whole subtree; denying an `action` disables that functionality for the subject.
- **Forward-compat:** profile grants may reference node ids not (yet/any longer) in the saved catalog —
  shape-validated strictly (values must be `allow|deny`), id existence deliberately not enforced, so
  catalog evolution never bricks saved profiles. Unknown ids are inert.
- **RBAC hand-off:** under the security module the same documents are read with `subjectType: role`;
  server-side default for roles may flip to deny-by-default there — a security-module decision, recorded
  here so nobody assumes the core's allow-default is contractual for roles.

## 4. Where the tree comes from (catalog derivation)

The UI is the source of truth for what exists on screen, so the UI **derives** the catalog and saves it:

- `inspecto-ui/src/app/inspecto/access/access-catalog.ts` — `deriveAccessCatalog()`: maps
  `defaultNavigation` (`mock-api/common/navigation/data.ts` — Business / Operations / Platform /
  System Maintenance / Settings / Assistant, with their children) into `menu`/`pane` nodes, then grafts
  the **action nodes** from a small declared map (the honest list — only capabilities that really gate
  something today):

  | Action node | Placed under | Capability |
  |---|---|---|
  | `workbench.author` — "Author Workbench content" | Platform ▸ Workbench | `canAuthorWorkbench` |
  | `runs.operate` — "Operate runs (trigger / pause / reprocess)" | Workbench ▸ Runs | `canOperateRuns` |
  | `requirements.triage` — "Triage requirements" | Business ▸ Requirements | `canTriageRequirements` |
  | `alerts.author` — "Author alert rules" | Operations ▸ Alerts | `canAuthorAlertRules` |
  | `access.configure` — "Configure lens access" | Settings | `canConfigureAccess` *(new)* |

  New nav items appear in the catalog automatically on the next save; new capabilities are one line in
  the action map. The `custom-menus-divider` and Menu-Builder custom menus are **excluded** — custom
  menus are already per-Space curated content (a different feature; merging the two trees would conflate
  "what the platform offers" with "what this team pinned").
- "Save" persists the derived catalog alongside the profiles (`PUT /access/catalog`), so the backend can
  serve it to other clients and the future security module — the user-visible contract is *the backend
  holds the tree*, the UI merely knows how to (re)derive it.

## 5. API — `/access/*` (new `AccessRoutes` RouteModule)

Served under `/api/v1` with the standard envelope automatically; space-scoped like every ComponentStore
route (writeRoot = the bound space's `config/`). Persistence = `ComponentStore` with two new
`WRITABLE_TYPES`: `access-catalog` (dir `access-catalog`, singleton id `catalog`) and `access-profile`
(dir `access-profiles`) — atomic TOON writes, id sanitization, path jail and MET-5 version history for
free.

| Route | Behavior |
|---|---|
| `GET /access/catalog` | The saved catalog, or `{version:0, nodes:[]}` when none saved yet (200 — an unsaved catalog is a state, not an error). |
| `PUT /access/catalog` | Validate shape (ids, kinds, action⇒capability) → save. |
| `GET /access/profiles` | All profiles (array). |
| `PUT /access/profiles/{id}` | Upsert; `{id}` must equal the body's `<subjectType>-<subjectId>`; grants shape-validated (`allow|deny`). |
| `DELETE /access/profiles/{id}` | Remove; 404 unknown. |

Gates (endpoint skill, fail-closed order): write-root **503** → body/shape validation **422** →
`WriteGates.safeName`/path jail **403** → act atomically. Mutations are wrapped in
`withCapability("canConfigureAccess")` — a no-op in the auth-free core, enforceable in Standard edition.
Test class: `ControlApiAccessTest` (HTTP-level, one per route family, covers every gate + round-trip).

## 6. UI — Settings ▸ Access (the matrix)

New Settings drawer **Access** (`modules/admin/access/`, + lazy `settings/access` route like its
siblings). Layout, top to bottom:

1. **Header** — title, one-line explainer ("Choose what each lens shows. Lenses are views, not security —
   roles enforce this later."), a search field, and a Save / Discard pair that appears only when dirty.
2. **The matrix** — `<inspecto-tree-table>` (`groupDefaultExpanded=-1`): rows = the Access Catalog
   (menu ▸ pane ▸ action, with nav icons on the tree column), value columns = one per profile —
   **Business · Builder · Ops** (the "View as" switcher's display order, so the matrix reads like the
   header menu). Each cell is an interactive **grant cell**.
3. **Legend** — a quiet strip decoding the three cell states for business users.

**Grant cell** (`AccessGrantCell`, an Angular cell renderer mirroring `TreeGroupCell`): a single button
cycling **Inherit → Hidden → Shown → Inherit**. Explicit states render solid (check-circle / x-circle +
word); Inherit renders the *effective* value faded with tooltip "Inherited: Shown (from Workbench)". So a
business user reads any row at a glance — what happens, and why — without understanding inheritance
first. Full keyboard + `aria-label` ("Requirements for Business: Hidden, set here. Activate to change.").

**Usability decisions (Builder + Business first):**
- Everything is visible by default; the page starts as an all-"Shown" matrix — zero-config equals today's
  behavior, nothing to undo.
- Plain-language labels come from the nav titles users already know; action rows say what they do
  ("Trigger / pause / reprocess runs"), not capability names — the capability id lives in the tooltip for
  builders.
- Search filters the tree (ancestors kept, matches auto-expanded) — 50-odd rows must never need scrolling
  hunts.
- Grants land in node `values`, so the tree-table's CSV export produces a readable access matrix for
  review/sign-off outside the app.
- Column headers carry the lens icon + a "n hidden" counter so an over-locked lens is spottable at a
  glance.
- "View as" (the existing lens switcher) is the preview: save, switch lens, see it. The page says so.

**Shared-component touch:** the tree-table's `expanded` linkedSignal currently reseeds on every `nodes`
change; the matrix rebuilds `nodes` on every cell edit (values must flow through rows for CSV/refresh), so
user expand/collapse state must survive data refreshes. Fix in `tree-table.component.ts`: linkedSignal
`computation` keeps the previous expanded set when one exists (unit-tested). This also fixes the
reconciliation host (expansion currently resets after Resolve refreshes).

**Data flow:** `AccessService` (`inspecto/api/access.service.ts`) — `catalog()`, `profiles()`,
`saveCatalog()`, `saveProfile()`. Save = PUT catalog (when first/changed) + one PUT per dirty profile.
Offline mock: `inspecto/mock/handlers/access.handler.ts` + `mockAccess` flag, registered **before**
`demoHandler` (its `/\/catalog$/` regex would swallow `/access/catalog` — the db-browser lesson).

## 7. Applying the config (what it changes at runtime)

Phase 2 of the same shift — the config must do something, but application stays small and honors the
Wave-1 decision (defaults unchanged):

- **Navigation filtering** — the classic layout filters nav items whose effective grant for the *current
  lens* is deny (pure `filterNavByAccess(items, profile)` util + tests). No route guard in the core: a
  Lens is not a permission; deep links still work read-only. RBAC adds guards later from the same data.
- **Capability re-derivation** — `LensService` capability signals AND in the profile's action grants:
  e.g. `canAuthorWorkbench = !readOnly() && access.actionAllowed('workbench.author', lens)`. This is
  precisely the "one file re-derives these signals" seam from `rbac-groundwork.md` — exercised now with
  lens profiles so the RBAC swap is proven, not theoretical. New capability: `canConfigureAccess`
  (Business lens: read-only ⇒ cannot edit the matrix).
- No profile / backend unreachable ⇒ everything falls back to today's behavior (allow).

## 8. Phasing

| Phase | Scope | Proof |
|---|---|---|
| **P1** (this shift) | Backend kinds + `AccessRoutes` + tests · catalog derivation · Access matrix UI + mock · save/load round-trip | reactor green + `ControlApiAccessTest` · UI DoD (lint:tokens / test:ci / build) · offline preview walk |
| **P2** (this shift) | Nav filtering + capability re-derivation from profiles · tree-table expanded-state fix | unit tests + preview: hide a pane for Business, switch lens, it's gone; matrix expansion survives edits |
| **P3** (security module, deferred) | `subjectType: role` profiles, server-side enforcement, role assignment UI, deny-by-default decision | out of core scope (`rbac-groundwork.md` §5) |

## 9. Explicitly out of scope

Identity/login/roles (security module) · route guards · per-user overrides · Menu-Builder custom-menu
gating (custom menus are already per-Space curation) · backend-driven navigation (nav stays a frontend
artifact; the backend stores the *catalog snapshot*, it does not serve the sidebar).
