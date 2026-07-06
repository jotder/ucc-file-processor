# Menu Builder — user-curated navigation for business users

**Status:** DRAFT — awaiting user sign-off (2026-07-06)
**Owner track:** frontend / space-builder line
**Related:** `frontend-review-and-completion-plan.md` (D1 one-console), `component-model-adoption-plan.md`
(menu-placement = shared-per-space), `GLOSSARY.md` (Space / Widget / Dashboard / Report).

## 1. Goal

Let a user assemble a **Menu** tree (top-level menus + sub-menus) and place published library
artifacts under the leaves, so business users navigate their own domain structure — e.g.
`Revenue › TopX › {top usages, top billed, top roamed}` and `FMS › {fraud categories…}`. The tree is
**persisted** and the sidebar **refreshes** from it. Each leaf opens via a single **dynamic,
parameterized route** that renders whichever component the leaf is bound to.

## 2. Locked decisions (from the user, 2026-07-06)

| # | Decision | Choice |
|---|---|---|
| D1 | Persistence (this iteration) | **Mock-first** — persist via the existing navigation API backed by MockStore/localStorage; define a forward-compatible JSON contract so a real Java endpoint drops in later with no UI change. |
| D2 | Ownership | **Shared per space** — one Menu tree per Space (matches the repo's shared-per-space config model). Per-user awaits RBAC. |
| D3 | Surface | **Same sidebar, new groups** — custom menus render as new top-level groups in the one existing left sidebar (aligns with the locked one-console D1 in the frontend plan). |
| D4 | Placeable kinds | **Dashboards + Widgets + saved views** (Link-Analysis / Geo-Map). One generic host route renders whichever kind a leaf binds. |

## 3. Vocabulary (proposed — add to GLOSSARY as step 0)

- **Menu** — a user-curated navigation grouping shown in the sidebar; may nest (sub-menus). Shared per Space.
- **Menu item** — a Menu leaf **bound to a Component** (Widget / Dashboard / saved View) that it opens.
- **Menu Builder** — the Settings surface that authors the Menu tree.
- The bound artifacts keep their canonical names (**Widget** / **Dashboard** / **View**). We do **not** call
  them "Reports" (Report = operational run-health / scheduled delivery per GLOSSARY §Report).

## 4. Architecture

### 4.1 Data model (framework-free, `inspecto/menu/`)
```ts
interface MenuNode {
  id: string;                 // stable, = the route param
  title: string;
  icon?: string;              // gamma svg icon name
  order: number;
  children?: MenuNode[];      // sub-menus (a group node); mutually exclusive with `binding`
  binding?: MenuBinding;      // leaf: what this item opens
}
interface MenuBinding { kind: PlaceableKind; componentId: string; }
type PlaceableKind = 'dashboard' | 'widget' | 'link-analysis-view' | 'geo-map-view';
interface MenuTree { space: string; version: 1; nodes: MenuNode[]; }
```
Pure ops live in a testable `MenuStore` class (add/rename/move/reorder/attach/detach/delete, validate
unique sibling titles) — no Angular, mirrors `DataTableController` / `SavedViewStore` style.

### 4.2 Services (`inspecto/api` or `inspecto/menu`)
- **`MenuService`** `@Injectable({providedIn:'root'})`, signal-backed. Loads the tree for
  `SpacesService.currentSpaceId()`, exposes `tree = signal<MenuTree>`, mutations persist then re-emit.
  Mock persistence via a `menus` MockStore collection + `localStorage` (versioned key), served by the
  navigation mock API (below). `refresh()` re-fetches.
- **Favorites** = a **client-local** overlay (localStorage set of `menuItemId`), *not* in the shared tree
  (favorites are personal; shared tree is per-space). Surfaced as a virtual "Favorites" group.

### 4.3 Navigation integration (the "refresh from backend" seam)
The sidebar already loads from `api/common/navigation` via `NavigationService`
(`core/navigation/navigation.service.ts`). Change the **mock** `NavigationMockApi` handler to **merge**
the static platform nav with the persisted Menu tree (converted `MenuNode → GammaNavigationItem`, leaves
`link = /w/<nodeId>`). Builder mutations persist, then call `NavigationService` re-fetch so the sidebar
updates reactively. No per-feature nav interceptor; the existing seam is reused.

### 4.4 Dynamic / parameterized routing
Add ONE route: `{ path: 'w/:nodeId', loadComponent: MenuItemHostComponent }`. The host reads `nodeId`,
looks up the binding in `MenuService`, and renders the bound artifact through the existing render seams:
- `dashboard` → the dashboard viewer,
- `widget` → the widget render host (`inspecto/viz` `viz-render` / async `registerVizComponent`),
- `link-analysis-view` / `geo-map-view` → the existing view-widget wrappers.
Empty/broken binding → shared `<inspecto-empty-state>`.

### 4.5 Builder UI — Settings → **Menus** (`modules/admin/menu-builder/`)
- **Tree editor**: add top-level menu / add sub-menu / rename / set icon / delete / **reorder**
  (CDK drag-drop — already present via Angular Material; fall back to up/down buttons if not). Inline
  duplicate-sibling-title block via `uniqueNameValidator`.
- **Attach component**: a picker dialog listing Dashboards / Widgets / saved Views via
  `ComponentsService` / `DashboardsService`, with **search + sort + kind filter** (reuse
  `<inspecto-data-table tier="standard">` or a card grid). Select → creates a bound leaf.
- **Look & feel / favorites**: star toggle per item, Favorites virtual group, empty states, skeletons.
- Reuse design system only (status-badge / empty-state / skeleton / data-table / confirm /
  schema-form); no hardcoded colors; axe spec; one `<h1>`.
- New route in `app.routes.ts` + nav item under the Settings group in
  `mock-api/common/navigation/data.ts`.

### 4.6 Capability gate
Authoring the shared tree is a config action → gate mutations behind a **new named capability**
`lens.canCurateMenus()` (defense-in-depth on the mutating methods, not just buttons). Default today =
available (derives from lens; RBAC can narrow later without pane changes). Consumption (navigating the
menus) is ungated. *Open point O1 below.*

### 4.7 Persistence contract (mock now → backend later)
Freeze the wire shape now so the real endpoint is a drop-in:
`GET /nav/menus → MenuTree` · `PUT /nav/menus (MenuTree) → MenuTree`, space-scoped by the existing
`spaceInterceptor`. Mock implements it against MockStore; the real Java `ControlApi` route (per the
`endpoint` skill, fail-closed gate order, ConfigCodec) is a later backend slice — **not** in this
iteration (D1).

## 5. Phasing (each slice independently verifiable)

| Slice | Scope | Verify |
|---|---|---|
| **M1** | Data model + `MenuStore` pure ops + `MenuService` (mock-persisted, signal) | vitest units green (pure ops + service) |
| **M2** | Nav mock API merges the Menu tree; sidebar shows custom groups; `refresh()` updates live | Seed a tree → groups appear in sidebar; edit → refresh reflects |
| **M3** | `w/:nodeId` route + `MenuItemHostComponent` renders bound dashboard/widget/view | Click a leaf → the artifact renders; broken binding → empty state |
| **M4** | Builder pane (tree editor + component picker + search/sort) under Settings | Build `Revenue › TopX › top usages` end-to-end in preview |
| **M5** | Favorites + look-and-feel polish + a11y + `/design` note + seeded example (Revenue/FMS) | GAUNTLET green + live smoke |

Ship UI-only → **master-only** (per release-workflow; inspecto-ui is a master-line feature —
`4.x` has no inspecto-ui). Commit per slice; push only on explicit ask.

## 6. Guardrails
- **No new deps** (CDK drag-drop is already transitively present via Material — verify in M4).
- Reuse the existing nav seam + render seams; no second nav interceptor, no re-rolled grid.
- Design-system + a11y gates (`lint:tokens`, axe, `test:ci`, prod `build`) each slice.
- Keep the mock/real contract identical so the backend slice is additive.

## 7. Open points (to confirm as we go)
- **O1 — who may curate:** default allows curation in all lenses; confirm whether Business (read-only)
  should curate its own menus or only consume a builder/ops-authored tree.
- **O2 — icons:** reuse the gamma heroicons set for menu icons (picker), or free-text icon name? Default: picker.
- **O3 — seed example:** ship a Telecom "Revenue / FMS" seed so the feature demos out-of-the-box (aligns
  with the Space Templates verticals). Default: yes, in M5.
