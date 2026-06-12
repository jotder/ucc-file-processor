# Inspector UI — DevExtreme → Open-Source Migration Plan

> **Status: PLANNING ONLY — not scheduled.** This document captures the analysis and a phased plan
> so the work can be picked up later *if* we decide to. Nothing here has been started. Living doc —
> edit freely.
>
> Scope: the `inspector-ui/` Angular SPA only. The Java backend (`ControlApi` + the whole
> `shared/api` HTTP contract) is unaffected.

> ### ✅ Phase 0 reality check — RESULT (2026-06-09, verified against the npm registry)
> We are on **Angular 21.2.13** (note: Angular **22.0.0** is already GA, so 21 is the *previous*
> stable major — not bleeding-edge; compat risk is lower than first assumed).
>
> | Library | Latest | Angular-21 compatible? |
> |---|---|---|
> | **PrimeNG** | `21.1.9` | ✅ peer `@angular/core ^21.0.7` — satisfied by our 21.2.13 |
> | **@primeng/themes** | `21.0.4` | ✅ framework-agnostic (the v18+ theming/dark-mode engine) |
> | **AntV G6** | `5.1.1` | ✅ framework-agnostic (no Angular coupling at all) |
> | **Chart.js** | `4.5.1` | ✅ framework-agnostic (PrimeNG `p-chart` wraps it) |
> | ag-grid-angular / -community | `35.3.1` | ✅ peer `>= 18.0.0` |
> | @angular/material | `21.2.14` | ✅ — **pin the 21.x line**; `latest` (22.0.0) needs Angular 22 |
> | @swimlane/ngx-graph | `12.0.0-alpha.4` | ⚠️ Angular 21 only via **alpha** (pre-release) |
> | @swimlane/ngx-charts | `24.0.0-alpha.1` | ⚠️ Angular 21 only via **alpha** (pre-release) |
>
> **Verdict — Option A (PrimeNG 21 + AntV G6 5 + Chart.js): fully GREEN, no blockers.** Every
> dependency has a *stable* release compatible with our exact Angular version.
> **Option B (Material + ag-Grid): GREEN with two caveats** — pin `@angular/material` to the 21.x
> line (not `latest`), and for charts use Chart.js directly (ngx-charts/ngx-graph only ship *alphas*
> for Angular 21).
>
> **Cross-cutting:** PrimeNG aligns its major to Angular's (21↔21, with per-major LTS tags), so future
> Angular upgrades get a matching PrimeNG. If an **Angular 22 bump** is planned, sequence it with the
> UI-lib choice — PrimeNG 21 peers `^21` (excludes 22), so you'd target PrimeNG 22 (will follow;
> Material 22 + ag-Grid already cover 22).
>
> The remaining Phase 0 work is the **build-one-screen spike** (theming/dark-mode/Vitest interplay) —
> the *dependency-availability* gate is now cleared.

> ### 🎯 Direction update (2026-06-11) — house template found: adopt **Option C**
> A product-line admin template now sits at **`inspector-ui2/`**: "gamma-analytics" (ThemeForest,
> Fuse-style) — **Angular 21.0.0 + Angular Material 21.0.0 + Tailwind 3.4**, Inter font,
> heroicons/feather/material icon packs, Transloco i18n, and a reusable `src/@gamma/` core
> (animations, navigation + loading-bar components, scrollbar directives, config/confirmation/
> media-watcher/splash-screen services, Material theming via `themes.scss` + custom Tailwind
> plugins). Layout shells: `vertical/classic`, `vertical/dense`, `empty`. Its `assets/api-data/` +
> `assets/lebara/` carry real UCC-domain payloads (fraud KPIs, alert distributions, IFRS margin
> dashboards, KPI browser, processing stats) — i.e. **this is the look & feel the rest of the
> product line ships with**.
>
> **Decision: the inspector should match the product line → migrate onto this template's
> Material + Tailwind shell (Option C below), not PrimeNG.** The Phase 0 npm check already
> confirmed Material 21.x is green on Angular 21. Charting/diagram stay library-agnostic
> (Chart.js + AntV G6).
>
> Caveats: (a) confirm the ThemeForest standard license covers this use before building on it;
> (b) the template's `src/assets/css/` carries legacy baggage (Bootstrap, Kendo, old DevExtreme
> themes) — strip, don't inherit.
>
> **Phase 0 spike — DONE ✅ (2026-06-11, go).** The `diagnoses` screen was rebuilt inside the gamma
> shell at `inspector-ui2/src/app/modules/admin/diagnoses/`: ag-Grid Community 35 (quick filter +
> pagination, dark `themeQuartz`), `MatDialog` detail (kv table, citation chips, .toon block +
> clipboard copy via `ngx-toastr`), Material header controls, `/api`→:8080 dev proxy
> (`proxy.conf.json`), unguarded route + nav entry. `ng build` green; live-verified at
> `:4204/diagnoses` (grid renders/filters 2→1 rows, dialog opens with full content, empty-state +
> toast on backend-down). Spike notes: (1) the template's `authInterceptor` 401-handling triggers a
> Pronto-OAuth logout flow — UCC services must use an interceptor-free `HttpClient` (HttpBackend),
> as the spike service does; (2) ag-Grid's light/dark theme is chosen at component init from the
> scheme class — Phase 1 should make it reactive to the gamma config service; (3) `?demo=1` seeds
> sample rows in the spike component — remove after spike.

---

## 1. Why consider this

| Driver | Notes |
|---|---|
| **Licensing (primary)** | DevExtreme is a commercial product (per-developer subscription). Every target below is MIT/Apache — no license cost. |
| Build/test friction | DevExtreme's ESM "directory imports" fight Vitest — we already needed a `vitest.config.ts` `deps.inline` workaround to let component specs import `devextreme-angular`. |
| Theming friction | DevExtreme themes don't follow a plain CSS-variable model; the catalog diagram needed `!important` surgery in `dx-styles.scss` (transparent surfaces, hidden focus textareas, selector overlay). |
| Bundle | DevExtreme is a large dependency; charts + diagram + grid + the full widget set ship a lot of JS. |

This is **not urgent**. The current UI works and is fully tested. This plan exists so the decision
is informed and the path is known.

---

## 2. Current-state audit (grounded — `grep` of the live code)

### 2.1 Widget inventory

| DevExtreme widget | Uses | Migration weight |
|---|---:|---|
| `dx-button` | 20 | trivial |
| **`dx-data-grid`** | **17 (9 screens)** | **heavy (volume), simple (features)** |
| `dx-text-box` | 9 | trivial |
| `dx-load-indicator` | 9 | trivial |
| `dx-popup` | 6 | small |
| `dx-select-box` | 5 | small |
| `dx-toolbar` | 4 | small |
| `dx-tabs` | 4 | small |
| `dx-date-box` | 4 | small |
| `dx-check-box` | 4 | trivial |
| `dx-scroll-view` | 3 | trivial (native/scrollpanel) |
| `dx-number-box` | 3 | trivial |
| `dx-drawer` | 2 | layout shell |
| `dx-tree-view` | 1 | small (nav menu) |
| `dx-text-area` | 1 | trivial |
| `dx-tag-box` | 1 | small |
| **`dx-pie-chart`** | 1 | **needs a chart lib** |
| **`dx-chart`** | 1 | **needs a chart lib** |
| `dx-list` | 1 | small |
| `dx-form` | 1 | rebuild with reactive forms |
| `dx-drop-down-button` | 1 | small |
| **`dx-diagram`** | 1 (catalog) | **needs a graph lib (isolated)** |

### 2.2 The decisive finding — grid feature surface

The 17 grids use **only**: `dxo-search-panel` (16), `dxo-paging` (14), `dxo-selection` (2).

**Not used anywhere:** row grouping/aggregation, master-detail rows, Excel export, pivot,
filter-row/header-filter, column chooser, inline editing, summaries.

> **Implication:** every grid feature we rely on is **free** in both ag-Grid **Community** and
> PrimeNG `p-table`. There is **no enterprise/paid grid tier requirement.** Porting off DevExtreme
> does **not** reintroduce a paywall.

### 2.3 Other touchpoints
- **Charts:** `dx-chart` + `dx-pie-chart` → need a charting lib (dashboard).
- **Toasts:** `notify()` in 8 files → a message/toast service.
- **Theming:** `dx-styles.scss`, `styles.scss`, `variables.scss`, a generated themes dir, and
  `metadata.{base,additional}.{,dark}.json` (DevExtreme Theme Builder metadata), driven by
  `theme.service.ts` + the `theme-switcher` component, **including dark mode**. Non-trivial — this
  is its own workstream.
- **Layout shell:** `side-nav-outer-toolbar` / `side-nav-inner-toolbar` / `single-card` came from
  the DevExtreme Angular template; built on `dx-drawer` + `dx-scroll-view` + `dx-toolbar` +
  `dx-tree-view` (nav menu).

---

## 3. License verdict

| Option | License | Covers our needs free? |
|---|---|---|
| **PrimeNG** (all components incl. grid, charts, toast) | MIT | ✅ Yes — `p-table` search/paging/selection, `p-chart`, `p-toast` all free |
| **ag-Grid Community** (grid only) | MIT | ✅ Yes — we use no enterprise features |
| ag-Grid Enterprise | Commercial (per-dev) | ❌ Not needed — would only matter for grouping/master-detail/Excel/pivot |
| **Angular Material** | MIT | ✅ for chrome; `MatTable` is basic (we'd hand-build search/paging — doable, more work) |
| **AntV G6** (graph, for the diagram) | MIT | ✅ |
| Chart libs (ngx-charts / Chart.js / ECharts) | MIT | ✅ |

**Bottom line:** a 100% MIT stack covers everything we use. No paid tier anywhere.

---

## 4. Recommended target stack

### Option C (recommended, 2026-06-11): **gamma-analytics template (Material 21 + Tailwind) + ag-Grid Community + Chart.js + AntV G6**
Adopt the product-line house template at `inspector-ui2/` as the app shell and component baseline.

- **Why:** visual/UX consistency with the rest of the product line is the deciding factor — the
  template *is* the product look & feel, with the theming (`@gamma` `themes.scss` + Tailwind
  plugins), layout shells (`vertical/classic|dense`, `empty`), navigation, splash screen,
  confirmation/loading services, and icon packs already built. This collapses most of Phase 1
  (theming + layout) from "build" to "adopt".
- **Stack:** Angular Material 21.x (pinned; in the template already) for chrome/inputs/dialogs/tabs,
  **ag-Grid Community** for the 17 grids (Material's `MatTable` is too bare for search+paging across
  9 screens), **Chart.js** for the two dashboard charts, **AntV G6** for the catalog diagram,
  `ngx-toastr` (already in the template) for `notify()` call sites.
- **Trade-offs:** ThemeForest license to confirm; template is Angular 21.0.0 vs our 21.2.13 (align
  minor versions); legacy `assets/css` to strip; ag-Grid is one extra dependency vs an all-Material
  dream that doesn't actually cover our grid needs.

### Option A (superseded): **PrimeNG + AntV G6**
One MIT suite for everything (grid, inputs, dialog, tabs, toolbar, toast, **charts**, drawer/layout)
+ AntV G6 for the single catalog graph.

- **Why:** lowest integration surface and the closest ergonomic match to DevExtreme's "one library
  does it all." `p-table` covers our exact grid needs; `p-chart` (Chart.js) replaces the two charts;
  `p-toast` replaces `notify`; PrimeNG has a first-class theming system with dark mode (replaces the
  Theme Builder pipeline). G6 stays isolated to the catalog (see the separate G6 brainstorm notes).
- **Trade-off:** PrimeNG is a single third-party dependency (vs. the Angular-team pedigree of
  Material). Angular-21 support is **confirmed** (PrimeNG 21.1.9, peer `^21.0.7`; see Phase 0 result).

### Option B (superseded by C, which is B + the house template): **Angular Material + ag-Grid Community + ngx-charts + AntV G6**
- **Why:** Angular-team-maintained chrome + best-in-class grid + dedicated charts + graph.
- **Trade-off:** four libraries to integrate and theme-coordinate; more glue; `MatTable` would only
  be used if we *didn't* adopt ag-Grid. Option C removes the biggest cost here (theming/layout
  glue) because the template ships it pre-integrated.

### Diagram (either option): **AntV G6** (MIT)
Isolated to the catalog. Our `catalog-graph.ts` pure mapper already produces `{nodes, edges}`; only
the rendering component changes (rename `from→source`, `to→target`; reuse `nodeColor`). See the G6
brainstorm for the wrapper sketch. Alternatives: `ngx-graph` (easiest Angular integration, lower
ceiling), Cytoscape.js (analysis-grade).

---

## 5. Component mapping (DevExtreme → Option C: Material + template + ag-Grid)

| DevExtreme | Option C target | Notes |
|---|---|---|
| `dx-data-grid` | **ag-Grid Community** (`ag-grid-angular`) | quick-filter (search), client pagination, row selection — covers all current usage; build one wrapper |
| `dx-button` | `mat-button` / `mat-icon-button` | |
| `dx-text-box` | `mat-form-field` + `matInput` | |
| `dx-text-area` | `mat-form-field` + `textarea matInput` | |
| `dx-number-box` | `matInput type="number"` | |
| `dx-select-box` | `mat-select` | |
| `dx-tag-box` | `mat-select multiple` / `mat-chips` | |
| `dx-date-box` | `mat-datepicker` (luxon adapter is in the template) | |
| `dx-check-box` | `mat-checkbox` | |
| `dx-tabs` | `mat-tab-group` | |
| `dx-popup` | `MatDialog` | |
| `dx-toolbar` | `mat-toolbar` / Tailwind flex header | |
| `dx-drop-down-button` | `mat-menu` + button | |
| `dx-list` | `mat-list` / `mat-selection-list` | |
| `dx-tree-view` | **template `@gamma` navigation component** | nav menu — comes with the shell |
| `dx-load-indicator` | `mat-progress-spinner` / template loading-bar | |
| `dx-drawer` | **template layout shell** (`vertical/classic` or `dense`) | replaces `side-nav-*` wholesale |
| `dx-scroll-view` | native overflow + template scrollbar directive | |
| `dx-form` | reactive forms + Material controls | no 1:1; assemble from controls |
| `dx-chart` / `dx-pie-chart` | **Chart.js** (thin directive/component wrapper) | dashboard |
| `dx-diagram` | **AntV G6** | catalog only; external |
| `notify()` | **`ngx-toastr`** (in the template) | 8 call sites |

<details>
<summary>Superseded mapping (DevExtreme → PrimeNG, Option A)</summary>

> PrimeNG renamed several components in recent majors (v19+): `dropdown→select`, `calendar→datepicker`,
> `sidebar→drawer`, `tabView→tabs`, `inputtextarea→textarea`. Names below are the current ones.

| DevExtreme | PrimeNG |
|---|---|
| `dx-data-grid` | `p-table` |
| `dx-button` | `p-button` |
| `dx-text-box` | `input pInputText` |
| `dx-text-area` | `textarea pTextarea` |
| `dx-number-box` | `p-inputNumber` |
| `dx-select-box` | `p-select` |
| `dx-tag-box` | `p-multiSelect` / `p-chips` |
| `dx-date-box` | `p-datePicker` |
| `dx-check-box` | `p-checkbox` |
| `dx-tabs` | `p-tabs` |
| `dx-popup` | `p-dialog` |
| `dx-toolbar` | `p-toolbar` |
| `dx-drop-down-button` | `p-splitButton` / `p-menu`+button |
| `dx-list` | `p-listbox` / `p-dataView` |
| `dx-tree-view` | `p-panelMenu` / `p-tree` |
| `dx-load-indicator` | `p-progressSpinner` |
| `dx-drawer` | `p-drawer` |
| `dx-scroll-view` | `p-scrollPanel` / native overflow |
| `dx-form` | reactive forms + PrimeNG inputs |
| `dx-chart` / `dx-pie-chart` | `p-chart` (Chart.js) |
| `dx-diagram` | **AntV G6** |
| `notify()` | `MessageService` + `p-toast` |

</details>

---

## 6. What does NOT change (the seams that make this tractable)

The codebase is already factored to make a UI-library swap mostly **template + import edits**, not a
logic rewrite:

- **The entire API/HTTP layer is DevExtreme-free** — `shared/api/*` (services, interceptors,
  `token-store`, models) and `shared/services/*` move untouched.
- **Component logic is separable from rendering** — we unit-test `CatalogComponent` /
  `ConfigComponent` by constructing them in an injection context *without rendering the template*.
  Those specs (and the `catalog-graph.ts` / API-layer specs) survive the migration with little/no
  change.
- **Standalone components** → migrate **screen-by-screen**; the old and new libraries can coexist
  during the transition.
- **Pure mappers** (`catalog-graph.ts`) already decouple the diagram's data from its widget.
- **The guarded e2e smoke** (`src/e2e/backend-smoke.spec.ts`) is UI-library-agnostic — it keeps
  verifying the backend contract throughout.

---

## 7. Phased execution plan

Each phase ends green (lint + unit + build) and is independently shippable.

| Phase | Work | Exit criteria |
|---|---|---|
| **0. Spike** | Dependency-availability gate **done ✅** (Phase 0 result). Remaining: stand up the `inspector-ui2` template (license check, align Angular 21.0→21.2.x, strip legacy `assets/css`); rebuild **one** representative screen (e.g. `diagnoses` — a grid + toast) inside its `vertical` layout with ag-Grid + ngx-toastr; confirm theming, dark mode, and Vitest interplay. | One screen renders in the house shell + tests pass; go/no-go confirmed. |
| **1. Theming + layout shell** — **DONE ✅ (2026-06-11)** | Done in `inspector-ui2/`: UCC `shared/api` ported to `src/app/ucc/api/` (error interceptor → ngx-toastr; `/connect` bounce deferred until that screen ports); main HttpClient now runs UCC's auth+error interceptors (template's Pronto-OAuth interceptor unwired — its 401 refresh→logout flow misfires on ControlApi); ag-Grid theme reactive to the gamma scheme (`GammaConfigService.config$`, incl. `auto`); legacy `assets/css|js|plugins` + fontawesome/glyphicons/slick fonts deleted; `environment.apiBaseUrl` added. Build green, live-verified. | App frame renders in both themes; nav works; matches product-line look. |
| **2. Primitives** — **in progress (2026-06-11)** | Done so far in `inspector-ui2/`: `connect` screen (token login, empty layout) + `uccAuthGuard` on all UCC routes; `UccConfirmService` (MatDialog confirm, replaces dx `confirm()`); `notify→ngx-toastr` throughout ported screens; **AssistPanel ported** (`ucc/components/assist-panel.component` — all 7 intents, SQL/sample/draft-toon/nextRuns/findings sections, Tailwind tables) + `AssistDialog` host; wired into jobs "New schedule" (nl-to-schedule) and the diagnoses detail "Refine as alert" (diagnose-and-alert), both live-verified. **All primitives ported (2026-06-11): `config`** (spec-driven dynamic form — select/number/boolean/text + comma-separated ARRAY editor, cross-field rules, validate-draft/file round-trip live-verified with real findings), **`enrichment`** (jobs grid + runs/lineage/report detail tabs), **`assist`** (intent selector + AssistPanel, panel re-keys on intent change). Phase 2 is effectively **DONE** for all ported screens. | All non-grid/non-chart widgets migrated; tests green. |
| **3. Grids** — **in progress (2026-06-11)** | Shared grid kit at `src/app/ucc/grid/`: `UccGridThemeService` (scheme-reactive theme), `UCC_DEFAULT_COL_DEF`, `fmtDateTime`, `actionsColumn()` + `UccActionsCell` (Angular cell renderer with mat-icon buttons, replaces dx command columns), `refreshActionsCells` workaround (see risks). **Ported + live-verified: `pipelines`** (toolbar, auto-refresh, trigger/pause/reprocess/open actions, reprocess dialog, run-all), **`jobs`** (runs-history dialog, 404 empty state, New-schedule assist dialog), **and `pipeline-detail`** (6 tabs — batches/files/lineage/quarantine/commits/report; auto-derived columns via `autoColumns()`, file-stats tiles + status filter, datepicker report window, batch-detail dialog with member-files + lineage grids; verified against live ControlApi incl. the report stats table). **All grid screens ported (2026-06-11)** — config/enrichment/assist done too. Remaining: dashboard (P4 charts), catalog (P5 diagram). NB: a template runtime error (undefined `affectedFields.join`) silently killed change detection on the config screen — symptom was "data set on the component but UI never updates"; guard loose-spec fields in templates. | All 17 grids on the new table; per-screen live smoke. |
| **4. Charts** — **DONE ✅ (2026-06-12)** | Dashboard ported to `inspector-ui2` with **Chart.js 4** (`chart.js` installed; cache-clear ritual applied). `ucc/components/chart.component.ts` = thin theme-aware `<ucc-chart>` host (registers `registerables`, recreates on data/scheme change, dark/light axis+legend colors from `GammaConfigService`). Dashboard screen: KPI tiles (READY ring, paused/quarantine/error-rate accents), latency bar + outcomes doughnut, per-pipeline ag-grid, collapsible raw Prometheus `<pre>`. `''` now redirects to `/dashboard`; nav entry added. Live-verified: tiles from real `/ready`+`/status`+`/report`, both canvases painted, voucher pipeline row, metrics toggle shows real exposition text, zero console errors. | Dashboard parity. |
| **5. Diagram** — **DONE ✅ (2026-06-12)** | Catalog ported to `inspector-ui2` with **AntV G6 5.1.1**. `catalog-graph.ts` mapper rewritten for G6 data (`from/to → source/target`, kind→G6 node type circle/rect/ellipse/diamond/hexagon/triangle, same per-kind palette + `legendFor`); `graph-view.component.ts` = read-only scheme-reactive G6 host (antv-dagre LR layout, pan/zoom/drag, node-click emits id). Screen: Tables (overlay columns, quick-filter, pagination, row→detail), KPIs, Graph traversal toolbar (from/depth/direction/kinds/edgeKinds/overlay). `node-detail.dialog.ts` walks neighbours in place + explain-entity AssistPanel re-keyed per node. **`skipLibCheck: true` added to tsconfig** — G6's transitive types (Pixi/WebXR globals) don't compile under our lib set. Live-verified: tables grid (3 nodes), whole-graph (739 nodes) + scoped traversal (7 nodes) rendered, legend, dialog + neighbour walk + assist panel; node-click chain verified via `graph.emit('node:click')` (synthetic pointer events don't penetrate @antv/g's event system — preview-tooling limitation, not a bug). | Catalog graph parity (live-verified, like the dx version was). |
| **6. Teardown** — **DONE ✅ (2026-06-12) for `inspector-ui2`** | Done in `inspector-ui2/`: diagnoses `?demo=1` seed removed (always loads from API); error interceptor now bounces 401 → `/connect` (live-verified: cleared tokens + refresh → landed on `/connect`, re-auth restored dashboard); compact/futuristic/horizontal nav arrays re-pointed at the UCC `defaultNavigation` (mock-api fill loops only clone `children`, so sharing is safe); Example module, landing `home` module and their routes deleted (zero references remain). **Vitest ported**: test target switched `@angular/build:karma → unit-test` (runner `vitest`; no `runnerConfig` — the `deps.inline` hack was DevExtreme-only and is not needed), `tsconfig.spec.json` types → `vitest/globals`, `vitest`+`jsdom` installed, `test:ci` script added. Specs ported into `ucc/api/` (token-store, auto-refresh, auth.interceptor, api-base, pipelines.service) + `catalog-graph.spec.ts` adapted to the G6 mapper (`toG6Data` source/target, circle fallback; the old all-shapes-distinct assertion dropped — SCHEMA/TABLE both map to `rect`, distinctness now asserted on colours). **6 files / 29 tests green; `ng build` green.** Old dx component specs (catalog/config) not ported — they tested DevExtreme widgets that no longer exist. **Epilogue (2026-06-12): old app retired** — the DevExtreme `inspector-ui/` was deleted and `inspector-ui2/` renamed to `inspector-ui/` (canonical path; `inspector-ui2` references in this doc describe the transition period). Guarded e2e `backend-smoke.spec.ts` carried over to `src/e2e/`; `ui.yml` collapsed to one npm job; `package.ps1` + repo docs switched pnpm→npm and :4200→:4204; UCC-focused README written. | No DevExtreme references in the new app; suite + builds green. |

---

## 8. Rough effort (order-of-magnitude, 1 engineer)

| Phase | Estimate |
|---|---|
| 0. Spike | 1–2 days |
| 1. Theming + layout | 2–4 days |
| 2. Primitives | 2–3 days |
| 3. Grids (the bulk) | 4–7 days |
| 4. Charts | 1 day |
| 5. Diagram (G6) | ~1 day (mappers exist) + polish |
| 6. Teardown | 1 day |
| **Total** | **~2–3 weeks** elapsed, dominated by grids |

(Estimates originally assumed Option A; they hold for Option C — the template shrinks Phase 1
toward "adopt + wire nav" (~2 days), offsetting the ag-Grid wrapper learning curve in Phase 3.)

---

## 9. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| ~~Target lib doesn't support Angular 21~~ **— RESOLVED ✅ (2026-06-09)** | — | Verified vs. npm: PrimeNG `21.1.9` (peer `^21.0.7`), AntV G6 `5.1.1` + Chart.js `4.5.1` (framework-agnostic), ag-Grid `35.3.1` (peer `>=18`), Material `21.2.14` (pin 21.x). Only ngx-graph/ngx-charts lag (alpha-only on 21) → avoid; use Chart.js + G6. **Option A is fully green.** |
| Angular 22 (already GA) bump done *after* migrating | Low–Medium | Material/ag-Grid both ship Angular-22-ready lines; sequence the template's `@angular/*` 21.0.0 deps with any bump. (PrimeNG-specific sequencing is moot under Option C.) |
| ThemeForest template license doesn't cover this use | Low | Verify the purchase/license terms before Phase 0 spike work begins (standard license is single end-product). |
| Template drift: `inspector-ui2` is a snapshot (Angular 21.0.0, Jasmine/Karma) vs our 21.2.13 + Vitest | Medium | Treat `@gamma` as vendored source we own (like the dx template files today); align versions in the spike; carry our Vitest setup over rather than adopting Karma. |
| Theming/dark-mode parity gap vs. the tuned DevExtreme themes | Medium | Phase 1 is dedicated to this; accept minor visual drift; capture before/after screenshots. |
| Grid behavior parity (server vs. client paging, search semantics, selection) | Low | Feature surface is tiny (search/paging/selection); build one wrapper, verify per screen. |
| AntV G6 v5 API churn / not Angular-native | Low | Pin v5, use v5 docs; thin imperative wrapper (sketch in G6 notes). |
| Two grid/UI libs coexisting inflates bundle mid-migration | Low (temporary) | Tolerate during transition; Phase 6 removes DevExtreme and shrinks it. |
| Canvas widgets (G6, charts) don't render under jsdom | Low | Already our pattern: unit-test pure logic, live-smoke the render. |
| ag-grid-angular 35 + Angular 21 shell: Angular cell renderers silently skip creation on the *initial* row render (empty cells, no error) | **Observed (2026-06-11)** | Workaround in place: `refreshActionsCells` forces a column refresh on `(firstDataRendered)`/`(rowDataUpdated)` — proven reliable. Re-test on ag-grid/Angular bumps. Also: Vite dep re-optimization mid-serve can dual-load `@angular/core` (NG0203 noise) — clear `.angular/cache` + restart `ng serve` after adding dependencies. |
| Scope creep (redesign while porting) | Medium | Explicitly a **like-for-like** port; defer redesign. |

---

## 10. Testing strategy

- **Keep** the API/service specs and the pure-logic component specs (injection-context pattern) —
  they're library-agnostic.
- **Keep** the guarded e2e smoke (`E2E_BASE_URL`) — it verifies the backend contract regardless of UI.
- **Per-screen live smoke** during phases 3–5 (run `ControlApi` + the SPA, eyeball each migrated
  screen) — the same approach used to verify the dxDiagram and the e2e originally.
- **Phase 6** likely lets us **delete** the `vitest.config.ts` `deps.inline` workaround (it exists
  only because of DevExtreme's ESM directory imports) — a small cleanup win.

---

## 11. Rollback & coexistence

- Migrate on a feature branch off `4.x`; standalone components allow old/new screens side-by-side,
  so the branch stays releasable at each phase boundary.
- DevExtreme is only fully removed in Phase 6 — until then, any screen can revert by pointing the
  route back at the dx component.

---

## 12. Open decisions (resolve before Phase 0)

1. **Which stack?** — **DECIDED (2026-06-11): Option C** — the gamma-analytics house template
   (Material 21 + Tailwind) + ag-Grid Community + Chart.js + G6, to match the product line's look
   and feel. Supersedes the earlier Option A (PrimeNG) recommendation. Remaining sub-decision:
   `vertical/classic` vs `vertical/dense` layout, and confirming the template license.
2. **Diagram lib:** AntV G6 (recommended) vs. ngx-graph (simpler) vs. Cytoscape.
3. **Like-for-like port, or fold in a light visual refresh?** — recommendation: like-for-like.
4. **Trigger:** is this license-cost-driven (hard requirement) or opportunistic (nice-to-have)? That
   sets priority vs. other roadmap work.
