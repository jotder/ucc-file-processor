---
type: Convention
title: Routing & Navigation
description: Lazy feature routes, the nav data file, breadcrumbs, and the client-side global search.
resource: inspecto-ui/src/app/app.routes.ts
tags: [routing, navigation, lazy-loading, breadcrumbs]
timestamp: 2026-06-28T00:00:00Z
---

# Routing & Navigation

* **Lazy-load every feature route**: `{ path, loadChildren: () => import('app/modules/admin/<f>/<f>.routes') }` in `app.routes.ts` (no auth guard — the core is auth-free). Each `<f>.routes.ts` is `export default [...] as Routes`. Default route → `dashboard`.
* **Adding a page = two edits**: the lazy route in `app.routes.ts` **and** the nav item in `mock-api/common/navigation/data.ts`.
* **Nav groups**: **Operations** · **Platform** (Workbench / Studio / Catalog) · **Settings**, plus Dashboard + Assistant — filtered by the active **Lens**. See the [features index](../features) for the screen-to-group mapping.
* **Lens Access matrix** (Settings ▸ Access): a per-Space **Access Profile** over the **Access Catalog**
  (menu groups → panes → functionalities, derived from the live nav config) grants Inherit/Hidden/Shown
  per Lens, persisted via `/access/*`. It filters the sidebar and re-derives `LensService` capabilities
  (`setActionGrants`) — deliberately **not** authorization (GLOSSARY §1-A); real RBAC swaps subjects to
  Roles on the same catalog/profile/matrix, server-enforced.
* **Custom Menus**: a user-curated per-Space **Menu** tree (Settings ▸ Menus) renders as extra sidebar
  groups; each leaf opens one dynamic parameterized host route that renders the bound artifact
  (Dashboard / Widget / saved Link or Geo view). Mock-first persistence over the navigation API.
* **Detail pages carry a breadcrumb** — the shared `<inspecto-breadcrumb>`
  (`inspecto/components/breadcrumb.component.ts`), e.g. [run-detail](../features/run-detail.md).
* **Global search** (`layout/common/search`) is a client-side jump-to-page palette over the nav — **not**
  a backend search. **Ctrl/Cmd+K** opens it app-wide (empty-query recents + shell action commands such
  as lens switch); **`?`** opens the shortcuts-help overlay (`inspecto/shortcuts-help.dialog.ts`).
* **Feature commands ride the command registry** (`inspecto/commands/command-registry.ts`): a command
  is declarative (title + router link + query params, no closures), registered via side-effect import
  (`app-commands.ts` — New incident / New case / New job), and merged into the palette by the classic
  layout — the shell never imports a feature. The target pane owns the **`?create=1` handshake**: on
  that param it strips it (`replaceUrl`) and opens its create dialog **after the strip navigation
  settles** (MatDialog closes open dialogs on navigation).

## Pane reuse across routes

A single component can serve multiple routes via `ActivatedRoute.snapshot.data`. Example:
[Incidents and Cases](../features/objects.md) are one `ObjectMailComponent` parameterized by route data
(`incidents.routes.ts` / `cases.routes.ts`).

## Context preservation (detail-over-list)

Prefer **detail as a side panel over the live list** to a routed detail page: one matcher-based route
config serves both `/runs` and `/runs/:name`, so the same component instance keeps list scroll/filter
state while the detail panel opens (deep links still work). Settings binds its selection to a
`:section` param the same way. Detail side panes are user-resizable via the shared
`InspectoSplitDirective` (`inspecto/components/split.directive.ts`,
`[inspectoSplit]="<stateKey>"` — width persists per key).
