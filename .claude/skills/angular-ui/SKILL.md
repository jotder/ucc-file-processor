---
name: angular-ui
description: >
  Senior Frontend Architect rules for the inspecto-ui Angular SPA. MUST be read and applied BEFORE
  generating or modifying ANY frontend artifact in inspecto-ui/ — components, panes, services, forms,
  styles, routes, tests, or docs. Encodes the project's feature-based architecture, the shared design
  system (status-badge / empty-state / skeleton / grid / connectivity-banner), the no-hardcoded-color
  CI guard, WCAG 2.2 AA + axe-core gate, API / error / connectivity / optimistic-UI patterns, signals
  state, and lazy routing. Trigger on any inspecto-ui change or new pane.
---

# Inspecto Frontend Architecture (Angular UI)

> Durable inspecto-ui conventions & gotchas (mode-toggle lens, lint:tokens guard, ag-Grid refresh/theme/
> virtualization, NG8011, auth-free client, CSV-blob download, live-tail, connectivity banner, optimistic
> mutate, G6 reuse): [docs/PROJECT_NOTES.md](../../docs/PROJECT_NOTES.md) §6.

You are acting as a **Senior Frontend Architect**. The goal is not merely to make things work, but a
frontend that stays **maintainable, scalable, testable, and consistent** over years. Assume this app
becomes enterprise-scale.

> **Before writing any code, read this file and the linked artifacts. After writing, satisfy the
> Definition of Done (§12).** The full a11y findings live in `docs/ui/accessibility-audit.md`; the
> living component gallery is the in-app `/design` route (`modules/admin/design-system/`).

## 0. Non-negotiables (the build breaks or review fails otherwise)

1. **No hardcoded colors** (hex / literal `rgb()/rgba()`), **no `levelClass`-style status-color
   helpers**, and **no status-tinted background fills** (`bg-{red|amber|green|…}-NNN` — the tell-tale of a
   hand-rolled pill/banner) in `src/app/inspecto/**` or `src/app/modules/admin/**`. Enforced by
   `npm run lint:tokens` (CI step in `.github/workflows/ui.yml`). Allowlist (the sanctioned color owners) =
   `theme/chart-tokens.ts` + `components/status-badge.component.ts` + `components/alert.component.ts` +
   `components/connectivity-banner.component.ts`. Use `--gamma-*` vars / Tailwind classes /
   `<inspecto-status-badge>` / `<inspecto-alert>`. (`text-*`/`border-*` tones are NOT flagged — legit inline
   emphasis / required-field asterisks.) Escape hatch: `ds-allow` on the line.
2. **Reuse the shared design system** — never re-roll a status pill, **inline alert/banner**, empty state,
   skeleton, grid theme, confirm dialog, or connectivity banner.
3. **Reactive forms only** with inline `<mat-error>` + `markAllAsTouched()` on invalid submit.
4. **A11y is not optional** (§6): one `<h1>` per page, `aria-label` on icon-only buttons, `:focus-visible`
   ring (never bare `outline:none`), WCAG 2.2 AA. Add an axe-core assertion to new component specs.
5. **No new dependencies** without explicit justification — keep the bundle lean.
6. **Never round-trip a raw secret** — secrets are `${ENV:…}` references; mask is `***`.
7. **Commit policy:** `feat:`/`test:`/`docs:` → `master` only; `fix:` → oldest supported branch then
   merge-forward. **Never push without an explicit user ask + confirming the merge-forward set.**

## 1. Philosophy

Maintainability over cleverness · Consistency over preference · Reusability over duplication ·
Explicitness over magic · Accessibility over aesthetics · Simplicity over abstraction ·
Incremental evolution without breaking existing features.

## 2. Technology assumptions (do not deviate without justification)

| Concern | Choice |
|---|---|
| **Framework** | Angular 21, **standalone components** (no NgModules), new control flow (`@if/@for/@switch`) |
| **Language** | TypeScript (strict); prefer explicit return types; `inject()` over constructor params in new code |
| **State** | **Angular signals** (local + service-held shared state) + **RxJS** for async/streams. **No NgRx / global store.** |
| **UI** | Angular **Material (M2)** + **Tailwind** on the gamma/Fuse shell; **ag-Grid 35** tables; **Chart.js** charts; **AntV G6** graphs |
| **Forms** | **Reactive** (`FormBuilder`/`FormGroup`/`Validators`) + inline `<mat-error>`. Template-driven `ngModel` is legacy — do not add it. **Config-attribute forms are schema-driven**: declare `AttributeSpec[]` (tier: required \| optional \| advanced) in `inspecto/component-model` and render with `<inspecto-schema-form>` (pilot: jobs `job-form.dialog`; demo: `/design`). Hand-build only genuinely bespoke sections (canvases, key/value arrays). **`tier` = visibility bucket; validation is separate** — set `required: false` on a `tier:'required'` spec for an always-visible-but-optional field (option sheets where every knob shows, none mandatory; e.g. `widget-option-attributes.ts`). For an inline duplicate-id guard, attach a `uniqueNameValidator` to the schema-form's name control (`errorFor()` renders the generic `duplicate` message) — see `job-form.dialog`/`dataset-editor` |
| **Testing** | **vitest** via `@angular/build:unit-test` (jsdom) + `TestBed`; **axe-core** a11y via `expectNoA11yViolations`. Two recurring compile/run traps: (a) **one `TestBed.configureTestingModule` per test** — a helper that configures TestBed must be called **once** per `it()`; to assert several states, build one fixture and mutate its `@Input`s/`detectChanges()` between assertions (don't call the helper twice). (b) **no spread over `NodeListOf`** — use `Array.from(el.querySelectorAll(...))`, not `[...el.querySelectorAll(...)]` (TS2488 under the test tsconfig). |
| **Package mgr** | **npm** (`npm ci` in CI — keep `package-lock.json` in sync when adding deps) |

`graphify` indexes only the Java backend — for UI work, read the TS directly (graphify won't orient you).

## 3. Architecture — feature-based

```
src/app/
  inspecto/                 # SHARED / CORE (cross-feature). Never import a feature from here.
    api/                    # @Injectable({providedIn:'root'}) services + barrel index.ts
    components/             # shared UI: status-badge, empty-state, skeleton, chart, connectivity-banner, schema-form
    grid/                   # ag-Grid theme + helpers (index.ts)
    mock/                   # THE unified mock backend: MockStore (per-space, localStorage-persisted) +
                            # framework-free domain handlers + ONE mockApiInterceptor. New mock endpoints
                            # go here as handlers — never as a new per-feature mock interceptor.
    theme/                  # chart-tokens.ts (the ONLY place canvas colors are hardcoded)
    testing/                # a11y.ts (expectNoA11yViolations)
    auth.service.ts, confirm.service.ts, …
  modules/admin/<feature>/  # FEATURES: standalone component(s) + .html + <feature>.routes.ts
  layout/                   # app shell (connectivity-banner mounts here)
  mock-api/common/navigation/data.ts   # nav items
```

- A feature = `modules/admin/<feature>/`: `*.component.ts` (+ `.html`), optional `*.dialog.ts`, and
  `<feature>.routes.ts` (`export default [...] as Routes`).
- **No cross-feature dependencies.** Share via `inspecto/`. A pane may be reused across routes via
  `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Vendored** gamma/Fuse code (`src/@gamma/**`, `modules/auth/**`) is out of scope — don't restyle, audit, or guard it.

## 4. Component design

- **Standalone** + `ChangeDetectionStrategy.OnPush` (default for new components).
- Separate **container** components (inject services, hold state) from **presentational** shared
  components (`@Input`/`@Output`, no HTTP, in `inspecto/components`).
- **Always reuse:** `<inspecto-status-badge [value]>` (or `statusBadgeHtml()` in cell renderers),
  `<inspecto-alert variant=info|warning|error|success [title]>` (inline per-screen notices — writes-disabled,
  feature-unavailable, test-result banners; message is content-projected, announces `status`/`alert` by
  variant), `<inspecto-empty-state>`, `<inspecto-skeleton>`,
  `InspectoConfirmService.confirm()/confirmDestructive()`, the grid helpers, `<inspecto-connectivity-banner>`
  (the app-wide offline/backend-down strip, already mounted in the layout — distinct from `<inspecto-alert>`).
- **Tabular surfaces → `<inspecto-data-table [tier]>`** (`app/inspecto/data-table`), the consolidation of
  every ag-Grid host. Tiers: **mini** (grid) · **standard** (+ icon-only toolbar: column chooser · search ·
  CSV export) · **pro** (+ an always-on **CodeMirror SQL editor** that runs real SQL offline via **AlaSQL**
  and re-renders the grid, + an icon-toggled filter builder that regenerates the SQL) · **proMax** (+ "save
  as rule" → a parameterized `:fieldValue` template via the rule store). Reusable logic is framework-free in
  `core/` (csv · quick-filter · column-resolve) and `sql/` (`runSql` lazy-loads AlaSQL; `SqlHistoryService` =
  per-source history/favorites in `localStorage`; `codemirror-setup.ts` themes CM6 entirely with `--gamma-*`
  vars). The CM editor is `@defer`-loaded so mini/standard never pull CodeMirror; **a `@defer` block means
  the spec must `await TestBed.compileComponents()` before `createComponent`**. New deps (justified, lazy):
  `alasql` (offline SQL — also add to `allowedCommonJsDependencies`), `codemirror` + `@codemirror/*` +
  `@lezer/highlight`. Don't re-roll a bare `<ag-grid-angular>` host or a second SQL engine.
- **ag-Grid internals** (used inside the data-table, rarely direct): `app/inspecto/grid`
  (`INSPECTO_DEFAULT_COL_DEF`, `actionsColumn`, `fmtDateTime`, `InspectoGridThemeService`, `noRowsOverlay`).
  Bind `(firstDataRendered)` AND `(rowDataUpdated)` → `refreshActions($event)` or action icons don't render.
  **Gotcha:** static `rowData` present at first render also skips **string-returning** cell renderers — force
  `api.refreshCells({force:true,columns:[…]})`.
- **G6 graph hosts — two patterns.** *Read-only* (`catalog/graph-view.component`) rebuilds the `Graph` on every
  data/scheme change — fine for static views. *Interactive editing* (`flows/flow-editor-graph.component`, T32) keeps a
  **persistent** `Graph` and mutates it in place (`add/remove/updateNodeData` + `draw()`), rebuilding only when the
  subject changes (an `@Input graphKey`) so user layout survives edits; node-add = HTML5 drop-to-add, edge-add =
  two-click (avoid G6 v5's `create-edge`), delete = a host `keydown`. Both reuse `canvasTheme()` + `nodeColor/nodeShape`
  (never hardcode canvas colours). The read-only host defaults to a `62vh` page band; pass `[fill]="true"` inside a
  full-height flex column (Link Analysis studio) to grow into the remaining space — its `ResizeObserver` re-sizes the
  canvas live when collapsible side panes open/close. Further opt-ins on the read-only host: `[display]`
  (`GraphDisplayOptions` — label toggles + per-kind colour/shape/pattern/size overrides, what Link Analysis persists with a saved view),
  `[tooltips]="true"` (G6 hover tooltip plugin), `(edgeClick)`, and `fitView()`. **G6 can't instantiate in jsdom** —
  unit-test on the empty/no-graph path (canvas not mounted) for axe, and the editing logic via the component's methods
  with a mocked host.
- **Ask the minimum (product-owner rule, 2026-07-02):** a form asks only what the action needs NOW;
  everything else is on-demand. Concretely: **create flows name the artifact at SAVE time** (a save step
  asks Name — pre-filled `<type>_<host>`-style, unique, = the id — plus optional Description) and
  **rarely-used sections (tunnels/proxies/advanced) start collapsed even on edit**, with a chip hinting
  what's configured. Reference: `connections/connection-form.dialog` (two-step create, collapsed Routing).
- Detail pages carry a breadcrumb (list → id). Reference everything live at `/design`.

## 5. Styling

- **Tailwind utilities + gamma `--gamma-*` CSS vars** (scheme-aware, set on `body.light`/`.dark`). No
  hardcoded colors (§0.1).
- **Status/severity/level color → only** `status-badge.component.ts`. **Canvas color → only**
  `theme/chart-tokens.ts` (Chart.js can't read CSS vars).
- **ag-Grid theme → only** `InspectoGridThemeService` / `GAMMA_GRID_PARAMS`. Never bare `themeQuartz`.
- Editing the theming plugin (`@gamma/tailwind/plugins/theming.js`) does **not** hot-reload — restart the
  dev server and verify via `getComputedStyle(body).getPropertyValue('--gamma-…')`.

## 6. Accessibility — WCAG 2.2 AA

- One semantic `<h1>` per page. `aria-label` on every icon-only button. Never `outline:none` without a
  `:focus-visible` ring. `scope` on table headers. Status conveyed by **text + color**, never color alone.
- Reactive forms surface errors inline via `<mat-error>`. Respect `prefers-reduced-motion`.
- Async / degraded states announce via `role="alert"` + `aria-live` (see the connectivity banner).
- **Automated gate:** add `await expectNoA11yViolations(fixture.nativeElement)` (`inspecto/testing/a11y.ts`)
  to new component specs. Runs in CI via `npm run test:ci`. `color-contrast` + page-level rules are
  excluded in jsdom — contrast is covered by the token guard + the manual audit.
- Known/deferred findings: `docs/ui/accessibility-audit.md` (e.g. F1 chart `<canvas>` text alternative).

## 7. API integration

- Service per resource in `inspecto/api/`, `@Injectable({providedIn:'root'})`, `private http = inject(HttpClient)`,
  return `Observable<T>`. Build URLs with `apiUrl('/path')`, query with `toParams({…})` (both `api-base.ts`).
  Declare interfaces inline. **Export from the `index.ts` barrel** (`import { X } from 'app/inspecto/api'`).
- **No auth in the core / Personal edition** (removed 2026-06-16): there is no `authInterceptor`, `TokenStore`,
  `InspectoAuthService`, route guard, or `/connect` screen — requests carry no bearer and the app boots straight
  to `/dashboard`. Don't reintroduce per-screen auth or `canControl`/`canAssist` gating. (Standard/Enterprise
  editions re-add auth out-of-band via the security module + an `Authenticator` SPI — not in this tree.)
- **Downloads** (CSV/blob) go through `HttpClient` (responseType `blob`/`text`) + an object
  URL — a plain `<a href>` to the API skips the token and 401s.
- **Live tail / polling** uses `visibleInterval(ms)` (pauses when the tab is hidden); unsubscribe in
  `ngOnDestroy`/`takeUntilDestroyed`.
- **Secrets:** references only (`${ENV:…}`); never echo a raw secret back to the server (`***` sentinel
  means "keep stored value").
- **Multi-space scoping (do NOT re-roll per feature):** the server hosts many isolated spaces. `SpacesService`
  (`inspecto/api`) holds the active space as a signal (`currentSpaceId`, restored from `localStorage` in its
  ctor) and the global `spaceInterceptor` rewrites `/api/<path>` → `/api/spaces/<id>/<path>` for every feature
  call — so feature services stay space-agnostic. It no-ops when there's no active space (single-tenant,
  byte-identical) and exempts server-global/already-scoped paths (`/health`,`/ready`,`/metrics`,`/spaces*`).
  Detect the mode via `GET /spaces/_meta` → `{multiSpace}` (never infer from the space-list length). The header
  `space-switcher` and the `modules/admin/spaces` admin view are the only space-aware UI; switching reloads.
- **Persona lens ("View as") + the Capability seam:** `LensService` (`inspecto/api`) mirrors
  `SpacesService`'s shape (signal + `localStorage` restore/persist) for the three lenses
  (business/builder/ops — `docs/GLOSSARY.md` §1-A). A lens is a **UI-side annotation, never a permission**
  (Lens ≠ Role — RBAC is security-module scope; design: `docs/superpower/rbac-groundwork.md`). Panes gate on
  the **named capability signals** — `lens.canAuthorWorkbench()` (Workbench create/edit/delete),
  `lens.canOperateRuns()` (Runs trigger/pause/reprocess), `lens.canTriageRequirements()` (C1 triage) —
  **never on `readOnly()`/lens identity**; add a new named capability per distinct authorization question
  (today they derive from the lens; under RBAC they re-derive from role grants with no pane changes).
  Default heuristic: operational actions (run-now, enable/disable, dry-run, activate) stay available in
  every lens — gate only true config-authoring — *unless* the plan explicitly says otherwise for a pane
  (Runs is "read-only observe" for Business, hence `canOperateRuns`). Gate the **mutating method**
  (defense-in-depth), not just the button, on canvas/drag surfaces. Unlike the space switcher, switching
  lens does **not** reload — capabilities are computed signals read directly in templates. Header
  `lens-switcher` mounts next to `space-switcher`, classic layout only.

## 8. Error handling

- **Global `errorInterceptor`:** **`status 0`** → `ConnectivityService.reportUnreachable()` (drives the
  persistent banner) — **do not** add a per-screen "unreachable" toast. Any success → `reportReachable()`.
  **`503` is per-screen** (e.g. assist disabled), NOT backend-down. (No 401 handling — the app is auth-free.)
- **Shell layout:** `layout.component` is a flex **column**; the `<inspecto-connectivity-banner>` is its first
  child and is `display:contents` (consumes no space when hidden, stacks full-width on top when shown). Don't
  give layout-level siblings a growing `flex` or they'll steal width from the content column.
- **Per-call:** surface `apiErrorMessage(err, fallback)` via `ToastrService`. Independent fetches
  **degrade gracefully** (one failing call must not blank the whole page — fetch outside the core `forkJoin`).
- **Offline / backend-down:** `ConnectivityService` (signals) + `<inspecto-connectivity-banner>`
  (`role=alert`, Retry → `/health`). Already mounted in `layout.component`.

## 9. Performance

- Lazy-load every feature route. `OnPush` + signals. `trackBy`/`track` in `@for`. Unsubscribe via
  `takeUntilDestroyed(destroyRef)`. `visibleInterval` pauses hidden polling. Keep bundles lean (no heavy
  deps; production build budgets are the gate). Mind ag-Grid column virtualization when asserting in tests.

## 10. State management

- **Signals** for component + shared service state; `computed()` for derived; `effect()` sparingly. RxJS for
  async pipelines and HTTP. **No global store / NgRx.**
- Shared cross-cutting state lives in a root service exposing signals (pattern: `ConnectivityService`).
- **Mutations: optimistic by default for reversible toggles/edits** — use `optimisticMutate({apply, commit,
  reconcile, rollback, onError})` (`inspecto/api/optimistic.ts`): apply locally now, reconcile with the
  server result on success (silent), roll back + toast on error. Reassign arrays (`rows = [...rows]`) so the
  grid re-renders. Keep request→refetch for create/destroy or server-computed results.

## 11. Routing

- Lazy `{ path, loadChildren: () => import('app/modules/admin/<f>/<f>.routes') }` in `app.routes.ts` (no auth
  guard — the app is auth-free). Each `<f>.routes.ts` is `export default [...] as Routes`. Default route → `dashboard`.
- **Adding a page = two edits:** the lazy route in `app.routes.ts` **and** the nav item in
  `mock-api/common/navigation/data.ts` (4 collapsable groups: Pipelines / Acquisition / Operations /
  Settings, + Dashboard/Assistant basics). Detail routes carry breadcrumbs.
- Global search (`layout/common/search`) is a client-side jump-to-page palette over the nav — not a backend
  search.

## 12. Definition of Done (run before claiming completion)

1. `npm run lint:tokens` — design-system guard green.
2. `npm run build` (production) — AOT type-check + budgets green.
3. `npm run test:ci` — unit + a11y specs green (add an `expectNoA11yViolations` for new components).
4. **Verify in the preview** (`.claude/launch.json` servers): load the route, confirm behavior in the DOM
   (`preview_eval`/snapshot — screenshots time out in this env), check `preview_console_logs` for errors.
5. If a pattern changed, update the `/design` gallery **and this skill** (the shared, profile-independent
   source of truth). Do **not** record UI conventions in per-profile session memory — teammates on this
   sandbox (e.g. `jotder`) each run under a different Windows profile and won't see it.
6. Commit per the `release-workflow` skill (§0.7); push only on explicit ask after confirming the
   merge-forward set.

---

**Source of truth for current patterns (all shared, profile-independent):** this skill, the in-app
`/design` gallery (`modules/admin/design-system/`), and `docs/ui/accessibility-audit.md`. When code and
this skill disagree, fix one of them — don't silently diverge. For build/test/run commands see the
`build-verify` skill; for backend API contracts see `java-backend`.
